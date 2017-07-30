/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.tomcat.util.net;

import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.jsse.JSSESocketFactory;


/**
 * Handle incoming TCP connections.
 *
 * This class implement a simple server model: one listener thread accepts on a socket and
 * creates a new worker thread for each incoming connection.
 *
 * More advanced Endpoints will reuse the threads, use queues, etc.
 *
 * @author James Duncan Davidson
 * @author Jason Hunter
 * @author James Todd
 * @author Costin Manolache
 * @author Gal Shachor
 * @author Yoav Shapira
 * @author Remy Maucherat
 */
public class JIoEndpoint extends AbstractEndpoint<Socket> {


    // -------------------------------------------------------------- Constants

    private static final Log log = LogFactory.getLog(JIoEndpoint.class);

    // ----------------------------------------------------------------- Fields

    /**
     * Associated server socket.
     */
    protected ServerSocket serverSocket = null;


    // ------------------------------------------------------------ Constructor

    public JIoEndpoint() {
        // Set maxConnections to zero so we can tell if the user has specified
        // their own value on the connector when we reach bind()
        setMaxConnections(0);                                               // 设置 JioEndPoint 的最大的连接数, 主要是通过 LimitLatch 来进行控制
        // Reduce the executor timeout for BIO as threads in keep-alive will not
        // terminate when the executor interrupts them.
        setExecutorTerminationTimeoutMillis(0);                             // 这个值是 当 JioEndPoint 关闭时 executor await 的时间 (因为可能有些事件还在处理中)
    }

    // ------------------------------------------------------------- Properties

    /**
     * Handling of accepted sockets.
     */
    protected Handler handler = null;
    public void setHandler(Handler handler ) { this.handler = handler; }
    public Handler getHandler() { return handler; }

    /**
     * Server socket factory.
     */
    protected ServerSocketFactory serverSocketFactory = null;
    public void setServerSocketFactory(ServerSocketFactory factory) { this.serverSocketFactory = factory; }
    public ServerSocketFactory getServerSocketFactory() { return serverSocketFactory; }

    /**
     * Port in use.
     */
    @Override
    public int getLocalPort() {
        ServerSocket s = serverSocket;
        if (s == null) {
            return -1;
        } else {
            return s.getLocalPort();
        }
    }


    @Override
    public String[] getCiphersUsed() {
        if (serverSocketFactory instanceof JSSESocketFactory) {
            return ((JSSESocketFactory) serverSocketFactory).getEnabledCiphers();
        }
        return new String[0];
    }


    /*
     * Optional feature support.
     */
    @Override
    public boolean getUseSendfile() { return false; } // Not supported
    @Override
    public boolean getUseComet() { return false; } // Not supported
    @Override
    public boolean getUseCometTimeout() { return false; } // Not supported
    @Override
    public boolean getDeferAccept() { return false; } // Not supported
    @Override
    public boolean getUsePolling() { return false; } // Not supported


    // ------------------------------------------------ Handler Inner Interface

    /**
     * Bare bones interface used for socket processing. Per thread data is to be
     * stored in the ThreadWithAttributes extra folders, or alternately in
     * thread local fields.
     */
    public interface Handler extends AbstractEndpoint.Handler {
        public SocketState process(SocketWrapper<Socket> socket,
                SocketStatus status);
        public SSLImplementation getSslImplementation();
        public void beforeHandshake(SocketWrapper<Socket> socket);
    }


    /**
     * Async timeout thread
     */
    protected class AsyncTimeout implements Runnable {
        /**
         * The background thread that checks async requests and fires the
         * timeout if there has been no activity.
         */
        @Override
        public void run() {

            // Loop until we receive a shutdown command
            while (running) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    // Ignore
                }
                long now = System.currentTimeMillis();
                Iterator<SocketWrapper<Socket>> sockets =
                    waitingRequests.iterator();
                while (sockets.hasNext()) {
                    SocketWrapper<Socket> socket = sockets.next();
                    long access = socket.getLastAccess();
                    if (socket.getTimeout() > 0 &&
                            (now-access)>socket.getTimeout()) {
                        processSocket(socket, SocketStatus.TIMEOUT, true);
                    }
                }

                // Loop if endpoint is paused
                while (paused && running) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

            }
        }
    }


    // --------------------------------------------------- Acceptor Inner Class
    /**
     * The background thread that listens for incoming TCP/IP connections and
     * hands them off to an appropriate processor.
     *
     * 1. 在没有网络 IO 数据时, 该线程会一直 在 serverSocketFactory.acceptSocket 上阻塞
     * 2. 如果有请求, 则首先通过 countUpOrAwaitConnection 来获取请求处理得许可(用的是LimitLatch)
     * 3. 若获取 LimitLatch 成功, 则首先将 socket 数据设置 Connector 配置的一些属性,
     * 4. 封装成 SocketProcessor 交由工作线程池来处理
     */
    protected class Acceptor extends AbstractEndpoint.Acceptor {

        @Override
        public void run() {

            int errorDelay = 0;

            // Loop until we receive a shutdown command
            while (running) {

                // Loop if endpoint is paused
                while (paused && running) {
                    state = AcceptorState.PAUSED;
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

                if (!running) {
                    break;
                }
                state = AcceptorState.RUNNING;

                try {
                    //if we have reached max connections, wait
                    countUpOrAwaitConnection();                 // 通过 LimitLatch 来控制连接处理数, 所以 BIO 情况下的 tomcat 并发请求数 = backlog + LimitLatch.maxConnection

                    Socket socket = null;
                    try {
                        // Accept the next incoming connection from the server
                        // socket
                        socket = serverSocketFactory.acceptSocket(serverSocket);
                    } catch (IOException ioe) {
                        countDownConnection();
                        // Introduce delay if necessary
                        errorDelay = handleExceptionWithDelay(errorDelay);
                        // re-throw
                        throw ioe;
                    }
                    // Successful accept, reset the error delay
                    errorDelay = 0;

                    // Configure the socket
                    if (running && !paused && setSocketOptions(socket)) {
                        // Hand this socket off to an appropriate processor
                        if (!processSocket(socket)) {                           // 若处理出错, 直接 countDownConnection (减去并发连接处理的个数)
                            countDownConnection();
                            // Close socket right away
                            closeSocket(socket);
                        }
                    } else {
                        countDownConnection();
                        // Close socket right away
                        closeSocket(socket);
                    }
                } catch (IOException x) {
                    if (running) {
                        log.error(sm.getString("endpoint.accept.fail"), x);
                    }
                } catch (NullPointerException npe) {
                    if (running) {
                        log.error(sm.getString("endpoint.accept.fail"), npe);
                    }
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    log.error(sm.getString("endpoint.accept.fail"), t);
                }
            }
            state = AcceptorState.ENDED;
        }
    }


    private void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // Ignore
        }
    }


    // ------------------------------------------- SocketProcessor Inner Class


    /**
     * This class is the equivalent of the Worker, but will simply use in an
     * external Executor thread pool.
     */
    protected class SocketProcessor implements Runnable {

        protected SocketWrapper<Socket> socket = null;
        protected SocketStatus status = null;

        public SocketProcessor(SocketWrapper<Socket> socket) {
            if (socket==null) throw new NullPointerException();
            this.socket = socket;
        }

        public SocketProcessor(SocketWrapper<Socket> socket, SocketStatus status) {
            this(socket);
            this.status = status;
        }

        @Override
        public void run() {
            boolean launch = false;
            synchronized (socket) {
                try {
                    /**
                     * state 标志着当前 socket 的状态, 这也是 为什么 AbstractHttp11Processor 会设置这个 SocketState 的原因了
                     */
                    SocketState state = SocketState.OPEN;                                  // 初始化 Socket 的状态位 OPEN (通过这个判断来判断是否 这个Socket的请求处理 OK 了)
                    handler.beforeHandshake(socket);
                    // 若是 SSL 则先握手
                    try {
                        // SSL handshake
                        serverSocketFactory.handshake(socket.getSocket());              // 若是 配置了 SSL, 则在这里完成 SSL 握手的操作
                    } catch (Throwable t) {
                        ExceptionUtils.handleThrowable(t);
                        if (log.isDebugEnabled()) {
                            log.debug(sm.getString("endpoint.err.handshake"), t);
                        }
                        // Tell to close the socket
                        state = SocketState.CLOSED;
                    }

                    // 让工作线程干活
                    if ((state != SocketState.CLOSED)) {
                        if (status == null) {   // 调用 AbstractHttp11Protocol 最后进入 Tomcat 后端容器
                            // 通过 Http11Protocol$Http11ConnectionHandler 处理请求, 应用外部对象的成员 handler
                            state = handler.process(socket, SocketStatus.OPEN_READ);
                        } else {
                            state = handler.process(socket,status);
                        }
                    }
                    // 收尾工作
                    /**
                     * state = CLOSED 直接关闭 socket 就可以了, 这也意味着, 当前 RCP 的连接关闭, 产生 TIME_WAIT 了
                     * 如果 是 OPEN, 说明该请求后续可能还有请求利用这个 socket
                     */
                    if (state == SocketState.CLOSED) {
                        // Close socket
                        if (log.isTraceEnabled()) {
                            log.trace("Closing socket:"+socket);
                        }
                        countDownConnection();                              // 这里明确进行类 CountDownConnection()
                        try {
                            socket.getSocket().close(); // 在处理好业务后, 进行 socket 的关闭
                        } catch (IOException e) {
                            // Ignore
                        }
                        /**
                         * 当发现 SocketState 重置为 Open 了, 说明 在 Http11Processor中Keepalive 模式下, 后续的请求还没有处理完
                         */
                    } else if (state == SocketState.OPEN ||     // 若是 OPEN  则说明是 Keepalive 在工作
                            state == SocketState.UPGRADING  ||
                            state == SocketState.UPGRADED){
                        socket.setKeptAlive(true);
                        socket.access();
                        launch = true;
                    } else if (state == SocketState.LONG) {
                        socket.access();
                        waitingRequests.add(socket);
                    }
                } finally {
                    /**
                     * keepalive 总结:
                     * 下面的代码说明: 在 keepalive 模式下, 好不容易 SocketProcessor 执行完了, 当前工作线程退出, 但在工作线程退出之前, 有新启动一个工作线程, 使用的还是原来的 socket
                     * 意味着: 在 KeepAlive 模式下, 只要页面的 request 请求没有完成, 那么工作线程一直就会占据着, 工作线程基本不会释放
                     */
                    if (launch) {   // lauch 属性 = true, 说明这个事 KeepAlive, 重新利用这个 socket 创建一个工作线程
                        try {
                            getExecutor().execute(new SocketProcessor(socket, SocketStatus.OPEN_READ));
                        } catch (RejectedExecutionException x) {
                            log.warn("Socket reprocessing request was rejected for:"+socket,x);
                            try {
                                //unable to handle connection at this time
                                handler.process(socket, SocketStatus.DISCONNECT);
                            } finally {
                                countDownConnection();
                            }


                        } catch (NullPointerException npe) {
                            if (running) {
                                log.error(sm.getString("endpoint.launch.fail"),
                                        npe);
                            }
                        }
                    }
                }
            }
            socket = null;
            // Finish up this request
        }

    }


    // -------------------- Public methods --------------------

    /**操作步骤
     * 1. 初始化 接收请求线程数
     * 2. 初始化工作线程数
     * 3. java 程序绑定对应的端口
     */
    @Override
    public void bind() throws Exception {

        // Initialize thread count defaults for acceptor
        if (acceptorThreadCount == 0) {                     // 接收请求的处理线程数, 也就是 Accepter 的个数(缺省值 1)
            acceptorThreadCount = 1;
        }
        // Initialize maxConnections
        if (getMaxConnections() == 0) {
            // User hasn't set a value - use the default
            setMaxConnections(getMaxThreadsExecutor(true));
        }

        if (serverSocketFactory == null) {  // 初始化 serverSocketFactory
            if (isSSLEnabled()) {               // 若是 基于 SSL 的
                serverSocketFactory =
                    handler.getSslImplementation().getServerSocketFactory(this);
            } else {
                serverSocketFactory = new DefaultServerSocketFactory(this);
            }
        }

        if (serverSocket == null) {                     // 初始化 服务端的 serverSocket, 下面的 backLog 注意一下, backLog + LimitLatch.limit 决定 Tomcat 的并发请求
            try {                                          // backLog 指的是 TCP 中的 accept queue,  LimitLatch.limit 控制的是 Tomcat 层面的并发请求数
                if (getAddress() == null) {
                    serverSocket = serverSocketFactory.createSocket(getPort(),
                            getBacklog());
                } else {
                    serverSocket = serverSocketFactory.createSocket(getPort(),
                            getBacklog(), getAddress());
                }
            } catch (BindException orig) {
                String msg;
                if (getAddress() == null)
                    msg = orig.getMessage() + " <null>:" + getPort();
                else
                    msg = orig.getMessage() + " " +
                            getAddress().toString() + ":" + getPort();
                BindException be = new BindException(msg);
                be.initCause(orig);
                throw be;
            }
        }

    }

    @Override
    public void startInternal() throws Exception {

        if (!running) {
            running = true;
            paused = false;

            // Create worker collection
            if (getExecutor() == null) {
                createExecutor();       // 工作线程池
            }

            initializeConnectionLatch();                // 初始化限流阀

            startAcceptorThreads(); // Accept 连接线程池

            // Start async timeout thread   检查  Timeout 的线程池
            Thread timeoutThread = new Thread(new AsyncTimeout(),
                    getName() + "-AsyncTimeout");
            timeoutThread.setPriority(threadPriority);
            timeoutThread.setDaemon(true);
            timeoutThread.start();
        }
    }

    @Override
    public void stopInternal() {
        releaseConnectionLatch();
        if (!paused) {
            pause();
        }
        if (running) {
            running = false;
            unlockAccept();
        }
        shutdownExecutor();
    }

    /**
     * Deallocate APR memory pools, and close server socket.
     */
    @Override
    public void unbind() throws Exception {
        if (running) {
            stop();
        }
        if (serverSocket != null) {
            try {
                if (serverSocket != null)
                    serverSocket.close();
            } catch (Exception e) {
                log.error(sm.getString("endpoint.err.close"), e);
            }
            serverSocket = null;
        }
        handler.recycle();
    }


    @Override
    protected AbstractEndpoint.Acceptor createAcceptor() {
        return new Acceptor();
    }


    /**
     * Configure the socket.
     */
    protected boolean setSocketOptions(Socket socket) {
        try {
            // 1: Set socket options: timeout, linger, etc
            socketProperties.setProperties(socket);
        } catch (SocketException s) {
            //error here is common if the client has reset the connection
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("endpoint.err.unexpected"), s);
            }
            // Close the socket
            return false;
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.error(sm.getString("endpoint.err.unexpected"), t);
            // Close the socket
            return false;
        }
        return true;
    }


    /**
     * Process a new connection from a new client. Wraps the socket so
     * keep-alive and other attributes can be tracked and then passes the socket
     * to the executor for processing.
     *
     * @param socket    The socket associated with the client.
     *
     * @return          <code>true</code> if the socket is passed to the
     *                  executor, <code>false</code> if something went wrong or
     *                  if the endpoint is shutting down. Returning
     *                  <code>false</code> is an indication to close the socket
     *                  immediately.
     */
    protected boolean processSocket(Socket socket) {
        // Process the request from this socket
        try {
            // 将 Socket 包装成 SocketWrapper
            SocketWrapper<Socket> wrapper = new SocketWrapper<>(socket);
            wrapper.setKeepAliveLeft(getMaxKeepAliveRequests());            // KeepAlive 剩余请求数
            wrapper.setSecure(isSSLEnabled());                              // 连接是否使用 SSL
            // During shutdown, executor may be null - avoid NPE
            if (!running) {
                return false;
            }
            // 包装成 SocketProcessor 交给线程池处理, 当前 Acceptor 线程不处理, 一遍收到下一个到达的请求
            getExecutor().execute(new SocketProcessor(wrapper));
        } catch (RejectedExecutionException x) {
            log.warn("Socket processing request was rejected for:"+socket,x);
            return false;
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            // This means we got an OOM or similar creating a thread, or that
            // the pool and its queue are full
            log.error(sm.getString("endpoint.process.fail"), t);
            return false;
        }
        return true;
    }


    @Override
    public void processSocket(SocketWrapper<Socket> socket,
            SocketStatus status, boolean dispatch) {
        try {
            // Synchronisation is required here as this code may be called as a
            // result of calling AsyncContext.dispatch() from a non-container
            // thread
            synchronized (socket) {
                if (waitingRequests.remove(socket)) {
                    SocketProcessor proc = new SocketProcessor(socket,status);
                    Executor executor = getExecutor();
                    if (dispatch && executor != null) {
                        // During shutdown, executor may be null - avoid NPE
                        if (!running) {
                            return;
                        }
                        getExecutor().execute(proc);
                    } else {
                        proc.run();
                    }
                }
            }
        } catch (RejectedExecutionException ree) {
            log.warn(sm.getString("endpoint.executor.fail", socket) , ree);
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            // This means we got an OOM or similar creating a thread, or that
            // the pool and its queue are full
            log.error(sm.getString("endpoint.process.fail"), t);
        }
    }

    protected ConcurrentLinkedQueue<SocketWrapper<Socket>> waitingRequests =
            new ConcurrentLinkedQueue<>();

    @Override
    protected Log getLog() {
        return log;
    }
}

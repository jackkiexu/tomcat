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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.X509KeyManager;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.collections.SynchronizedQueue;
import org.apache.tomcat.util.collections.SynchronizedStack;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.SecureNioChannel.ApplicationBufferHandler;
import org.apache.tomcat.util.net.jsse.NioX509KeyManager;

/**
 * NIO tailored thread pool, providing the following services:
 * <ul>
 * <li>Socket acceptor thread</li>
 * <li>Socket poller thread</li>
 * <li>Worker threads pool</li>
 * </ul>
 *
 * When switching to Java 5, there's an opportunity to use the virtual
 * machine's thread pool.
 *
 * 参考资料
 * http://zddava.iteye.com/blog/835244
 * http://tyrion.iteye.com/blog/2256898
 * http://tyrion.iteye.com/blog/2256896
 * http://blog.csdn.net/yfkscu/article/details/38144029
 * http://www.blogjava.net/zddava/archive/2010/12/08/340029.html
 * http://tyrion.iteye.com/blog/2256896
 * http://ifeve.com/how-tomcat-implements-keep-alive/
 * https://mp.weixin.qq.com/s?__biz=MzA4MTc3Nzk4NQ==&mid=2650075870&idx=1&sn=51feda8e62906f97a8937076cbce786f&mpshare=1&scene=23&srcid=0620P9MIR65qjXpu4lXVKAGs#rd
 *
 * @author Mladen Turk
 * @author Remy Maucherat
 *
 * NioEndPoint 类包含以下组件:
 * 1. socket acceptor 线程池
 * 2. PollerEvent 数组
 * 3. socket poller 线程池
 * 4. 工作线程池
 *
 */
public class NioEndpoint extends AbstractEndpoint<NioChannel> {


    // -------------------------------------------------------------- Constants


    private static final Log log = LogFactory.getLog(NioEndpoint.class);


    public static final int OP_REGISTER = 0x100; //register interest op
    public static final int OP_CALLBACK = 0x200; //callback interest op

    // ----------------------------------------------------------------- Fields

    private NioSelectorPool selectorPool = new NioSelectorPool();

    /**
     * Server socket "pointer".
     */
    private ServerSocketChannel serverSock = null;

    /**
     * use send file
     */
    private boolean useSendfile = true;

    /**
     * The size of the OOM parachute.
     */
    private int oomParachute = 1024*1024;
    /**
     * The oom parachute, when an OOM error happens,
     * will release the data, giving the JVM instantly
     * a chunk of data to be able to recover with.
     */
    private byte[] oomParachuteData = null;

    /**
     * Make sure this string has already been allocated
     */
    private static final String oomParachuteMsg =
        "SEVERE:Memory usage is low, parachute is non existent, your system may start failing.";

    /**
     * Keep track of OOM warning messages.
     */
    private long lastParachuteCheck = System.currentTimeMillis();

    /**
     *
     */
    private volatile CountDownLatch stopLatch = null;

    /**
     * Cache for SocketProcessor objects
     */
    private final SynchronizedStack<SocketProcessor> processorCache =
            new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                    socketProperties.getProcessorCache());

    /**
     * Cache for key attachment objects
     */
    private final SynchronizedStack<KeyAttachment> keyCache =
            new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                    socketProperties.getKeyCache());

    /**
     * Cache for poller events
     */
    private final SynchronizedStack<PollerEvent> eventCache =
            new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                    socketProperties.getEventCache());

    /**
     * Bytebuffer cache, each channel holds a set of buffers (two, except for SSL holds four)
     */
    private final SynchronizedStack<NioChannel> nioChannels =
            new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                    socketProperties.getBufferPoolSize());


    // ------------------------------------------------------------- Properties


    /**
     * Generic properties, introspected
     */
    @Override
    public boolean setProperty(String name, String value) {
        final String selectorPoolName = "selectorPool.";
        try {
            if (name.startsWith(selectorPoolName)) {
                return IntrospectionUtils.setProperty(selectorPool, name.substring(selectorPoolName.length()), value);
            } else {
                return super.setProperty(name, value);
            }
        }catch ( Exception x ) {
            log.error("Unable to set attribute \""+name+"\" to \""+value+"\"",x);
            return false;
        }
    }


    /**
     * Priority of the poller threads.
     */
    private int pollerThreadPriority = Thread.NORM_PRIORITY;
    public void setPollerThreadPriority(int pollerThreadPriority) { this.pollerThreadPriority = pollerThreadPriority; }
    public int getPollerThreadPriority() { return pollerThreadPriority; }


    /**
     * Handling of accepted sockets.
     */
    private Handler handler = null;
    public void setHandler(Handler handler ) { this.handler = handler; }
    public Handler getHandler() { return handler; }


    /**
     * Allow comet request handling.
     */
    private boolean useComet = true;
    public void setUseComet(boolean useComet) { this.useComet = useComet; }
    @Override
    public boolean getUseComet() { return useComet; }
    @Override
    public boolean getUseCometTimeout() { return getUseComet(); }
    @Override
    public boolean getUsePolling() { return true; } // Always supported


    /**
     * Poller thread count.
     */
    private int pollerThreadCount = Math.min(2,Runtime.getRuntime().availableProcessors());
    public void setPollerThreadCount(int pollerThreadCount) { this.pollerThreadCount = pollerThreadCount; }
    public int getPollerThreadCount() { return pollerThreadCount; }

    private long selectorTimeout = 1000;
    public void setSelectorTimeout(long timeout){ this.selectorTimeout = timeout;}
    public long getSelectorTimeout(){ return this.selectorTimeout; }
    /**
     * The socket poller.
     */
    private Poller[] pollers = null;
    private AtomicInteger pollerRotater = new AtomicInteger(0);
    /**
     * Return an available poller in true round robin fashion
     */
    // 以 RR 的方式从 Poller 里面获取一个进行处理
    public Poller getPoller0() {
        // 最简单的轮询调度算法, poller的计数器不断加1再对 poller数组取余数
        int idx = Math.abs(pollerRotater.incrementAndGet()) % pollers.length;
        return pollers[idx];
    }


    public void setSelectorPool(NioSelectorPool selectorPool) {
        this.selectorPool = selectorPool;
    }

    public void setSocketProperties(SocketProperties socketProperties) {
        this.socketProperties = socketProperties;
    }

    public void setUseSendfile(boolean useSendfile) {
        this.useSendfile = useSendfile;
    }

    /**
     * Is deferAccept supported?
     */
    @Override
    public boolean getDeferAccept() {
        // Not supported
        return false;
    }

    public void setOomParachute(int oomParachute) {
        this.oomParachute = oomParachute;
    }

    public void setOomParachuteData(byte[] oomParachuteData) {
        this.oomParachuteData = oomParachuteData;
    }


    private SSLContext sslContext = null;
    public SSLContext getSSLContext() { return sslContext;}
    public void setSSLContext(SSLContext c) { sslContext = c;}
    private String[] enabledCiphers;
    private String[] enabledProtocols;

    /**
     * Port in use.
     */
    @Override
    public int getLocalPort() {
        ServerSocketChannel ssc = serverSock;
        if (ssc == null) {
            return -1;
        } else {
            ServerSocket s = ssc.socket();
            if (s == null) {
                return -1;
            } else {
                return s.getLocalPort();
            }
        }
    }


    @Override
    public String[] getCiphersUsed() {
        return enabledCiphers;
    }


    // --------------------------------------------------------- OOM Parachute Methods

    protected void checkParachute() {
        boolean para = reclaimParachute(false);
        if (!para && (System.currentTimeMillis()-lastParachuteCheck)>10000) {
            try {
                log.fatal(oomParachuteMsg);
            }catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                System.err.println(oomParachuteMsg);
            }
            lastParachuteCheck = System.currentTimeMillis();
        }
    }

    protected boolean reclaimParachute(boolean force) {
        if ( oomParachuteData != null ) return true;
        if ( oomParachute > 0 && ( force || (Runtime.getRuntime().freeMemory() > (oomParachute*2))) )
            oomParachuteData = new byte[oomParachute];
        return oomParachuteData != null;
    }

    protected void releaseCaches() {
        this.keyCache.clear();
        this.nioChannels.clear();
        this.processorCache.clear();
        if ( handler != null ) handler.recycle();

    }

    // --------------------------------------------------------- Public Methods
    /**
     * Number of keepalive sockets.
     */
    public int getKeepAliveCount() {
        if (pollers == null) {
            return 0;
        } else {
            int sum = 0;
            for (int i=0; i<pollers.length; i++) {
                sum += pollers[i].getKeyCount();
            }
            return sum;
        }
    }


    // ----------------------------------------------- Public Lifecycle Methods


    /**
     * Initialize the endpoint.
     */
    @Override
    public void bind() throws Exception {
        // 初始化 ServerSocketChannel, 这里是使用阻塞的方式, 没有用 Selector
        serverSock = ServerSocketChannel.open();
        socketProperties.setProperties(serverSock.socket());
        InetSocketAddress addr = (getAddress()!=null?new InetSocketAddress(getAddress(),getPort()):new InetSocketAddress(getPort()));
        serverSock.socket().bind(addr,getBacklog());                                // 绑定地址, 这里的 backLog 是请求的 socket 底层 "连接的队列" 大小
        serverSock.configureBlocking(true); //mimic APR behavior
        serverSock.socket().setSoTimeout(getSocketProperties().getSoTimeout());     // 这是 accept() 的超时时间,超时时间到了会报出异常, 但是 ServerSocket 还是有效的

        // Initialize thread count defaults for acceptor, poller
        if (acceptorThreadCount == 0) {
            // FIXME: Doesn't seem to work that well with multiple accept threads
            acceptorThreadCount = 1;
        }
        if (pollerThreadCount <= 0) {
            //minimum one poller thread
            pollerThreadCount = 1;
        }
        stopLatch = new CountDownLatch(pollerThreadCount);

        // Initialize SSL if needed
        if (isSSLEnabled()) {
            SSLUtil sslUtil = handler.getSslImplementation().getSSLUtil(this);

            sslContext = sslUtil.createSSLContext();
            sslContext.init(wrap(sslUtil.getKeyManagers()),
                    sslUtil.getTrustManagers(), null);

            SSLSessionContext sessionContext =
                sslContext.getServerSessionContext();
            if (sessionContext != null) {
                sslUtil.configureSessionContext(sessionContext);
            }
            // Determine which cipher suites and protocols to enable
            enabledCiphers = sslUtil.getEnableableCiphers(sslContext);
            enabledProtocols = sslUtil.getEnableableProtocols(sslContext);
        }

        if (oomParachute>0) reclaimParachute(true);
        selectorPool.open();
    }

    public KeyManager[] wrap(KeyManager[] managers) {
        if (managers==null) return null;
        KeyManager[] result = new KeyManager[managers.length];
        for (int i=0; i<result.length; i++) {
            if (managers[i] instanceof X509KeyManager && getKeyAlias()!=null) {
                result[i] = new NioX509KeyManager((X509KeyManager)managers[i],getKeyAlias());
            } else {
                result[i] = managers[i];
            }
        }
        return result;
    }


    /**
     * Start the NIO endpoint, creating acceptor, poller threads.
     */
    @Override
    public void startInternal() throws Exception {
        // 初始化
        if (!running) {
            running = true;
            paused = false;

            // Create worker collection // 创建 ThreadPoolExecutor 对象, 和 JDK 里的功能一样, 只不过进行一些扩展
            if ( getExecutor() == null ) {
                createExecutor();                           // 构造线程池, 用于后续执行 SocketProcessor线程
            }

            initializeConnectionLatch();

            // Start poller threads     // 开启 poll线程, 最终 Pooler 会轮询的方式处理请求
            pollers = new Poller[getPollerThreadCount()];
            for (int i=0; i<pollers.length; i++) {
                pollers[i] = new Poller();
                Thread pollerThread = new Thread(pollers[i], getName() + "-ClientPoller-"+i);
                pollerThread.setPriority(threadPriority);
                pollerThread.setDaemon(true);
                pollerThread.start();
            }
            // 开启 Acceptor 线程
            startAcceptorThreads();
        }
    }


    /**
     * Stop the endpoint. This will cause all processing threads to stop.
     */
    @Override
    public void stopInternal() {
        releaseConnectionLatch();
        if (!paused) {
            pause();
        }
        if (running) {
            running = false;
            unlockAccept();
            for (int i=0; pollers!=null && i<pollers.length; i++) {
                if (pollers[i]==null) continue;
                pollers[i].destroy();
                pollers[i] = null;
            }
            try {
                stopLatch.await(selectorTimeout + 100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignore) {
            }
        }
        eventCache.clear();
        keyCache.clear();
        nioChannels.clear();
        processorCache.clear();
        shutdownExecutor();

    }


    /**
     * Deallocate NIO memory pools, and close server socket.
     */
    @Override
    public void unbind() throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Destroy initiated for "+new InetSocketAddress(getAddress(),getPort()));
        }
        if (running) {
            stop();
        }
        // Close server socket
        serverSock.socket().close();
        serverSock.close();
        serverSock = null;
        sslContext = null;
        releaseCaches();
        selectorPool.close();
        if (log.isDebugEnabled()) {
            log.debug("Destroy completed for "+new InetSocketAddress(getAddress(),getPort()));
        }
    }


    // ------------------------------------------------------ Protected Methods


    public int getWriteBufSize() {
        return socketProperties.getTxBufSize();
    }

    public int getReadBufSize() {
        return socketProperties.getRxBufSize();
    }

    public NioSelectorPool getSelectorPool() {
        return selectorPool;
    }

    @Override
    public boolean getUseSendfile() {
        return useSendfile;
    }

    public int getOomParachute() {
        return oomParachute;
    }

    public byte[] getOomParachuteData() {
        return oomParachuteData;
    }


    @Override
    protected AbstractEndpoint.Acceptor createAcceptor() {
        return new Acceptor();
    }


    /**
     * Process the specified connection.
     * Accepter 首先根据 是否是 SSL配置, 使用 Tomcat 自身扩展的 NioChannel 来包装 SocketChannel, 之所以包装的目的是要给
     * NIO 的 channel 加很多的功能, NioChannel 持有 socketChannel 的一个 引用, 如果是 SSL 配置的话, 那么就启动的是
     * SecurityNioChannel 类包装一下,
     * 接下来 Accepter 将 这个包装的 NioChannel 直接扔给 Poller 线程, 以 addEvent 的方式, 加一个 PollerEvent 事件
     */
    protected boolean setSocketOptions(SocketChannel socket) {
        // Process the connection
        try {
            //disable blocking, APR style, we are gonna be polling it
            // 设置 IO 为 NIO 模式
            socket.configureBlocking(false);
            Socket sock = socket.socket();
            // 设置 Socket 参数值 (从 server.xml 的 Connector 节点上获取参数值)
            // 比如 Socket 发送, 接收的缓存大小, 心跳检测等
            socketProperties.setProperties(sock);

            // NioChannel 是 ByteChannel的子类
            // 从队列里取出第一个可用的Channel, 这样的话 NioChannel应该是设计成非GC 的
            // 感觉其目的主要是对 SocketChannel进行下封装
            // 从 NioChannel 的缓存队列取出一个 NioChannel
            // NioChannel是 SocketChannel的一个包装类
            // 这里对上层屏蔽 SSL 和 一般的 TCP 连接的差异
            NioChannel channel = nioChannels.pop();

            // 若缓存队列中没有则新建一个 NioChannel
            if ( channel == null ) {
                // 不过这里如果没有可用的就初始化一个的话, 请求数陡然增高再慢慢回落的时候不就浪费了内存了
                // NioBufferHandler 里分别分配了读缓冲区和写缓冲区
                // SSL setup
                if (sslContext != null) {                                                   // 是否是 Https 的请求    (这里需要注意一下, 对应的 SSL 的握手其实是在 工作线程里面 SocketProcessor, 其实最后调用的就是 Http11NioProcessor)
                    SSLEngine engine = createSSLEngine();                                      // 是 Https 则创建相应的 SSLEngine
                    int appbufsize = engine.getSession().getApplicationBufferSize();
                    NioBufferHandler bufhandler = new NioBufferHandler(Math.max(appbufsize,socketProperties.getAppReadBufSize()),       // socket 读取的最大 BufferSize 默认 8M
                                                                       Math.max(appbufsize,socketProperties.getAppWriteBufSize()),      // socket 写会数据, 最大 8M
                                                                       socketProperties.getDirectBuffer());                             // 是否启用堆外的内存
                    channel = new SecureNioChannel(socket, engine, bufhandler, selectorPool);// 这里创建的也是 SecureNioChannel (SecureNioChannel 继承 NioChannel)
                } else {
                    // normal tcp setup
                    NioBufferHandler bufhandler = new NioBufferHandler(socketProperties.getAppReadBufSize(),
                                                                       socketProperties.getAppWriteBufSize(),
                                                                       socketProperties.getDirectBuffer());

                    channel = new NioChannel(socket, bufhandler);
                }
            } else {
                // 这里就是对 Channel 的重用了
                // 将 SocketChannel 关联到从缓存队列中获取的 NioChannel 上来
                channel.setIOChannel(socket);
                if ( channel instanceof SecureNioChannel ) {
                    SSLEngine engine = createSSLEngine();
                    ((SecureNioChannel)channel).reset(engine);
                } else {
                    channel.reset();
                }
            }
            // 它将配置好的 SocketChannel 包装成一个 PollerEvent, 加入到 Poller的events缓存队列里面
            // 这里就是将 SocketChannel注册到 Poller 了
            // getPoller0 用的循环的方式来返回 Poller, 即 Poller 1, 2, 3.....n  然后再回到 1, 2, 3 (以 RR 的方式从 Poller 里面获取一个进行处理)
            getPoller0().register(channel);
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            try {
                log.error("",t);
            } catch (Throwable tt) {
                ExceptionUtils.handleThrowable(t);
            }
            // Tell to close the socket
            return false;
        }
        return true;
    }

    protected SSLEngine createSSLEngine() {
        SSLEngine engine = sslContext.createSSLEngine();
        if ("false".equals(getClientAuth())) {
            engine.setNeedClientAuth(false);
            engine.setWantClientAuth(false);
        } else if ("true".equals(getClientAuth()) || "yes".equals(getClientAuth())){
            engine.setNeedClientAuth(true);
        } else if ("want".equals(getClientAuth())) {
            engine.setWantClientAuth(true);
        }
        engine.setUseClientMode(false);
        engine.setEnabledCipherSuites(enabledCiphers);
        engine.setEnabledProtocols(enabledProtocols);

        handler.onCreateSSLEngine(engine);
        return engine;
    }


    /**
     * Returns true if a worker thread is available for processing.
     * @return boolean
     */
    protected boolean isWorkerAvailable() {
        return true;
    }


    @Override
    public void processSocket(SocketWrapper<NioChannel> socketWrapper,
            SocketStatus socketStatus, boolean dispatch) {
        dispatchForEvent(socketWrapper.getSocket(), socketStatus, dispatch);
    }

    public boolean dispatchForEvent(NioChannel socket, SocketStatus status, boolean dispatch) {
        if (dispatch && status == SocketStatus.OPEN_READ) {
            socket.getPoller().add(socket, OP_CALLBACK);
        } else {
            processSocket(socket,status,dispatch);
        }
        return true;
    }

    /**
     * SocketProcessor 是工作线程池中的工作方法
     */
    protected boolean processSocket(NioChannel socket, SocketStatus status, boolean dispatch) {
        try {
            KeyAttachment attachment = (KeyAttachment)socket.getAttachment(false);
            if (attachment == null) {
                return false;
            }
            attachment.setCometNotify(false); //will get reset upon next reg
            // 从 SocketProcessor 的缓存队列取出一个来处理 socket
            SocketProcessor sc = processorCache.pop();
            if ( sc == null ) sc = new SocketProcessor(socket,status);              // SocketProcessor 是工作线程池中的 Runnable
            else sc.reset(socket,status);                                           // 若是从池中拿出来的, 则 reset 一下
            Executor executor = getExecutor();
            // 若有事件发生的 socket交给 Worker处理
            if (dispatch && executor != null) { // 若配置了 ThreadPoolExecutor, 则让它来执行
                executor.execute(sc);
            } else {
                sc.run();           // 执行运行 run 方法
            }
        } catch (RejectedExecutionException ree) {
            log.warn(sm.getString("endpoint.executor.fail", socket), ree);
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
    protected Log getLog() {
        return log;
    }


    // --------------------------------------------------- Acceptor Inner Class
    /**
     * The background thread that listens for incoming TCP/IP connections and
     * hands them off to an appropriate processor.
     */
    // EndPoint 中的Acceptor 线程, 对来访的请求进行最初的处理之用
    // 后台线程, 用于监听 TCP/IP 连接以及将它们分发给相应的调度器处理
    protected class Acceptor extends AbstractEndpoint.Acceptor {

        @Override
        public void run() {

            int errorDelay = 0;

            // Loop until we receive a shutdown command
            // 循环遍历直到接收到关闭的命令
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
                    countUpOrAwaitConnection();

                    SocketChannel socket = null;
                    try {
                        // Accept the next incoming connection from the server
                        // socket
                        socket = serverSock.accept();   // 接收请求, 这里用的是阻塞的模式
                    } catch (IOException ioe) {
                        //we didn't get a socket
                        countDownConnection();
                        // Introduce delay if necessary
                        errorDelay = handleExceptionWithDelay(errorDelay);
                        // re-throw
                        throw ioe;
                    }
                    // Successful accept, reset the error delay
                    errorDelay = 0;

                    // 注意这个 setSocketOptions 方法
                    // 它将把上面接收到的 socket 添加到 轮询器 Pooler 中
                    // setSocketOptions() will add channel to the poller
                    // if successful
                    if (running && !paused) {
                        if (!setSocketOptions(socket)) {    // 将 SocketChannel 交给 pollor 处理
                            countDownConnection();
                            closeSocket(socket);
                        }
                    } else {
                        countDownConnection();
                        closeSocket(socket);
                    }
                } catch (SocketTimeoutException sx) {
                    // Ignore: Normal condition
                } catch (IOException x) {
                    if (running) {
                        log.error(sm.getString("endpoint.accept.fail"), x);
                    }
                } catch (OutOfMemoryError oom) {
                    try {
                        oomParachuteData = null;
                        releaseCaches();
                        log.error("", oom);
                    }catch ( Throwable oomt ) {
                        try {
                            try {
                                System.err.println(oomParachuteMsg);
                                oomt.printStackTrace();
                            }catch (Throwable letsHopeWeDontGetHere){
                                ExceptionUtils.handleThrowable(letsHopeWeDontGetHere);
                            }
                        }catch (Throwable letsHopeWeDontGetHere){
                            ExceptionUtils.handleThrowable(letsHopeWeDontGetHere);
                        }
                    }
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    log.error(sm.getString("endpoint.accept.fail"), t);
                }
            }
            state = AcceptorState.ENDED;
        }
    }


    private void closeSocket(SocketChannel socket) {
        try {
            socket.socket().close();
        } catch (IOException ioe)  {
            if (log.isDebugEnabled()) {
                log.debug("", ioe);
            }
        }
        try {
            socket.close();
        } catch (IOException ioe) {
            if (log.isDebugEnabled()) {
                log.debug("", ioe);
            }
        }
    }


    // ----------------------------------------------------- Poller Inner Classes

    /**
     *
     * PollerEvent, cacheable object for poller events to avoid GC
     * PollerEvent 是 poller线程池处理的任务单元, 这个类也是一个 Runnable
     * PollerEvent 不单单还有前面包装的 NioChannel, 还持有 NioEndPoint.KeyAttachment 类的一个引用
     * KeyAttachment 类的作用主要是 对 Connector 中的一些 socket 属性进行解析, 然后设置到对应的 socketChannel 通道中
     * 因为 Tomcat 作为前端的服务器, 网络请求很多, 所以对于一个 Poller 线程池, 上述的 从Acceptor 过来的 PollerEvent 时间会很多, 因此这里采用一个 队列 SynchronizeQueue events
     */
    public static class PollerEvent implements Runnable {

        private NioChannel socket;                              // 每个 PollerEvent 都会保存 NioChannel 的引用
        private int interestOps;
        private KeyAttachment key;

        public PollerEvent(NioChannel ch, KeyAttachment k, int intOps) {
            reset(ch, k, intOps);
        }

        public void reset(NioChannel ch, KeyAttachment k, int intOps) {
            socket = ch;
            interestOps = intOps;
            key = k;
        }

        public void reset() {
            reset(null, null, 0);
        }

        @Override
        public void run() {
            if ( interestOps == OP_REGISTER ) {                             // socket 第一次注册到 selector 中, 完成socket对 READ的注册
                try {
                    socket.getIOChannel().register(socket.getPoller().getSelector(), SelectionKey.OP_READ, key);
                } catch (Exception x) {
                    log.error("", x);
                }
            } else { // 这里应该是对 comet 进行支持,暂时先不看
                final SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());
                try {
                    boolean cancel = false;
                    if (key != null) {
                        final KeyAttachment att = (KeyAttachment) key.attachment();
                        if ( att!=null ) {
                            //handle callback flag
                            if ((interestOps & OP_CALLBACK) == OP_CALLBACK ) {
                                att.setCometNotify(true);
                            } else {
                                att.setCometNotify(false);
                            }
                            interestOps = (interestOps & (~OP_CALLBACK));//remove the callback flag
                            // 刷新事件的最后访问时间, 防止事件超时
                            att.access();//to prevent timeout
                            //we are registering the key to start with, reset the fairness counter.
                            int ops = key.interestOps() | interestOps;
                            att.interestOps(ops);
                            if (att.getCometNotify()) key.interestOps(0);
                            else key.interestOps(ops);
                        } else {
                            cancel = true;
                        }
                    } else {
                        cancel = true;
                    }
                    if ( cancel ) socket.getPoller().cancelledKey(key,SocketStatus.ERROR);
                }catch (CancelledKeyException ckx) {
                    try {
                        socket.getPoller().cancelledKey(key,SocketStatus.DISCONNECT);
                    }catch (Exception ignore) {}
                }
            }//end if
        }//run

        @Override
        public String toString() {
            return super.toString()+"[intOps="+this.interestOps+"]";
        }
    }

    /**
     * Poller class.
     */
    // 参考资料 https://mp.weixin.qq.com/s?__biz=MzA4MTc3Nzk4NQ==&mid=402459056&idx=1&sn=909921555a7a4120a08b874ba1b40d61&mpshare=1&scene=23&srcid=0612CX2rqLOzsF4m7r1o8y41#rd
    // Poller 主要是从 SynchronizedQueue 里面 poll 出PollerEvent事件, 并进行相应的处理
    public class Poller implements Runnable {

        // 这就是 NIO 中用到的选择器, 可以看出每一个 Poller 都会关联一个 Selector
        private Selector selector;
        // 待处理的事件队列
        private final SynchronizedQueue<PollerEvent> events =
                new SynchronizedQueue<>();

        private volatile boolean close = false;
        private long nextExpiration = 0;//optimize expiration handling

        // 唤醒多路复用的条件阀值
        private AtomicLong wakeupCounter = new AtomicLong(0);

        private volatile int keyCount = 0;

        public Poller() throws IOException {
            // 对 Selector 的同步访问, 通过调用 Selector.open() 方法创建一个 Selector
            synchronized (Selector.class) {
                // Selector.open() isn't thread safe
                // http://bugs.sun.com/view_bug.do?bug_id=6427854
                // Affects 1.6.0_29, fixed in 1.7.0_01
                this.selector = Selector.open();
            }
        }

        public int getKeyCount() { return keyCount; }

        public Selector getSelector() { return selector;}

        /**
         * Destroy the poller.
         */
        protected void destroy() {
            // Wait for polltime before doing anything, so that the poller threads
            // exit, otherwise parallel closure of sockets which are still
            // in the poller can cause problems
            close = true;
            selector.wakeup();
        }

        // 通过 daaEvent 方法事件添加到 Poller 的事件队列中
        private void addEvent(PollerEvent event) {
            // 把事件加入到队列里面
            events.offer(event);
            // 如果当前事件队列中没有事件, 则唤醒阻塞状态的 selector
            if ( wakeupCounter.incrementAndGet() == 0 ) selector.wakeup();
        }

        /**
         * Add specified socket and associated pool to the poller. The socket will
         * be added to a temporary array, and polled first after a maximum amount
         * of time equal to pollTime (in most cases, latency will be much lower,
         * however).
         *
         * @param socket to add to the poller
         */
        public void add(final NioChannel socket) {
            add(socket,SelectionKey.OP_READ);
        }

        public void add(final NioChannel socket, final int interestOps) {
            PollerEvent r = eventCache.pop();
            if ( r==null) r = new PollerEvent(socket,null,interestOps);
            else r.reset(socket,null,interestOps);
            if ( (interestOps&OP_CALLBACK) == OP_CALLBACK ) {
                nextExpiration = 0; //force the check for faster callback
            }
            addEvent(r);
            if (close) {
                processSocket(socket, SocketStatus.STOP, false);
            }
        }

        /**
         * Processes events in the event queue of the Poller.
         *
         * @return <code>true</code> if some events were processed,
         *   <code>false</code> if queue was empty
         */
        // 处理事件队列中的所有事件, 如果事件队列是空的则返回 false
        public boolean events() {
            boolean result = false;

            PollerEvent pe = null;
            // 将 Poller 的事件队列中的事件逐个取出并执行相应的事件线程
            while ( (pe = events.poll()) != null ) {
                result = true;
                try {
                    // 执行事件处理逻辑
                    // 这里将事件设计成线程是将具体的事件处理逻辑和事件框架分开
                    pe.run();
                    pe.reset();
                    if (running && !paused) {
                        // 事件处理完之后, 将事件对象返回 NioEndPoint 的事件对象缓存中
                        eventCache.push(pe);
                    }
                } catch ( Throwable x ) {
                    log.error("",x);
                }
            }

            return result;
        }

        /**
         * Registers a newly created socket with the poller.
         *
         * @param socket    The newly created socket
         */
        // 将 socket 包装成统一的事件对象 PollerEvent, 加入到带处理事件队列中
        public void register(final NioChannel socket) {
            // 设置 socket 的 Poller 引用, 便于后续处理
            socket.setPoller(this);                                             // 每个 socket 绑定一个 Poller
            // 从 NioEndPoint 的 keyCache 缓存队列中取出一个 KeyAttachment, KeyAttachment 是对 NioChannel 信息的包装, 同样是非GC的
            KeyAttachment key = keyCache.pop();
            // KeyAttachment 实际上是 NioChannel包装类
            final KeyAttachment ka = key!=null?key:new KeyAttachment(socket);
            // 重置 KeyAttachment对象中的 Poller, NioChannel等成员变量的引用
            ka.reset(this,socket,getSocketProperties().getSoTimeout());
            ka.setKeepAliveLeft(NioEndpoint.this.getMaxKeepAliveRequests());    // 一个 Socket 在KeepAlive的周期内, 默认还能处理的请求数
            ka.setSecure(isSSLEnabled());

            // 从 Poller的事件对象缓存中取出一个 PollerEvent, 并用 socket 初始化事件
            // PollerEvent 的初始化, 非 GC
            PollerEvent r = eventCache.pop();
            // 设置读操作作为感兴趣的操作
            // 注册 Read 事件
            ka.interestOps(SelectionKey.OP_READ);//this is what OP_REGISTER turns into.
            if ( r==null) r = new PollerEvent(socket,ka,OP_REGISTER);       // 包装 REGISTER 事件, 放入 events 里面
            else r.reset(socket,ka,OP_REGISTER);

            // 加入到 Poller 对象的事件队列里面
            addEvent(r);                                                       // 把事件加到 Poller
        }

        public void cancelledKey(SelectionKey key, SocketStatus status) {
            try {
                if ( key == null ) return;//nothing to do
                KeyAttachment ka = (KeyAttachment) key.attachment();
                if (ka != null && ka.isComet() && status != null) {
                    ka.setComet(false);//to avoid a loop
                    if (status == SocketStatus.TIMEOUT ) {
                        if (processSocket(ka.getChannel(), status, true)) {
                            return; // don't close on comet timeout
                        }
                    } else {
                        // Don't dispatch if the lines below are canceling the key
                        processSocket(ka.getChannel(), status, false);
                    }
                }
                key.attach(null);
                if (ka!=null) handler.release(ka);
                else handler.release((SocketChannel)key.channel());
                if (key.isValid()) key.cancel();
                if (key.channel().isOpen()) {
                    try {
                        key.channel().close();
                    } catch (Exception e) {
                        if (log.isDebugEnabled()) {
                            log.debug(sm.getString(
                                    "endpoint.debug.channelCloseFail"), e);
                        }
                    }
                }
                try {
                    if (ka!=null) {
                        ka.getSocket().close(true);
                    }
                } catch (Exception e){
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString(
                                "endpoint.debug.socketCloseFail"), e);
                    }
                }
                try {
                    if (ka != null && ka.getSendfileData() != null
                            && ka.getSendfileData().fchannel != null
                            && ka.getSendfileData().fchannel.isOpen()) {
                        ka.getSendfileData().fchannel.close();
                    }
                } catch (Exception ignore) {
                }
                if (ka!=null) {
                    ka.reset();
                    countDownConnection();
                }
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                if (log.isDebugEnabled()) log.error("",e);
            }
        }

        /**
         * The background thread that listens for incoming TCP/IP connections and
         * hands them off to an appropriate processor.
         */
        // Poller 是一个线程, 该线程同 Acceptor一样会监听 TCP/IP连接并将它们交给合适的处理器
        @Override
        public void run() {
            // Loop until destroy() is called
            while (true) {
                try {
                    // Loop if endpoint is paused
                    while (paused && (!close) ) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            // Ignore
                        }
                    }

                    boolean hasEvents = false;

                    // Time to terminate?
                    if (close) {
                        events();
                        timeout(0, false);
                        try {
                            selector.close();
                        } catch (IOException ioe) {
                            log.error(sm.getString(
                                    "endpoint.nio.selectorCloseFail"), ioe);
                        }
                        break;
                    } else {
                        // 执行事件队列中的事件线程
                        hasEvents = events();
                    }
                    try {
                        if ( !close ) {
                            if (wakeupCounter.getAndSet(-1) > 0) {
                                // 把 wakeCounter设置成 -1, 这是与 addEvent里面的代码呼应, 这样会唤醒 selector
                                //if we are here, means we have other stuff to do
                                //do a non blocking select  // 立即返回 I/O 就绪的那些通道事件
                                // 以阻塞的方式查看 selector 里面是否有注册感兴趣的事件发生
                                keyCount = selector.selectNow();
                            } else {    // 阻塞 selectorTimeout 来获取 事件
                                // 查看 selector 是否有事件发生, 超过指定事件则立即返回
                                keyCount = selector.select(selectorTimeout);
                            }
                            wakeupCounter.set(0);
                        }
                        if (close) {
                            // 执行事件队列中的事件线程
                            events();
                            timeout(0, false);
                            try {
                                selector.close();
                            } catch (IOException ioe) {
                                log.error(sm.getString(
                                        "endpoint.nio.selectorCloseFail"), ioe);
                            }
                            break;
                        }
                    } catch ( NullPointerException x ) {
                        //sun bug 5076772 on windows JDK 1.5
                        if ( log.isDebugEnabled() ) log.debug("Possibly encountered sun bug 5076772 on windows JDK 1.5",x);
                        if ( wakeupCounter == null || selector == null ) throw x;
                        continue;
                    } catch ( CancelledKeyException x ) {
                        //sun bug 5076772 on windows JDK 1.5
                        if ( log.isDebugEnabled() ) log.debug("Possibly encountered sun bug 5076772 on windows JDK 1.5",x);
                        if ( wakeupCounter == null || selector == null ) throw x;
                        continue;
                    } catch (Throwable x) {
                        ExceptionUtils.handleThrowable(x);
                        log.error("",x);
                        continue;
                    }
                    //either we timed out or we woke up, process events first
                    if ( keyCount == 0 ) hasEvents = (hasEvents | events());

                    Iterator<SelectionKey> iterator =
                        keyCount > 0 ? selector.selectedKeys().iterator() : null;

                    // Walk through the collection of ready keys and dispatch
                    // any active event.
                    // 根据向 selector 中注册的 key 遍历 channel 中已经就绪的 keys, 并处理这些 key
                    /**
                     * 将  SocketChannel 遍历出来的事件 SelectionKey 和 KeyAttachment 一起交给工作线程继续处理
                     */
                    while (iterator != null && iterator.hasNext()) {                            // 遍历 SelectionKey 事件
                        SelectionKey sk = iterator.next();
                        // 这里的 KeyAttachment 是在 #register() 方法中注册的
                        // 而 KeyAttachment对象是对 socket的包装
                        KeyAttachment attachment = (KeyAttachment)sk.attachment();
                        // Attachment may be null if another thread has called
                        // cancelledKey()
                        if (attachment == null) {
                            iterator.remove();
                        } else {
                            // 更新通道最近一次发生事件的事件
                            // 防止因超时没有事件发生而被剔除 selector
                            attachment.access();
                            iterator.remove();
                            // 这里的 processKey 是处理通道的具体逻辑
                            processKey(sk, attachment);                                             // 交由工作线程继续处理
                        }
                    }//while

                    //process timeouts
                    // 多路复用器每执行一遍完整的轮询便查看所有通道是否超时
                    // 对超时的通道将会被剔除多路复用器
                    timeout(keyCount,hasEvents);
                    if ( oomParachute > 0 && oomParachuteData == null ) checkParachute();
                } catch (OutOfMemoryError oom) {
                    try {
                        oomParachuteData = null;
                        releaseCaches();
                        log.error("", oom);
                    }catch ( Throwable oomt ) {
                        try {
                            System.err.println(oomParachuteMsg);
                            oomt.printStackTrace();
                        }catch (Throwable letsHopeWeDontGetHere){
                            ExceptionUtils.handleThrowable(letsHopeWeDontGetHere);
                        }
                    }
                }
            }//while

            stopLatch.countDown();
        }

        // 处理 selector 检测到的通道事件
        protected boolean processKey(SelectionKey sk, KeyAttachment attachment) {
            boolean result = true;
            try {
                if ( close ) {
                    cancelledKey(sk, SocketStatus.STOP);
                } else if ( sk.isValid() && attachment != null ) {
                    // 确保通道不会因为超时而被剔除
                    attachment.access();//make sure we don't time out valid sockets
                    NioChannel channel = attachment.getChannel();

                    // 处理通道发生的读写事件
                    if (sk.isReadable() || sk.isWritable() ) {
                        if ( attachment.getSendfileData() != null ) {
                            processSendfile(sk,attachment, false);
                        } else {
                            if ( isWorkerAvailable() ) {
                                // 在通道上注销对已经发生的事件的关注
                                unreg(sk, attachment, sk.readyOps());
                                boolean closeSocket = false;
                                // Read goes before write
                                if (sk.isReadable()) {
                                    // 具体的通道处理逻辑
                                    if (!processSocket(channel, SocketStatus.OPEN_READ, true)) {
                                        closeSocket = true;
                                    }
                                }
                                if (!closeSocket && sk.isWritable()) {
                                    if (!processSocket(channel, SocketStatus.OPEN_WRITE, true)) {
                                        closeSocket = true;
                                    }
                                }
                                if (closeSocket) {
                                    // 解除无效的通道
                                    cancelledKey(sk,SocketStatus.DISCONNECT);
                                }
                            } else {
                                result = false;
                            }
                        }
                    }
                } else {
                    //invalid key
                    cancelledKey(sk, SocketStatus.ERROR);
                }
            } catch ( CancelledKeyException ckx ) {
                cancelledKey(sk, SocketStatus.ERROR);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error("",t);
            }
            return result;
        }

        public boolean processSendfile(SelectionKey sk, KeyAttachment attachment, boolean event) {
            NioChannel sc = null;
            try {
                unreg(sk, attachment, sk.readyOps());
                SendfileData sd = attachment.getSendfileData();

                if (log.isTraceEnabled()) {
                    log.trace("Processing send file for: " + sd.fileName);
                }

                //setup the file channel
                if ( sd.fchannel == null ) {
                    File f = new File(sd.fileName);
                    if ( !f.exists() ) {
                        cancelledKey(sk,SocketStatus.ERROR);
                        return false;
                    }
                    @SuppressWarnings("resource") // Closed when channel is closed
                    FileInputStream fis = new FileInputStream(f);
                    sd.fchannel = fis.getChannel();
                }

                //configure output channel
                sc = attachment.getChannel();
                sc.setSendFile(true);
                //ssl channel is slightly different
                WritableByteChannel wc = ((sc instanceof SecureNioChannel)?sc:sc.getIOChannel());

                //we still have data in the buffer
                if (sc.getOutboundRemaining()>0) {
                    if (sc.flushOutbound()) {
                        attachment.access();
                    }
                } else {
                    long written = sd.fchannel.transferTo(sd.pos,sd.length,wc);
                    if ( written > 0 ) {
                        sd.pos += written;
                        sd.length -= written;
                        attachment.access();
                    } else {
                        // Unusual not to be able to transfer any bytes
                        // Check the length was set correctly
                        if (sd.fchannel.size() <= sd.pos) {
                            throw new IOException("Sendfile configured to " +
                                    "send more data than was available");
                        }
                    }
                }
                if ( sd.length <= 0 && sc.getOutboundRemaining()<=0) {
                    if (log.isDebugEnabled()) {
                        log.debug("Send file complete for: "+sd.fileName);
                    }
                    attachment.setSendfileData(null);
                    try {
                        sd.fchannel.close();
                    } catch (Exception ignore) {
                    }
                    if ( sd.keepAlive ) {
                            if (log.isDebugEnabled()) {
                                log.debug("Connection is keep alive, registering back for OP_READ");
                            }
                            if (event) {
                                this.add(attachment.getChannel(),SelectionKey.OP_READ);
                            } else {
                                reg(sk,attachment,SelectionKey.OP_READ);
                            }
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug("Send file connection is being closed");
                        }
                        cancelledKey(sk,SocketStatus.STOP);
                        return false;
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("OP_WRITE for sendfile: " + sd.fileName);
                    }
                    if (event) {
                        add(attachment.getChannel(),SelectionKey.OP_WRITE);
                    } else {
                        reg(sk,attachment,SelectionKey.OP_WRITE);
                    }
                }
            }catch ( IOException x ) {
                if ( log.isDebugEnabled() ) log.debug("Unable to complete sendfile request:", x);
                cancelledKey(sk,SocketStatus.ERROR);
                return false;
            }catch ( Throwable t ) {
                log.error("",t);
                cancelledKey(sk, SocketStatus.ERROR);
                return false;
            }finally {
                if (sc!=null) sc.setSendFile(false);
            }
            return true;
        }

        // 这个 unreg() 很巧妙, 防止了通道对同一个事件不断 select 的问题
        protected void unreg(SelectionKey sk, KeyAttachment attachment, int readyOps) {
            //this is a must, so that we don't have multiple threads messing with the socket
            reg(sk,attachment,sk.interestOps()& (~readyOps));
        }

        // 向 NioChannel 注册感兴趣的事件, 具体的代码看下面的 PollerEvent类的说明
        protected void reg(SelectionKey sk, KeyAttachment attachment, int intops) {
            sk.interestOps(intops);
            attachment.interestOps(intops);
        }

        protected void timeout(int keyCount, boolean hasEvents) {
            long now = System.currentTimeMillis();
            // This method is called on every loop of the Poller. Don't process
            // timeouts on every loop of the Poller since that would create too
            // much load and timeouts can afford to wait a few seconds.
            // However, do process timeouts if any of the following are true:
            // - the selector simply timed out (suggests there isn't much load)
            // - the nextExpiration time has passed
            // - the server socket is being closed
            if (nextExpiration > 0 && (keyCount > 0 || hasEvents) && (now < nextExpiration) && !close) {
                return;
            }
            //timeout
            Set<SelectionKey> keys = selector.keys();
            int keycount = 0;
            for (Iterator<SelectionKey> iter = keys.iterator(); iter.hasNext();) {
                SelectionKey key = iter.next();
                keycount++;
                try {
                    KeyAttachment ka = (KeyAttachment) key.attachment();
                    if ( ka == null ) {
                        cancelledKey(key, SocketStatus.ERROR); //we don't support any keys without attachments
                    } else if ( ka.getError() ) {
                        cancelledKey(key, SocketStatus.ERROR);//TODO this is not yet being used
                    } else if (ka.getCometNotify() ) {
                        ka.setCometNotify(false);
                        int ops = ka.interestOps() & ~OP_CALLBACK;
                        reg(key,ka,0);//avoid multiple calls, this gets re-registered after invocation
                        ka.interestOps(ops);
                        if (!processSocket(ka.getChannel(), SocketStatus.OPEN_READ, true)) processSocket(ka.getChannel(), SocketStatus.DISCONNECT, true);
                    } else if ((ka.interestOps()&SelectionKey.OP_READ) == SelectionKey.OP_READ ||
                              (ka.interestOps()&SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
                        //only timeout sockets that we are waiting for a read from
                        long delta = now - ka.getLastAccess();
                        long timeout = ka.getTimeout();
                        boolean isTimedout = timeout > 0 && delta > timeout;
                        if ( close ) {
                            key.interestOps(0);
                            ka.interestOps(0); //avoid duplicate stop calls
                            processKey(key,ka);
                        } else if (isTimedout) {
                            key.interestOps(0);
                            ka.interestOps(0); //avoid duplicate timeout calls
                            cancelledKey(key, SocketStatus.TIMEOUT);
                        }
                    } else if (ka.isAsync() || ka.isComet()) {
                        if (close) {
                            key.interestOps(0);
                            ka.interestOps(0); //avoid duplicate stop calls
                            processKey(key,ka);
                        } else if (!ka.isAsync() || ka.getTimeout() > 0) {
                            // Async requests with a timeout of 0 or less never timeout
                            long delta = now - ka.getLastAccess();
                            long timeout = (ka.getTimeout()==-1)?((long) socketProperties.getSoTimeout()):(ka.getTimeout());
                            boolean isTimedout = delta > timeout;
                            if (isTimedout) {
                                // Prevent subsequent timeouts if the timeout event takes a while to process
                                ka.access(Long.MAX_VALUE);
                                processSocket(ka.getChannel(), SocketStatus.TIMEOUT, true);
                            }
                        }
                    }//end if
                }catch ( CancelledKeyException ckx ) {
                    cancelledKey(key, SocketStatus.ERROR);
                }
            }//for
            long prevExp = nextExpiration; //for logging purposes only
            nextExpiration = System.currentTimeMillis() +
                    socketProperties.getTimeoutInterval();
            if (log.isTraceEnabled()) {
                log.trace("timeout completed: keys processed=" + keycount +
                        "; now=" + now + "; nextExpiration=" + prevExp +
                        "; keyCount=" + keyCount + "; hasEvents=" + hasEvents +
                        "; eval=" + ((now < prevExp) && (keyCount>0 || hasEvents) && (!close) ));
            }

        }
    }

// ----------------------------------------------------- Key Attachment Class
    public static class KeyAttachment extends SocketWrapper<NioChannel> {

        public KeyAttachment(NioChannel channel) {
            super(channel);
        }

        public void reset(Poller poller, NioChannel channel, long soTimeout) {
            super.reset(channel, soTimeout);

            cometNotify = false;
            interestOps = 0;
            this.poller = poller;
            sendfileData = null;
            if (readLatch != null) {
                try {
                    for (int i = 0; i < (int) readLatch.getCount(); i++) {
                        readLatch.countDown();
                    }
                } catch (Exception ignore) {
                }
            }
            readLatch = null;
            sendfileData = null;
            if (writeLatch != null) {
                try {
                    for (int i = 0; i < (int) writeLatch.getCount(); i++) {
                        writeLatch.countDown();
                    }
                } catch (Exception ignore) {
                }
            }
            writeLatch = null;
            setWriteTimeout(soTimeout);
        }

        public void reset() {
            reset(null,null,-1);
        }

        public Poller getPoller() { return poller;}
        public void setPoller(Poller poller){this.poller = poller;}
        public void setCometNotify(boolean notify) { this.cometNotify = notify; }
        public boolean getCometNotify() { return cometNotify; }
        public NioChannel getChannel() { return getSocket();}
        public int interestOps() { return interestOps;}
        public int interestOps(int ops) { this.interestOps  = ops; return ops; }
        public CountDownLatch getReadLatch() { return readLatch; }
        public CountDownLatch getWriteLatch() { return writeLatch; }
        protected CountDownLatch resetLatch(CountDownLatch latch) {
            if ( latch==null || latch.getCount() == 0 ) return null;
            else throw new IllegalStateException("Latch must be at count 0");
        }
        public void resetReadLatch() { readLatch = resetLatch(readLatch); }
        public void resetWriteLatch() { writeLatch = resetLatch(writeLatch); }

        protected CountDownLatch startLatch(CountDownLatch latch, int cnt) {
            if ( latch == null || latch.getCount() == 0 ) {
                return new CountDownLatch(cnt);
            }
            else throw new IllegalStateException("Latch must be at count 0 or null.");
        }
        public void startReadLatch(int cnt) { readLatch = startLatch(readLatch,cnt);}
        public void startWriteLatch(int cnt) { writeLatch = startLatch(writeLatch,cnt);}

        protected void awaitLatch(CountDownLatch latch, long timeout, TimeUnit unit) throws InterruptedException {
            if ( latch == null ) throw new IllegalStateException("Latch cannot be null");
            // Note: While the return value is ignored if the latch does time
            //       out, logic further up the call stack will trigger a
            //       SocketTimeoutException
            latch.await(timeout,unit);
        }
        public void awaitReadLatch(long timeout, TimeUnit unit) throws InterruptedException { awaitLatch(readLatch,timeout,unit);}
        public void awaitWriteLatch(long timeout, TimeUnit unit) throws InterruptedException { awaitLatch(writeLatch,timeout,unit);}

        public void setSendfileData(SendfileData sf) { this.sendfileData = sf;}
        public SendfileData getSendfileData() { return this.sendfileData;}

        public void setWriteTimeout(long writeTimeout) {
            this.writeTimeout = writeTimeout;
        }
        public long getWriteTimeout() {return this.writeTimeout;}

        private Poller poller = null;
        private int interestOps = 0;
        private boolean cometNotify = false;
        private CountDownLatch readLatch = null;
        private CountDownLatch writeLatch = null;
        private SendfileData sendfileData = null;
        private long writeTimeout = -1;

    }

    // ------------------------------------------------ Application Buffer Handler
    public static class NioBufferHandler implements ApplicationBufferHandler {
        private ByteBuffer readbuf = null;
        private ByteBuffer writebuf = null;

        public NioBufferHandler(int readsize, int writesize, boolean direct) {
            if ( direct ) {
                readbuf = ByteBuffer.allocateDirect(readsize);
                writebuf = ByteBuffer.allocateDirect(writesize);
            }else {
                readbuf = ByteBuffer.allocate(readsize);
                writebuf = ByteBuffer.allocate(writesize);
            }
        }

        @Override
        public ByteBuffer expand(ByteBuffer buffer, int remaining) {return buffer;}
        @Override
        public ByteBuffer getReadBuffer() {return readbuf;}
        @Override
        public ByteBuffer getWriteBuffer() {return writebuf;}

    }

    // ------------------------------------------------ Handler Inner Interface


    /**
     * Bare bones interface used for socket processing. Per thread data is to be
     * stored in the ThreadWithAttributes extra folders, or alternately in
     * thread local fields.
     */
    public interface Handler extends AbstractEndpoint.Handler {
        public SocketState process(SocketWrapper<NioChannel> socket,
                SocketStatus status);
        public void release(SocketWrapper<NioChannel> socket);
        public void release(SocketChannel socket);
        public SSLImplementation getSslImplementation();
        public void onCreateSSLEngine(SSLEngine engine);
    }


    // ---------------------------------------------- SocketProcessor Inner Class
    /**
     * This class is the equivalent of the Worker, but will simply use in an
     * external Executor thread pool.
     */
    protected class SocketProcessor implements Runnable {

        private NioChannel socket = null;
        private SocketStatus status = null;

        public SocketProcessor(NioChannel socket, SocketStatus status) {
            reset(socket,status);
        }

        public void reset(NioChannel socket, SocketStatus status) {
            this.socket = socket;
            this.status = status;
        }

        @Override
        public void run() {
            // 拿到 Poller 线程轮询出来的 Key
            SelectionKey key = socket.getIOChannel().keyFor(
                    socket.getPoller().getSelector());
            KeyAttachment ka = null;

            if (key != null) {      // 拿到 Poller 线程轮询出来的 Key
                ka = (KeyAttachment)key.attachment();
            }

            // Upgraded connections need to allow multiple threads to access the
            // connection at the same time to enable blocking IO to be used when
            // NIO has been configured
            if (ka != null && ka.isUpgraded() &&
                    SocketStatus.OPEN_WRITE == status) {
                synchronized (ka.getWriteThreadLock()) {
                    doRun(key, ka);
                }
            } else {
                synchronized (socket) {
                    doRun(key, ka);
                }
            }
        }

        private void doRun(SelectionKey key, KeyAttachment ka) {
            boolean launch = false;
            try {
                int handshake = -1;

                // 如果有 ssl 配置那么先进行握手
                try {
                    if (key != null) {
                        // For STOP there is no point trying to handshake as the
                        // Poller has been stopped.
                        if (socket.isHandshakeComplete() ||                     // 如果 SecureioChannel已经是 SSL 建立完毕, 则直接 handshake = 0
                                status == SocketStatus.STOP) {
                            handshake = 0;
                        } else {
                            handshake = socket.handshake(                       // SSL 通过需要建立, 执行握手
                                    key.isReadable(), key.isWritable());
                            // The handshake process reads/writes from/to the
                            // socket. status may therefore be OPEN_WRITE once
                            // the handshake completes. However, the handshake
                            // happens when the socket is opened so the status
                            // must always be OPEN_READ after it completes. It
                            // is OK to always set this as it is only used if
                            // the handshake completes.
                            status = SocketStatus.OPEN_READ;
                        }
                    }
                } catch (IOException x) {
                    handshake = -1;
                    if (log.isDebugEnabled()) log.debug("Error during SSL handshake",x);
                } catch (CancelledKeyException ckx) {
                    handshake = -1;
                }
                if (handshake == 0) {                                             // SSL 握手成功
                    SocketState state = SocketState.OPEN;
                    // Process the request from this socket
                    if (status == null) {
                        state = handler.process(ka, SocketStatus.OPEN_READ);   // 这里是将工作移交给 Http11ConnectionHandler 来处理
                    } else {
                        state = handler.process(ka, status);
                    }
                    if (state == SocketState.CLOSED) {
                        // Close socket and pool
                        try {
                            if (ka!=null) ka.setComet(false);
                            socket.getPoller().cancelledKey(key, SocketStatus.ERROR); // NIO 中的 CLOSE 就是告诉 Poller 不需要再关注这个 Socket 了 然后socket 在 Poller 里面被关闭掉
                            if (running && !paused) {
                                nioChannels.push(socket);
                            }
                            socket = null;
                            if (running && !paused && ka != null) {
                                keyCache.push(ka);
                            }
                            ka = null;
                        } catch (Exception x) {
                            log.error("",x);
                        }
                    } else if (state == SocketState.LONG && ka != null && ka.isAsync() && ka.interestOps() > 0) {
                        //we are async, and we are interested in operations
                        ka.getPoller().add(socket, ka.interestOps()); // 如果是 KeepAlive 的话, 那么下一次, Poller 轮询, 这个 Socket 还需要参与
                    }
                } else if (handshake == -1 ) {                                  // 握手失败 (像 socket 突然关闭了)
                    if (key != null) {
                        socket.getPoller().cancelledKey(key, SocketStatus.DISCONNECT);
                    }
                    if (running && !paused) {
                        nioChannels.push(socket);
                    }
                    socket = null;
                    if (running && !paused && ka != null) {
                        keyCache.push(ka);
                    }
                    ka = null;
                } else {
                    ka.getPoller().add(socket,handshake); // 若是 keepalive 的话, 下一个 Poller 轮询, 这个 socket 还需要参与
                }
            } catch (CancelledKeyException cx) {
                socket.getPoller().cancelledKey(key, null);
            } catch (OutOfMemoryError oom) {
                try {
                    oomParachuteData = null;
                    log.error("", oom);
                    if (socket != null) {
                        socket.getPoller().cancelledKey(key,SocketStatus.ERROR);
                    }
                    releaseCaches();
                } catch (Throwable oomt) {
                    try {
                        System.err.println(oomParachuteMsg);
                        oomt.printStackTrace();
                    } catch (Throwable letsHopeWeDontGetHere){
                        ExceptionUtils.handleThrowable(letsHopeWeDontGetHere);
                    }
                }
            } catch (VirtualMachineError vme) {
                ExceptionUtils.handleThrowable(vme);
            } catch (Throwable t) {
                log.error("", t);
                if (socket != null) {
                    socket.getPoller().cancelledKey(key,SocketStatus.ERROR);
                }
            } finally {
                /**
                 * KeepAlive总结:
                 * 整体的 SocketProcessor 流程与 BIO 差不多, 只不过因为 NIO 通道中多了一个 Poller 线程,
                 * 所以针对 Socket 的直接操作, 变成对 socket 的事件key 的关注或者取消, 例如关闭连接, 就是告知 Poller 线程
                 * cancelledKey,
                 * 当 handler.process 返回的SocketState.keepalive 的时候, 可以看代码, Poller 线程 add socket也就是重新将 该socket 的读写事件作为 Poller 关注事项了
                 *
                 * 到这里, 我们其实可以总结出, NIO 模式下, 因为多了一个 POller线程, 它把 socket 感兴趣的事件注册上去, 当有感兴趣的 key 到时, 才会把 keepalive 模式下的 SocketProcessor 工作线程 run 起来, 这相当于
                 * 工作线程 SocketProcessor 并不是 一直阻塞着(BIO是阻塞的), 只有读写事件时, 工作线程才重新 run 起来, 这是就是为什么在 NIO 模式下, 当加上 KeepAlive(默认是 true) 会占用很少的线程的实质原因
                 *
                 */
                if (launch) {
                    try {
                        getExecutor().execute(new SocketProcessor(socket, SocketStatus.OPEN_READ));
                    } catch (NullPointerException npe) {
                        if (running) {
                            log.error(sm.getString("endpoint.launch.fail"),
                                    npe);
                        }
                    }
                }
                socket = null;
                status = null;
                //return to cache
                if (running && !paused) {
                    processorCache.push(this);  // 将 SocketProcessor 缓存起来, 但该工作线程会退出
                }
            }
        }
    }

    // ----------------------------------------------- SendfileData Inner Class
    /**
     * SendfileData class.
     */
    public static class SendfileData {
        // File
        public String fileName;
        public FileChannel fchannel;
        public long pos;
        public long length;
        // KeepAlive flag
        public boolean keepAlive;
    }
}

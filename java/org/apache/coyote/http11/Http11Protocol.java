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
package org.apache.coyote.http11;

import java.io.IOException;
import java.net.Socket;

import javax.servlet.http.HttpUpgradeHandler;

import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.Processor;
import org.apache.coyote.http11.upgrade.BioProcessor;
import org.apache.juli.logging.Log;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.JIoEndpoint;
import org.apache.tomcat.util.net.JIoEndpoint.Handler;
import org.apache.tomcat.util.net.SSLImplementation;
import org.apache.tomcat.util.net.SocketStatus;
import org.apache.tomcat.util.net.SocketWrapper;


/**
 * Abstract the protocol implementation, including threading, etc.
 * Processor is single threaded and specific to stream-based protocols,
 * will not fit Jk protocols like JNI.
 * Http11Protocol 对应的 Http11Processor 是 org.apache.coyote.http11.Http11Processor, 通过 Http11ConnectionHandler.createProcessor() 创建出来的
 * @author Remy Maucherat
 * @author Costin Manolache
 */
public class Http11Protocol extends AbstractHttp11JsseProtocol<Socket> {


    private static final org.apache.juli.logging.Log log
        = org.apache.juli.logging.LogFactory.getLog(Http11Protocol.class);

    @Override
    protected Log getLog() { return log; }


    @Override
    protected AbstractEndpoint.Handler getHandler() {
        return cHandler;
    }


    // ------------------------------------------------------------ Constructor

    /**
     * 每一个 ProtocolHandler 里面都会含有对应的 EndPoint(处理客户端的网络连接), Handler(处理EndPoint发来的 SocketWrapper, 主要是存储在 handler 里面的 Processor)
     */
    public Http11Protocol() {
        endpoint = new JIoEndpoint();                            // Endpoint 网路连接请求的终点(这里的 bio 模型)
        cHandler = new Http11ConnectionHandler(this);           // 新建处理 SocketWrapper 的Http11ConnectionHandler, 每次都是从对象池里面拿出 Processor, 或直接创建一个 Processor
        ((JIoEndpoint) endpoint).setHandler(cHandler);          // JioEndPoint 里面的 SocketProcessor 会调用 Http11ConnectionHandler 中的 handler 来进行处理请求
        setSoLinger(Constants.DEFAULT_CONNECTION_LINGER);     // soLinger 是在调用 socket.close() 时阻塞一会, 因为底层的数据 可能还没有发出去
        /** soTimeOut 这个参数是在 ServerSocket.accept() 时阻塞的时间, 若超过这个时间还没有客户端连接上来, 则直接报出 SocketTimeoutException 异常, 当 ServerSocket 还是存活着的
         * 参考地址 https://docs.oracle.com/javase/8/docs/api/java/net/ServerSocket.html#setSoTimeout-int-
         * 与之对应的是 客户端的 socket, 这时 soTimeOut 影响的是 inputStream.read() 的超时时间
         * 参考地址 https://docs.oracle.com/javase/8/docs/api/java/net/Socket.html#setSoTimeout-int-
         */
        setSoTimeout(Constants.DEFAULT_CONNECTION_TIMEOUT);
        /**
         * Nagle's 算法主要是减少小数据包在网络上的流动,当数据包小于 limit (usually MSS), 将会等待前面发送的数据包返回  ACK(这意味着在底层积累数据包, 等到数据包变大了再发送出去)
         * 而 TcpNoDelay 主要是禁止 Nagle 算法
         * 参考地址
         * https://stackoverflow.com/questions/3761276/when-should-i-use-tcp-nodelay-and-when-tcp-cork
         * http://ccr.sigcomm.org/archive/2001/jan01/ccr-200101-mogul.pdf
         */
        setTcpNoDelay(Constants.DEFAULT_TCP_NO_DELAY);
    }


    // ----------------------------------------------------------------- Fields

    private final Http11ConnectionHandler cHandler;


    // ------------------------------------------------ HTTP specific properties
    // ------------------------------------------ managed in the ProtocolHandler

    private int disableKeepAlivePercentage = 75;
    public int getDisableKeepAlivePercentage() {
        return disableKeepAlivePercentage;
    }
    public void setDisableKeepAlivePercentage(int disableKeepAlivePercentage) {
        if (disableKeepAlivePercentage < 0) {
            this.disableKeepAlivePercentage = 0;
        } else if (disableKeepAlivePercentage > 100) {
            this.disableKeepAlivePercentage = 100;
        } else {
            this.disableKeepAlivePercentage = disableKeepAlivePercentage;
        }
    }

    @Override
    public void start() throws Exception {
        super.start();
        if (npnHandler != null) {
            npnHandler.init(endpoint, 0, getAdapter());
        }
    }

    // ----------------------------------------------------- JMX related methods

    @Override
    protected String getNamePrefix() {
        return ("http-bio");
    }


    // -----------------------------------  Http11ConnectionHandler Inner Class

    protected static class Http11ConnectionHandler
            extends AbstractConnectionHandler<Socket, Http11Processor> implements Handler {

        protected Http11Protocol proto;

        Http11ConnectionHandler(Http11Protocol proto) {
            this.proto = proto;
        }

        @Override
        protected AbstractProtocol<Socket> getProtocol() {
            return proto;
        }

        @Override
        protected Log getLog() {
            return log;
        }

        @Override
        public SSLImplementation getSslImplementation() {
            return proto.sslImplementation;
        }

        @Override
        public SocketState process(SocketWrapper<Socket> socket,
                SocketStatus status) {
            if (proto.npnHandler != null) {
                SocketState ss = proto.npnHandler.process(socket, status);
                if (ss != SocketState.OPEN) {
                    return ss;
                }
            }
            return super.process(socket, status);
        }

        /**
         * Expected to be used by the handler once the processor is no longer
         * required.
         *
         * @param socket            Not used in BIO
         * @param processor
         * @param isSocketClosing   Not used in HTTP
         * @param addToPoller       Not used in BIO
         */
        @Override
        public void release(SocketWrapper<Socket> socket,
                Processor<Socket> processor, boolean isSocketClosing,
                boolean addToPoller) {
            processor.recycle(isSocketClosing);
            recycledProcessors.push(processor);
        }

        @Override
        protected void initSsl(SocketWrapper<Socket> socket,
                Processor<Socket> processor) {
            if (proto.isSSLEnabled() && (proto.sslImplementation != null)) {
                processor.setSslSupport(
                        proto.sslImplementation.getSSLSupport(
                                socket.getSocket()));
            } else {
                processor.setSslSupport(null);
            }

        }

        @Override
        protected void longPoll(SocketWrapper<Socket> socket,
                Processor<Socket> processor) {
            // NO-OP
        }

        @Override
        protected Http11Processor createProcessor() {   // 构建 Http11Processor
            Http11Processor processor = new Http11Processor(
                    proto.getMaxHttpHeaderSize(), (JIoEndpoint)proto.endpoint,        // http header 的最大尺寸
                    proto.getMaxTrailerSize(),proto.getMaxExtensionSize());            // 最大的 http trailer size
            processor.setAdapter(proto.getAdapter());
            processor.setMaxKeepAliveRequests(proto.getMaxKeepAliveRequests());         // 默认的 KeepAlive 情况下, 每个 Socket 处理的最多的 请求次数
            processor.setKeepAliveTimeout(proto.getKeepAliveTimeout());                 // 开启 KeepAlive 的 Timeout
            processor.setConnectionUploadTimeout(
                    proto.getConnectionUploadTimeout());                                // http 当遇到文件上传时的 默认超时时间 (300 * 1000)
            processor.setDisableUploadTimeout(proto.getDisableUploadTimeout());
            processor.setCompressionMinSize(proto.getCompressionMinSize());             // 当 http 请求的 body size超过这个值时, 通过 gzip 进行压缩
            processor.setCompression(proto.getCompression());                           // http 请求是否开启 compression 处理
            processor.setNoCompressionUserAgents(proto.getNoCompressionUserAgents());
            processor.setCompressableMimeTypes(proto.getCompressableMimeTypes());       // http body里面的内容是 "text/html,text/xml,text/plain" 才会进行 压缩处理
            processor.setRestrictedUserAgents(proto.getRestrictedUserAgents());
            processor.setSocketBuffer(proto.getSocketBuffer());                         // socket 的 buffer, 默认 9000
            processor.setMaxSavePostSize(proto.getMaxSavePostSize());                   // 最大的 Post 处理尺寸的大小 4 * 1000
            processor.setServer(proto.getServer());
            processor.setDisableKeepAlivePercentage(
                    proto.getDisableKeepAlivePercentage());                             // 这是一个阀值, 当工作线程池超过 disableKeepAlivePercentage() 默认时, 就会 disable 的功能, 在 AbstractHttp11Processor中的 process
            register(processor);                                                         // 将 Http11Processor 注册到 JMX 里面
            return processor;
        }

        @Override
        protected Processor<Socket> createUpgradeProcessor(
                SocketWrapper<Socket> socket,
                HttpUpgradeHandler httpUpgradeProcessor)
                throws IOException {
            return new BioProcessor(socket, httpUpgradeProcessor);
        }

        @Override
        public void beforeHandshake(SocketWrapper<Socket> socket) {
        }
    }
}

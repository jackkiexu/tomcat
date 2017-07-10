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
import java.nio.channels.SocketChannel;
import java.util.Iterator;

import javax.net.ssl.SSLEngine;
import javax.servlet.http.HttpUpgradeHandler;

import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.Processor;
import org.apache.coyote.http11.upgrade.NioProcessor;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.NioChannel;
import org.apache.tomcat.util.net.NioEndpoint;
import org.apache.tomcat.util.net.NioEndpoint.Handler;
import org.apache.tomcat.util.net.SSLImplementation;
import org.apache.tomcat.util.net.SecureNioChannel;
import org.apache.tomcat.util.net.SocketStatus;
import org.apache.tomcat.util.net.SocketWrapper;


/**
 * Abstract the protocol implementation, including threading, etc.
 * Processor is single threaded and specific to stream-based protocols,
 * will not fit Jk protocols like JNI.
 *
 * tomcat nio 线程模型
 * https://yq.aliyun.com/articles/39093
 * http://blog.csdn.net/yfkscu/article/details/38144029
 *
 *
 * Http11NioProtocol 对应的 处理器是 org.apache.coyote.http11.Http11NioProcessor, 通过 Http11ConnectionHandler.createProcessor 创建出来
 * @author Remy Maucherat
 * @author Costin Manolache
 */
public class Http11NioProtocol extends AbstractHttp11JsseProtocol<NioChannel> {

    private static final Log log = LogFactory.getLog(Http11NioProtocol.class);


    @Override
    protected Log getLog() { return log; }


    @Override
    protected AbstractEndpoint.Handler getHandler() {
        return cHandler;
    }

    /**
     * Http11nioProtocol 作为协议的实现者, 它持有两大组件:
     * 1. EndPoint: 默认就是 NioEndPoint, 这个类是 线程池 socket 的转接类, 将 NIO 通道中的 socket 组包, 交给 handler 来进行处理
     * 2. Handler, 也就是 Http11ConnectionHandler, 设置给 EndPoint, 这个 Http11ConnectionHandler 类主要的作用是将前面的组包 socket 包, 转换成内部的 Request 对象, 最后发给 Tomcat 的后端
     */
    public Http11NioProtocol() {
        endpoint=new NioEndpoint();
        cHandler = new Http11ConnectionHandler(this);
        ((NioEndpoint) endpoint).setHandler(cHandler);
        // socket 关闭时是否阻塞一段时间(因为底层的数据包可能还没有发送完)
        setSoLinger(Constants.DEFAULT_CONNECTION_LINGER);
        // 这里是 ServerSocket 的 soTimeout 设置, 在ServerSocket.accept()调用时, 在没有client进行连接时, 会阻塞, 而超过了 soTimeout 时间会出现 SocketTimeoutException , 但是 ServerSocket 还是有效的
        // ServerSocket 的参数设置 https://docs.oracle.com/javase/8/docs/api/java/net/ServerSocket.html#setSoTimeout-int-
        // 与之对应的是 Socket 的参数设置 https://docs.oracle.com/javase/8/docs/api/java/net/Socket.html#setSoTimeout-int-
        setSoTimeout(Constants.DEFAULT_CONNECTION_TIMEOUT);
        // 是否开启 Nagle's  算法 (Nagle 算法 将小数据包合并, 等达到一定大小时, 一并的进行发送, 从而减小数据包的头)
        setTcpNoDelay(Constants.DEFAULT_TCP_NO_DELAY);
    }


    public NioEndpoint getEndpoint() {
        return ((NioEndpoint)endpoint);
    }

    @Override
    public void start() throws Exception {
        super.start();
        if (npnHandler != null) {
            npnHandler.init(getEndpoint(), 0, getAdapter());
        }
    }

    // -------------------- Properties--------------------

    private final Http11ConnectionHandler cHandler;

    // -------------------- Pool setup --------------------

    public void setPollerThreadCount(int count) {
        ((NioEndpoint)endpoint).setPollerThreadCount(count);
    }

    public int getPollerThreadCount() {
        return ((NioEndpoint)endpoint).getPollerThreadCount();
    }

    public void setSelectorTimeout(long timeout) {
        ((NioEndpoint)endpoint).setSelectorTimeout(timeout);
    }

    public long getSelectorTimeout() {
        return ((NioEndpoint)endpoint).getSelectorTimeout();
    }

    public void setAcceptorThreadPriority(int threadPriority) {
        ((NioEndpoint)endpoint).setAcceptorThreadPriority(threadPriority);
    }

    public void setPollerThreadPriority(int threadPriority) {
        ((NioEndpoint)endpoint).setPollerThreadPriority(threadPriority);
    }

    public int getAcceptorThreadPriority() {
      return ((NioEndpoint)endpoint).getAcceptorThreadPriority();
    }

    public int getPollerThreadPriority() {
      return ((NioEndpoint)endpoint).getThreadPriority();
    }


    public boolean getUseSendfile() {
        return endpoint.getUseSendfile();
    }

    public void setUseSendfile(boolean useSendfile) {
        ((NioEndpoint)endpoint).setUseSendfile(useSendfile);
    }

    // -------------------- Tcp setup --------------------
    public void setOomParachute(int oomParachute) {
        ((NioEndpoint)endpoint).setOomParachute(oomParachute);
    }

    // ----------------------------------------------------- JMX related methods

    @Override
    protected String getNamePrefix() {
        return ("http-nio");
    }


    // --------------------  Connection handler --------------------

    protected static class Http11ConnectionHandler
            extends AbstractConnectionHandler<NioChannel,Http11NioProcessor>
            implements Handler {

        protected Http11NioProtocol proto;

        Http11ConnectionHandler(Http11NioProtocol proto) {
            this.proto = proto;
        }

        @Override
        protected AbstractProtocol<NioChannel> getProtocol() {
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

        /**
         * Expected to be used by the Poller to release resources on socket
         * close, errors etc.
         */
        @Override
        public void release(SocketChannel socket) {
            if (log.isDebugEnabled())
                log.debug("Iterating through our connections to release a socket channel:"+socket);
            boolean released = false;
            Iterator<java.util.Map.Entry<NioChannel, Processor<NioChannel>>> it = connections.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<NioChannel, Processor<NioChannel>> entry = it.next();
                if (entry.getKey().getIOChannel()==socket) {
                    it.remove();
                    Processor<NioChannel> result = entry.getValue();
                    result.recycle(true);
                    unregister(result);
                    released = true;
                    break;
                }
            }
            if (log.isDebugEnabled())
                log.debug("Done iterating through our connections to release a socket channel:"+socket +" released:"+released);
        }

        /**
         * Expected to be used by the Poller to release resources on socket
         * close, errors etc.
         */
        @Override
        public void release(SocketWrapper<NioChannel> socket) {
            Processor<NioChannel> processor =
                connections.remove(socket.getSocket());
            if (processor != null) {
                processor.recycle(true);
                recycledProcessors.push(processor);
            }
        }

        @Override
        public SocketState process(SocketWrapper<NioChannel> socket,
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
         * @param socket
         * @param processor
         * @param isSocketClosing   Not used in HTTP
         * @param addToPoller
         */
        @Override
        public void release(SocketWrapper<NioChannel> socket,
                Processor<NioChannel> processor, boolean isSocketClosing,
                boolean addToPoller) {
            processor.recycle(isSocketClosing);
            recycledProcessors.push(processor);
            if (addToPoller) {
                socket.getSocket().getPoller().add(socket.getSocket());
            }
        }


        @Override
        protected void initSsl(SocketWrapper<NioChannel> socket,
                Processor<NioChannel> processor) {
            if (proto.isSSLEnabled() &&
                    (proto.sslImplementation != null)
                    && (socket.getSocket() instanceof SecureNioChannel)) {
                SecureNioChannel ch = (SecureNioChannel)socket.getSocket();
                processor.setSslSupport(
                        proto.sslImplementation.getSSLSupport(
                                ch.getSslEngine().getSession()));
            } else {
                processor.setSslSupport(null);
            }

        }

        @Override
        protected void longPoll(SocketWrapper<NioChannel> socket,
                Processor<NioChannel> processor) {

            if (processor.isAsync()) {
                socket.setAsync(true);
            } else {
                // Either:
                //  - this is comet request
                //  - this is an upgraded connection
                //  - the request line/headers have not been completely
                //    read
                socket.getSocket().getPoller().add(socket.getSocket());
            }
        }

        // 这里创建的 Http11NioProcessor 是进行 HTTP1.1版本的 NIO 信息处理
        @Override
        public Http11NioProcessor createProcessor() {
            Http11NioProcessor processor = new Http11NioProcessor(
                    proto.getMaxHttpHeaderSize(), (NioEndpoint)proto.endpoint,
                    proto.getMaxTrailerSize(), proto.getMaxExtensionSize());
            processor.setAdapter(proto.getAdapter());
            processor.setMaxKeepAliveRequests(proto.getMaxKeepAliveRequests());
            processor.setKeepAliveTimeout(proto.getKeepAliveTimeout());
            processor.setConnectionUploadTimeout(
                    proto.getConnectionUploadTimeout());
            processor.setDisableUploadTimeout(proto.getDisableUploadTimeout());
            processor.setCompressionMinSize(proto.getCompressionMinSize());
            processor.setCompression(proto.getCompression());
            processor.setNoCompressionUserAgents(proto.getNoCompressionUserAgents());
            processor.setCompressableMimeTypes(proto.getCompressableMimeTypes());
            processor.setRestrictedUserAgents(proto.getRestrictedUserAgents());
            processor.setSocketBuffer(proto.getSocketBuffer());
            processor.setMaxSavePostSize(proto.getMaxSavePostSize());
            processor.setServer(proto.getServer());
            register(processor);
            return processor;
        }

        @Override
        protected Processor<NioChannel> createUpgradeProcessor(
                SocketWrapper<NioChannel> socket,
                HttpUpgradeHandler httpUpgradeProcessor)
                throws IOException {
            return new NioProcessor(socket, httpUpgradeProcessor,
                    ((Http11NioProtocol) getProtocol()).getEndpoint().getSelectorPool());
        }

        @Override
        public void onCreateSSLEngine(SSLEngine engine) {
            if (proto.npnHandler != null) {
                proto.npnHandler.onCreateEngine(engine);
            }
        }
    }
}

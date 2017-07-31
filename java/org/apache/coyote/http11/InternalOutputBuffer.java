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
import java.io.OutputStream;
import java.net.Socket;

import org.apache.coyote.OutputBuffer;
import org.apache.coyote.Response;
import org.apache.log4j.Logger;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.SocketWrapper;

/**
 * Output buffer.
 *
 * @author <a href="mailto:remm@apache.org">Remy Maucherat</a>
 */
public class InternalOutputBuffer extends AbstractOutputBuffer<Socket>
    implements ByteChunk.ByteOutputChannel {
    private Logger logger = Logger.getLogger(InternalOutputBuffer.class);
    // ----------------------------------------------------------- Constructors

    /**
     * Default constructor.
     */
    public InternalOutputBuffer(Response response, int headerBufferSize) {

        super(response, headerBufferSize);

        outputStreamOutputBuffer = new OutputStreamOutputBuffer();

        socketBuffer = new ByteChunk();
        socketBuffer.setByteOutputChannel(this);            // 设置这一步主要是在 flushBuffer 时进行回调的
    }

    /**
     * Underlying output stream. Note: protected to assist with unit testing
     */
    protected OutputStream outputStream;


    /**
     * Socket buffer.
     */
    private final ByteChunk socketBuffer;


    /**
     * Socket buffer (extra buffering to reduce number of packets sent).    useSocketBuffer 来决定要写到 Client 端的数据是否缓存到 Buffer  里面, 交由buffer进行 commit 出去
     */
    private boolean useSocketBuffer = false;


    /**
     * Set the socket buffer size.
     */
    @Override
    public void setSocketBuffer(int socketBufferSize) {

        if (socketBufferSize > 500) {
            useSocketBuffer = true;
            socketBuffer.allocate(socketBufferSize, socketBufferSize);
        } else {
            useSocketBuffer = false;
        }
    }


    // --------------------------------------------------------- Public Methods

    @Override
    public void init(SocketWrapper<Socket> socketWrapper,
            AbstractEndpoint<Socket> endpoint) throws IOException {

        outputStream = socketWrapper.getSocket().getOutputStream();
    }


    /**
     * Recycle the output buffer. This should be called when closing the
     * connection.
     */
    @Override
    public void recycle() {
        super.recycle();
        outputStream = null;
    }


    /**
     * End processing of current HTTP request.
     * Note: All bytes of the current request should have been already
     * consumed. This method only resets all the pointers so that we are ready
     * to parse the next HTTP request.
     */
    @Override
    public void nextRequest() {
        super.nextRequest();
        socketBuffer.recycle();
    }


    // ------------------------------------------------ HTTP/1.1 Output Methods

    /**
     * Send an acknowledgment.
     */
    @Override
    public void sendAck()
        throws IOException {

        if (!committed)
            outputStream.write(Constants.ACK_BYTES);

    }


    // ------------------------------------------------------ Protected Methods


    /**
     * Commit the response.
     *
     * @throws IOException an underlying I/O error occurred
     */
    @Override
    protected void commit()
        throws IOException {

        // The response is now committed
        committed = true;
        response.setCommitted(true);

        if (pos > 0) {                  // pos > 0 说明要写入数据
            // Sending the response header buffer
            if (useSocketBuffer) {  logger.info(new String(headerBuffer, "utf-8"));
                socketBuffer.append(headerBuffer, 0, pos);          // 这里的 socketBuffer 是  ByteChunk, 追加的是 Http Header 里面的信息
            } else {
                outputStream.write(headerBuffer, 0, pos);
            }
        }

    }


    /**
     * Callback to write data from the buffer.
     */
    @Override
    public void realWriteBytes(byte cbuf[], int off, int len)
        throws IOException {
        if (len > 0) { logger.info(new String(cbuf, "utf-8"));
            outputStream.write(cbuf, off, len);                   //   这里的 outputStream 其实就是 通过 socket 获取得到的
        }
    }


    //-------------------------------------------------- Non-blocking IO methods

    @Override
    protected boolean hasMoreDataToFlush() {
        // The blocking connector always blocks until the previous write is
        // complete so there is never data remaining to flush. This effectively
        // allows non-blocking code to work with the blocking connector but -
        // obviously - every write will always block.
        return false;
    }


    @Override
    protected void registerWriteInterest() {
        // NO-OP for non-blocking connector
    }


    @Override
    protected boolean flushBuffer(boolean block) throws IOException {
        // Blocking connector so ignore block parameter as this will always use
        // blocking IO.
        if (useSocketBuffer) {
            socketBuffer.flushBuffer();         // 这里是 socketBuffer 是 ByteChunk
        }
        // Always blocks so never any data left over.
        return false;
    }


    // ----------------------------------- OutputStreamOutputBuffer Inner Class

    /**
     * This class is an output buffer which will write data to an output
     * stream.
     */
    protected class OutputStreamOutputBuffer                      // 这个类主要是在Filter 中进行调用
        implements OutputBuffer {


        /**
         * Write chunk.
         */
        @Override
        public int doWrite(ByteChunk chunk, Response res)                  // 调用这里就是将 body 里面的数据追加, 刷到 socketBuffer 里面
            throws IOException {

            int length = chunk.getLength();
            if (useSocketBuffer) {                                       // 这个值何时改变 ?
                socketBuffer.append(chunk.getBuffer(), chunk.getStart(),  //  这里的 socketBuffer 是先写 Header 里面的数据, 然后写 body 里面的数据 ByteChunk
                                    length);
            } else {
                outputStream.write(chunk.getBuffer(), chunk.getStart(),   // 这里是通过 Stream 直接将数据写回客户端
                                   length);
            }
            byteCount += chunk.getLength();
            return chunk.getLength();
        }

        @Override
        public long getBytesWritten() {
            return byteCount;
        }
    }
}

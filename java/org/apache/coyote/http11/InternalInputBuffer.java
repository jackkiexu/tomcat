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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.apache.coyote.InputBuffer;
import org.apache.coyote.Request;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.SocketWrapper;

/**
 * Implementation of InputBuffer which provides HTTP request header parsing as
 * well as transfer decoding.
 *
 * 参考资料
 * http://www.cnblogs.com/killbug/archive/2012/10/21/2733060.html
 *
 * 1. org.apache.coyote.Request 是tomcat内部使用用于存放 关于 request 消息的数据结构
 * 2. oeg.apache.tomcat.util.buf.MessageBytes 用于存放消息, 在 org.apache.coyote.Request 中大量用于存放解析后的 byte 字符
 * 3. org.apache.tomcat.util.buf.ByteChunk 真正用于存放数据的数据结构, 存放的是 byte[], org.apache.tomcat.util.buf.MessageBytes 使用它
 *
 * 大体流程:
 * http 消息通过 InputBuffer 解析后放到 request 中, request 把它放到相应的 messageBytes, 最后 MessageBytes 把它存放到 ByteChunk 里
 *
 *
 * @author <a href="mailto:remm@apache.org">Remy Maucherat</a>
 */
public class InternalInputBuffer extends AbstractInputBuffer<Socket> {

    private static final Log log = LogFactory.getLog(InternalInputBuffer.class);


    /**
     * Underlying input stream.
     */
    private InputStream inputStream;


    /**
     * Default constructor.
     */
    public InternalInputBuffer(Request request, int headerBufferSize) {

        this.request = request;
        headers = request.getMimeHeaders();

        buf = new byte[headerBufferSize];           // 先构造一个比较大的 用于存储 Header 里面信息的 buffer( 在 AbstractHttp11Protocol 里面 默认是 8M)

        inputStreamInputBuffer = new InputStreamInputBuffer();

        filterLibrary = new InputFilter[0];
        activeFilters = new InputFilter[0];
        lastActiveFilter = -1;

        parsingHeader = true;
        swallowInput = true;

    }


    /**
     * Data is always available for blocking IO (if you wait long enough) so
     * return a value of 1. Note that the actual value is never used it is only
     * tested for == 0 or > 0.
     */
    @Override
    public int available() {
        return 1;
    }


    /**
     * Read the request line. This function is meant to be used during the
     * HTTP request header parsing. Do NOT attempt to read the request body
     * using it.
     *
     * @throws IOException If an exception occurs during the underlying socket
     * read operations, or if the given buffer is not big enough to accommodate
     * the whole line.
     * 用于读取请求行
     */
    @Override
    public boolean parseRequestLine(boolean useAvailableDataOnly)

        throws IOException {

        int start = 0;

        //
        // Skipping blank lines
        // 忽略空行
        //

        byte chr = 0;
        do {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }

            chr = buf[pos++];

        } while ((chr == Constants.CR) || (chr == Constants.LF));       // 判断是否是 换行符号

        pos--;

        // Mark the current buffer position
        start = pos;

        //
        // Reading the method name
        // Method name is always US-ASCII
        // space 类似于开关一样, 当为 false 时, 查内容, 为 true 时去除空行

        boolean space = false;

        while (!space) {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }
            //
            // Spec says no CR or LF in method name
            if (buf[pos] == Constants.CR || buf[pos] == Constants.LF) {
                throw new IllegalArgumentException(
                        sm.getString("iib.invalidmethod"));
            }
            // Spec says single SP but it also says be tolerant of HT
            // 查出第一个空格, tab 居然也是允许的
            if (buf[pos] == Constants.SP || buf[pos] == Constants.HT) {
                space = true; // 跳出循环
                // 把下标记录下来, 这里的 method() 得到一个 Request 的 MessageBytes: methodMB
                request.method().setBytes(buf, start, pos - start);
            }

            pos++;

        }


        // Spec says single SP but also says be tolerant of multiple and/or HT
        // 忽略空格后面的空格或 tab, 因为是忽略的内容, 所以不需要什么 start
        while (space) {
            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }
            if (buf[pos] == Constants.SP || buf[pos] == Constants.HT) {
                pos++;  // 忽略的方式就是继续移动下标
            } else {
                space = false;
            }
        }

        // Mark the current buffer position
        start = pos;        // 出现 start了, 后面肯定是需要记录下标
        int end = 0;
        int questionPos = -1;

        //
        // Reading the URI
        //

        boolean eol = false;

        while (!space) {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }

            // Spec says single SP but it also says be tolerant of HT
            // 寻找第二个空格, 第一个空格和第二个空格之间就是传说中的 URI
            if (buf[pos] == Constants.SP || buf[pos] == Constants.HT) {
                space = true;
                end = pos;
            } else if ((buf[pos] == Constants.CR)
                       || (buf[pos] == Constants.LF)) {
                // HTTP/0.9 style request
                eol = true;     // 为了兼容 HTTP 0.9 格式
                space = true;
                end = pos;
            } else if ((buf[pos] == Constants.QUESTION) // 遇到 '?'
                       && (questionPos == -1)) {
                questionPos = pos; // 把问号的位置先记录下来
            }

            pos++;

        }
        // 把可能包含问号的 URI 的起始位和结束位记录下来
        request.unparsedURI().setBytes(buf, start, end - start);
        if (questionPos >= 0) { // 有问号的情况
            // 问号位置记录
            request.queryString().setBytes(buf, questionPos + 1,
                                           end - questionPos - 1);
            // 将 URI 记录下来
            request.requestURI().setBytes(buf, start, questionPos - start);
        } else {
            request.requestURI().setBytes(buf, start, end - start);
        }
        logger.info(new String(request.requestURI().getByteChunk().getBytes(), "utf-8"));

        // Spec says single SP but also says be tolerant of multiple and/or HT
        // 这段算是重复代码, 就是忽略空格
        while (space) {
            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }
            if (buf[pos] == Constants.SP || buf[pos] == Constants.HT) {
                pos++;
            } else {
                space = false;
            }
        }

        // Mark the current buffer position
        start = pos;
        end = 0;

        //
        // Reading the protocol
        // Protocol is always US-ASCII
        ///
        /**
         * eol 标志位是为了标记是否是 HTTP 0.9
         */

        while (!eol) {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }
            // 查出 /r/n
            if (buf[pos] == Constants.CR) {
                end = pos;
            } else if (buf[pos] == Constants.LF) {
                if (end == 0)
                    end = pos;
                eol = true;
            }

            pos++;

        }
        // 至此把 head 分成 三部分, 放到 Request 定义好的 MessageBytes 中去了
        if ((end - start) > 0) {
            request.protocol().setBytes(buf, start, end - start);
        } else {
            request.protocol().setString("");
        }
        logger.info(new String(request.protocol().getByteChunk().getBytes(), "utf-8"));

        return true;

    }


    /**
     * Parse the HTTP headers.
     * 读取请求头
     */
    @Override
    public boolean parseHeaders()
        throws IOException {
        if (!parsingHeader) {
            throw new IllegalStateException(
                    sm.getString("iib.parseheaders.ise.error"));
        }

        while (parseHeader()) {
            // Loop until we run out of headers
        }

        parsingHeader = false;
        end = pos;
        return true;
    }


    /**
     * Parse an HTTP header.
     *
     * @return false after reading a blank line (which indicates that the
     * HTTP header parsing is done
     */
    @SuppressWarnings("null") // headerValue cannot be null
    private boolean parseHeader()
        throws IOException {

        //
        // Check for blank line
        //

        byte chr = 0;
        while (true) {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }

            chr = buf[pos];

            if (chr == Constants.CR) {
                // Skip
            } else if (chr == Constants.LF) {
                pos++;
                return false;
            } else {
                break;
            }

            pos++;

        }

        // Mark the current buffer position
        int start = pos;

        //
        // Reading the header name
        // Header name is always US-ASCII
        //

        boolean colon = false;
        MessageBytes headerValue = null;

        while (!colon) {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }

            if (buf[pos] == Constants.COLON) {
                colon = true;
                headerValue = headers.addValue(buf, start, pos - start);
            } else if (!HTTP_TOKEN_CHAR[buf[pos]]) {
                // If a non-token header is detected, skip the line and
                // ignore the header
                skipLine(start);
                return true;
            }

            chr = buf[pos];
            if ((chr >= Constants.A) && (chr <= Constants.Z)) {
                buf[pos] = (byte) (chr - Constants.LC_OFFSET);
            }

            pos++;

        }

        // Mark the current buffer position
        start = pos;
        int realPos = pos;

        //
        // Reading the header value (which can be spanned over multiple lines)
        //

        boolean eol = false;
        boolean validLine = true;

        while (validLine) {

            boolean space = true;

            // Skipping spaces
            while (space) {

                // Read new bytes if needed
                if (pos >= lastValid) {
                    if (!fill())
                        throw new EOFException(sm.getString("iib.eof.error"));
                }

                if ((buf[pos] == Constants.SP) || (buf[pos] == Constants.HT)) {
                    pos++;
                } else {
                    space = false;
                }

            }

            int lastSignificantChar = realPos;

            // Reading bytes until the end of the line
            while (!eol) {

                // Read new bytes if needed
                if (pos >= lastValid) {
                    if (!fill())
                        throw new EOFException(sm.getString("iib.eof.error"));
                }

                if (buf[pos] == Constants.CR) {
                    // Skip
                } else if (buf[pos] == Constants.LF) {
                    eol = true;
                } else if (buf[pos] == Constants.SP) {
                    buf[realPos] = buf[pos];
                    realPos++;
                } else {
                    buf[realPos] = buf[pos];
                    realPos++;
                    lastSignificantChar = realPos;
                }

                pos++;

            }

            realPos = lastSignificantChar;

            // Checking the first character of the new line. If the character
            // is a LWS, then it's a multiline header

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }

            chr = buf[pos];
            if ((chr != Constants.SP) && (chr != Constants.HT)) {
                validLine = false;
            } else {
                eol = false;
                // Copying one extra space in the buffer (since there must
                // be at least one space inserted between the lines)
                buf[realPos] = chr;
                realPos++;
            }

        }

        // Set the header value
        headerValue.setBytes(buf, start, realPos - start);

        return true;

    }


    @Override
    public void recycle() {
        super.recycle();
        inputStream = null;
    }


    // ------------------------------------------------------ Protected Methods


    @Override
    protected void init(SocketWrapper<Socket> socketWrapper,
            AbstractEndpoint<Socket> endpoint) throws IOException {
        inputStream = socketWrapper.getSocket().getInputStream();
    }



    private void skipLine(int start) throws IOException {
        boolean eol = false;
        int lastRealByte = start;
        if (pos - 1 > start) {
            lastRealByte = pos - 1;
        }

        while (!eol) {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }

            if (buf[pos] == Constants.CR) {
                // Skip
            } else if (buf[pos] == Constants.LF) {
                eol = true;
            } else {
                lastRealByte = pos;
            }
            pos++;
        }

        if (log.isDebugEnabled()) {
            log.debug(sm.getString("iib.invalidheader", new String(buf, start,
                    lastRealByte - start + 1, StandardCharsets.ISO_8859_1)));
        }
    }

    /**
     * Fill the internal buffer using data from the underlying input stream.
     *
     * @return false if at end of stream
     */
    protected boolean fill() throws IOException {
        return fill(true);
    }

    @Override
    protected boolean fill(boolean block) throws IOException {

        int nRead = 0;

        if (parsingHeader) {

            if (lastValid == buf.length) {
                throw new IllegalArgumentException
                    (sm.getString("iib.requestheadertoolarge.error"));
            }

            nRead = inputStream.read(buf, pos, buf.length - lastValid);
            if (nRead > 0) {                                        //  nRead 就是已经读取的数据
                lastValid = pos + nRead;
            }
            logger.info(new String(buf, "UTF-8"));
        } else {

            if (buf.length - end < 4500) {
                // In this case, the request header was really large, so we allocate a
                // brand new one; the old one will get GCed when subsequent requests
                // clear all references
                buf = new byte[buf.length];
                end = 0;
            }
            pos = end;
            lastValid = pos;
            nRead = inputStream.read(buf, pos, buf.length - lastValid);
            if (nRead > 0) {
                lastValid = pos + nRead;
            }

        }

        return (nRead > 0);

    }


    @Override
    protected final Log getLog() {
        return log;
    }


    // ------------------------------------- InputStreamInputBuffer Inner Class

    /**
     * This class is an input buffer which will read its data from an input
     * stream.
     */
    protected class InputStreamInputBuffer
        implements InputBuffer {


        /**
         * Read bytes into the specified chunk.
         */
        @Override
        public int doRead(ByteChunk chunk, Request req )
            throws IOException {

            if (pos >= lastValid) {
                if (!fill())
                    return -1;
            }

            int length = lastValid - pos;
            chunk.setBytes(buf, pos, length);
            pos = lastValid;

            return (length);
        }
    }
}

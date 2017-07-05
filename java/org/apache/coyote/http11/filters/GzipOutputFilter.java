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

package org.apache.coyote.http11.filters;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.coyote.OutputBuffer;
import org.apache.coyote.Response;
import org.apache.coyote.http11.OutputFilter;
import org.apache.tomcat.util.buf.ByteChunk;

/**
 * Gzip output filter.
 *
 * @author Remy Maucherat
 *
 * 这里的 Filter 不是容器端的 filter, 而是在 Response 进行 commit 提交的时候, 基于响应头的 Tomcat 的配置, 是否执行相关的处理,
 * 下面就是 compression 的例子,
 * 原理: 下面其实就是基于流的包装机制, 使用 GzipOutputStream 来在对当前的数据流包装一下
 * 在 outputBuffer 最终 commit 的时候, 调用这个 filter 执行 doWrite 方法, 让输出的字节流进行一次压缩
 *
 * Tomcat 压缩设置 ：
 * 1. compressibleMimeType: The value is a comma separated list of MIME types for which HTTP compression may be used. The default value is
 *      text/html. text/xml, text/plain, text/css, text/javascript, application/javascript
 *
 * 2. compression; The Connector may use HTTP/1.1 GZIP compression in an attempt to save bandwidth, The acceptable values for the parameter is "off"
 *      (disable compression), "on"(allow compression, which causes test data to be compressed), "force" (forces compression in all cases), or a numerial
 *      integer value (which is equivalent to "on", but specifies the minimum amount of data before the output is compressed). If the content-length is
 *      not known and compression is set to "on" or more aggressive, the output will also be compressed, if not specified, this attribute is set
 *      to "off"
 * 3. compressionMinSize: If compression set to "on" then this attribute may be used to specify the minimum amount of data before the output is compressed, if not
 *      specified, this attribute is default to "2048" (2k)
 */
public class GzipOutputFilter implements OutputFilter {


    /**
     * Logger.
     */
    protected static final org.apache.juli.logging.Log log =
        org.apache.juli.logging.LogFactory.getLog(GzipOutputFilter.class);


    // ----------------------------------------------------- Instance Variables


    /**
     * Next buffer in the pipeline.
     */
    protected OutputBuffer buffer;


    /**
     * Compression output stream.
     */
    protected GZIPOutputStream compressionStream = null;


    /**
     * Fake internal output stream.
     */
    protected final OutputStream fakeOutputStream = new FakeOutputStream();


    // --------------------------------------------------- OutputBuffer Methods


    /**
     * Write some bytes.
     *
     * @return number of bytes written by the filter
     */
    @Override
    public int doWrite(ByteChunk chunk, Response res)
        throws IOException {
        if (compressionStream == null) {
            compressionStream = new GZIPOutputStream(fakeOutputStream, true);
        }
        compressionStream.write(chunk.getBytes(), chunk.getStart(),
                                chunk.getLength());
        return chunk.getLength();
    }


    @Override
    public long getBytesWritten() {
        return buffer.getBytesWritten();
    }


    // --------------------------------------------------- OutputFilter Methods

    /**
     * Added to allow flushing to happen for the gzip'ed outputstream
     */
    public void flush() {
        if (compressionStream != null) {
            try {
                if (log.isDebugEnabled()) {
                    log.debug("Flushing the compression stream!");
                }
                compressionStream.flush();
            } catch (IOException e) {
                if (log.isDebugEnabled()) {
                    log.debug("Ignored exception while flushing gzip filter", e);
                }
            }
        }
    }

    /**
     * Some filters need additional parameters from the response. All the
     * necessary reading can occur in that method, as this method is called
     * after the response header processing is complete.
     */
    @Override
    public void setResponse(Response response) {
        // NOOP: No need for parameters from response in this filter
    }


    /**
     * Set the next buffer in the filter pipeline.
     */
    @Override
    public void setBuffer(OutputBuffer buffer) {
        this.buffer = buffer;
    }


    /**
     * End the current request. It is acceptable to write extra bytes using
     * buffer.doWrite during the execution of this method.
     */
    @Override
    public long end()
        throws IOException {
        if (compressionStream == null) {
            compressionStream = new GZIPOutputStream(fakeOutputStream, true);
        }
        compressionStream.finish();
        compressionStream.close();
        return ((OutputFilter) buffer).end();
    }


    /**
     * Make the filter ready to process the next request.
     */
    @Override
    public void recycle() {
        // Set compression stream to null
        compressionStream = null;
    }


    // ------------------------------------------- FakeOutputStream Inner Class


    protected class FakeOutputStream
        extends OutputStream {
        protected final ByteChunk outputChunk = new ByteChunk();
        protected final byte[] singleByteBuffer = new byte[1];
        @Override
        public void write(int b)
            throws IOException {
            // Shouldn't get used for good performance, but is needed for
            // compatibility with Sun JDK 1.4.0
            singleByteBuffer[0] = (byte) (b & 0xff);
            outputChunk.setBytes(singleByteBuffer, 0, 1);
            buffer.doWrite(outputChunk, null);
        }
        @Override
        public void write(byte[] b, int off, int len)
            throws IOException {
            outputChunk.setBytes(b, off, len);
            buffer.doWrite(outputChunk, null);
        }
        @Override
        public void flush() throws IOException {/*NOOP*/}
        @Override
        public void close() throws IOException {/*NOOP*/}
    }


}

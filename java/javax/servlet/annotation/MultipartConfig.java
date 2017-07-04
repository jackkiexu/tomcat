/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package javax.servlet.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation is used to indicate that the {@link javax.servlet.Servlet} on
 * which it is declared expects requests to made using the {@code
 * multipart/form-data} MIME type. <br />
 * <br />
 *
 * {@link javax.servlet.http.Part} components of a given {@code
 * multipart/form-data} request are retrieved by a Servlet annotated with
 * {@code MultipartConfig} by calling
 * {@link javax.servlet.http.HttpServletRequest#getPart} or
 * {@link javax.servlet.http.HttpServletRequest#getParts}.<br />
 * <br />
 *
 * E.g. <code>@WebServlet("/upload")}</code><br />
 *
 * <code>@MultipartConfig()</code> <code>public class UploadServlet extends
 * HttpServlet ... } </code><br />
 *
 * @since Servlet 3.0
 *
 * MultipartConfig 使用的建议
 * 1. 若是上传一个文件, 仅仅需要设置 maxFileSize
 * 2. 上传多个文件, 可能需要设置 maxRequestSize, 设定一次上传数据的最大量
 * 3. 上传过程中无论单个文件超过 maxFileSize, 或者上传总的数量大于 maxRequestSize 值都会抛出 IllegalStateExceprion 异常
 * 4. location 既是保存路径, 又是上传过程中临时文件的保存路径, 一旦 执行 Part.write方法之后, 临时文件将被自动清除
 * 5. Servlet 3.0 规范中指明不提供 获取文件名的方法, 但我们可以通过 part.getHeader("content-disposition") 方法间接获取到
 * 6. 如何读取 MultipartConfig 注解属性值, API
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface MultipartConfig {

    /**
     * 存放的文件的地址
     * @return location in which the Container stores temporary files
     */
    String location() default "";

    /**
     * 允许上传文件的最大值
     * @return the maximum size allowed for uploaded files (in bytes)
     */
    long maxFileSize() default -1L;

    /**
     * 请求的最大数量
     * @return the maximum size of the request allowed for {@code
     *         multipart/form-data}
     */
    long maxRequestSize() default -1L;

    /**
     * 当数据大于这个值, 数据将会被写入磁盘
     * @return the size threshold at which the file will be written to the disk
     */
    int fileSizeThreshold() default 0;
}

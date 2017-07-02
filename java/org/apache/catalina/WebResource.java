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
package org.apache.catalina;

import java.io.InputStream;
import java.net.URL;
import java.security.cert.Certificate;
import java.util.jar.Manifest;

/**
 * Represents a file or directory within a web application. It borrows heavily
 * from {@link java.io.File}.
 *
 *  参考资料
 *  http://mp.weixin.qq.com/s?__biz=MzA4MTc3Nzk4NQ==&mid=2650076194&idx=1&sn=fd9848eefd258dca2f9bdf061f5647e3&mpshare=1&scene=23&srcid=0702ji9L2W6vQtFqBOLrPsk8#rd
 *
 *  WebResource 接口时抽象的 web 应用对应的资源, 下面是其主要的实现
 *  1. JarResource: 代表 jar 包的尸体资源, 一个 jar包对应一个资源
 *  2. JarWarResource: 代表 jar 包中的尸体资源, 可以把 jar 包看成一个目录, 在这个 jar 包中还有很多的资源隐藏在 jar 包的里面
 *  3. FileResource: 文件资源, 代表 Web 应用下面的一个 html, 图片等各种以文件形式各种格式存储的资源, 除此以外, 这个 FileResource也代表目录
 *  4. CachedResource: 在 WebResourceRoot的实现类 StandardRoot 中, 有对应的 Cache类, 这个 Cache 类的作用在资源查找的过程中,
 *      通过 Cache进行缓存, 当然前提是需要将 Cache 打开, 这么做是提高性能的好办法
 *  5. EmptyResource: 作为一个实体占为的 Resource, 在 StandardRoot的 getResource方法中, 发现什么都没查出来
 *      但还要返回一个 WebResource接口的实现类, 这时就用到 EmptyResource
 *
 */
public interface WebResource {
    /**
     * See {@link java.io.File#lastModified()}.
     */
    long getLastModified();

    /**
     * Return the last modified time of this resource in the correct format for
     * the HTTP Last-Modified header as specified by RFC 2616.
     */
    String getLastModifiedHttp();

    /**
     * See {@link java.io.File#exists()}.
     */
    boolean exists();

    /**
     * Indicates if this resource is required for applications to correctly scan
     * the file structure but that does not exist in either the main or any
     * additional {@link WebResourceSet}. For example, if an external
     * directory is mapped to /WEB-INF/lib in an otherwise empty web
     * application, /WEB-INF will be represented as a virtual resource.
     */
    boolean isVirtual();

    /**
     * See {@link java.io.File#isDirectory()}.
     */
    boolean isDirectory();

    /**
     * See {@link java.io.File#isFile()}.
     */
    boolean isFile();

    /**
     * See {@link java.io.File#delete()}.
     */
    boolean delete();

    /**
     * See {@link java.io.File#getName()}.
     */
    String getName();

    /**
     * See {@link java.io.File#length()}.
     */
    long getContentLength();

    /**
     * See {@link java.io.File#getCanonicalPath()}.
     */
    String getCanonicalPath();

    /**
     * See {@link java.io.File#canRead()}.
     */
    boolean canRead();

    /**
     * The path of this resource relative to the web application root. If the
     * resource is a directory, the return value will end in '/'.
     */
    String getWebappPath();

    /**
     * Return the strong ETag if available (currently not supported) else return
     * the weak ETag calculated from the content length and last modified.
     *
     * @return  The ETag for this resource
     */
    String getETag();

    /**
     * Set the MIME type for this Resource.
     */
    void setMimeType(String mimeType);

    /**
     * Get the MIME type for this Resource.
     */
    String getMimeType();

    /**
     * Obtain an InputStream based on the contents of this resource.
     *
     * @return  An InputStream based on the contents of this resource or
     *          <code>null</code> if the resource does not exist or does not
     *          represent a file
     */
    InputStream getInputStream();

    /**
     * Obtain the cached binary content of this resource.
     */
    byte[] getContent();

    /**
     * The time the file was created. If not available, the result of
     * {@link #getLastModified()} will be returned.
     */
    long getCreation();

    /**
     * Obtain a URL to access the resource or <code>null</code> if no such URL
     * is available or if the resource does not exist.
     */
    URL getURL();

    /**
     * Obtain a reference to the WebResourceRoot of which this WebResource is a
     * part.
     */
    WebResourceRoot getWebResourceRoot();

    /**
     * Obtain the certificates that were used to sign this resource to verify
     * it or @null if none.
     *
     * @see java.util.jar.JarEntry#getCertificates()
     */
    Certificate[] getCertificates();

    /**
     * Obtain the manifest associated with this resource or @null if none.
     *
     * @see java.util.jar.JarFile#getManifest()
     */
    Manifest getManifest();
}

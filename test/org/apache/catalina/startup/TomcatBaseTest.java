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
package org.apache.catalina.startup;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.junit.Assert.fail;

import org.apache.coyote.http11.Http11Protocol;
import org.junit.After;
import org.junit.Before;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Server;
import org.apache.catalina.Service;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.AprLifecycleListener;
import org.apache.catalina.core.StandardServer;
import org.apache.catalina.session.StandardManager;
import org.apache.catalina.valves.AccessLogValve;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.buf.ByteChunk;

/**
 * Base test case that provides a Tomcat instance for each test - mainly so we
 * don't have to keep writing the cleanup code.
 */
public abstract class TomcatBaseTest extends LoggingBaseTest {
    private Tomcat tomcat;
    private boolean accessLogEnabled = false;

    public static final String TEMP_DIR = System.getProperty("java.io.tmpdir");

    /**
     * Make Tomcat instance accessible to sub-classes.
     */
    public Tomcat getTomcatInstance() {
        return tomcat;
    }

    /**
     * Sub-classes need to know port so they can connect
     */
    public int getPort() {
        return tomcat.getConnector().getLocalPort();
    }

    /**
     * Sub-classes may want to check, whether an AccessLogValve is active
     */
    public boolean isAccessLogEnabled() {
        return accessLogEnabled;
    }

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        // Trigger loading of catalina.properties
        String value = CatalinaProperties.getProperty("foo");

        File appBase = new File(getTemporaryDirectory(), "webapps");
        if (!appBase.exists() && !appBase.mkdir()) {
            fail("Unable to create appBase for test");
        }
        // 运行程序时 在Java命令行后面加上 "-verbose:class -XX:+TraceClassLoading" 来显示 class 的加载
        // 这时发现在 new Tomcat() 时, ClassLoader 加载了 Host, Wrapper, Server, Service, Realm, Engine,Manager
        // -> 从而说明 只要Class中有对应的 变量成员定义, 则在 classLoader.load(Class) 时通过 resolve 来加载数据
        // (PS: 这时若 Tomcat 有父类, 则父类也会一级一级的从上至下进行加载, 父类的成员变量不进行 ClassLoader 加载)
        tomcat = new TomcatWithFastSessionIDs();

        String protocol = getProtocol();                                                    // 默认连接协议获取 org.apache.coyote.http11.Http11NioProtocol
        Connector connector = new Connector(protocol);                                      // 构建 Connector, 在构建的过程中会生成 protocolHandler
        // Listen only on localhost
        connector.setAttribute("address",                                                  // 设置连接的地址, 里面是通过 反射工具类 IntrospectionUtils 来进行生成的(IntrospectionUtils 是一个非常强大的类)
                InetAddress.getByName("localhost").getHostAddress());
        // Use random free port
        connector.setPort(0);
        // Mainly set to reduce timeouts during async tests
        connector.setAttribute("connectionTimeout", "3000");
        tomcat.getService().addConnector(connector);                                        // 初始化 server, service
        tomcat.setConnector(connector);

        // Add AprLifecycleListener if we are using the Apr connector
        if (protocol.contains("Apr")) {
            StandardServer server = (StandardServer) tomcat.getServer();
            AprLifecycleListener listener = new AprLifecycleListener();
            listener.setSSLRandomSeed("/dev/urandom");
            server.addLifecycleListener(listener);
            connector.setAttribute("pollerThreadCount", Integer.valueOf(1));
        }

        File catalinaBase = getTemporaryDirectory();
        tomcat.setBaseDir(catalinaBase.getAbsolutePath());
        tomcat.getHost().setAppBase(appBase.getAbsolutePath());                             // 初始化 host, 及 Engine

        accessLogEnabled = Boolean.parseBoolean(
            System.getProperty("tomcat.test.accesslog", "false"));
        if (accessLogEnabled) {
            AccessLogValve alv = new AccessLogValve();
            alv.setDirectory(getBuildDirectory() + "/logs");
            alv.setPattern("%h %l %u %t \"%r\" %s %b %I %D");
            tomcat.getHost().getPipeline().addValve(alv);
        }

        // Cannot delete the whole tempDir, because logs are there,
        // but delete known subdirectories of it.
        addDeleteOnTearDown(new File(catalinaBase, "webapps"));         // 增加在 test 用例关闭时删除的文件夹
        addDeleteOnTearDown(new File(catalinaBase, "work"));            // 增加在 test 用例关闭时删除的文件夹
    }

    protected String getProtocol() {
        // Has a protocol been specified
        // 获取 程序默认指定的 处理协议
        String protocol = System.getProperty("tomcat.test.protocol");   // 1. 获取 java 默认处理协议

        // Use NIO by default starting with Tomcat 8
        if (protocol == null) {
            protocol = Http11NioProtocol.class.getName();                 // 2. Tomcat 8 默认是 NIO 模式
            protocol = Http11Protocol.class.getName();                    // 3. 这里我们先用 BIO 做分析
        }

        return protocol;
    }

    @After
    @Override
    public void tearDown() throws Exception {
        try {
            // Some tests may call tomcat.destroy(), some tests may just call
            // tomcat.stop(), some not call either method. Make sure that stop()
            // & destroy() are called as necessary.
            if (tomcat.server != null
                    && tomcat.server.getState() != LifecycleState.DESTROYED) {
                if (tomcat.server.getState() != LifecycleState.STOPPED) {
                    tomcat.stop();
                }
                tomcat.destroy();
            }
        } finally {
            super.tearDown();
        }
    }

    /**
     * Simple Hello World servlet for use by test cases
     */
    public static final class HelloWorldServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        public static final String RESPONSE_TEXT =
            "<html><body><p>Hello World</p></body></html>";

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            PrintWriter out = resp.getWriter();
            out.print(RESPONSE_TEXT);
        }
    }


    /**
     *  Wrapper for getting the response.
     */
    public static ByteChunk getUrl(String path) throws IOException {
        ByteChunk out = new ByteChunk();
        getUrl(path, out, null);
        return out;
    }

    public static int getUrl(String path, ByteChunk out,
            Map<String, List<String>> resHead) throws IOException {
        return getUrl(path, out, null, resHead);
    }

    public static int headUrl(String path, ByteChunk out,
            Map<String, List<String>> resHead) throws IOException {
        return methodUrl(path, out, 1000000000, null, resHead, "HEAD");
    }

    public static int getUrl(String path, ByteChunk out,
            Map<String, List<String>> reqHead,
            Map<String, List<String>> resHead) throws IOException {
        return getUrl(path, out, 1000000000, reqHead, resHead);
    }

    public static int getUrl(String path, ByteChunk out, int readTimeout,
            Map<String, List<String>> reqHead,
            Map<String, List<String>> resHead) throws IOException {
        return methodUrl(path, out, readTimeout, reqHead, resHead, "GET");
    }

    public static int methodUrl(String path, ByteChunk out, int readTimeout,
            Map<String, List<String>> reqHead,
            Map<String, List<String>> resHead,
            String method) throws IOException {

        URL url = new URL(path);
        HttpURLConnection connection =
            (HttpURLConnection) url.openConnection();
        connection.setUseCaches(false);
        connection.setReadTimeout(readTimeout);
        connection.setRequestMethod(method);
        if (reqHead != null) {
            for (Map.Entry<String, List<String>> entry : reqHead.entrySet()) {
                StringBuilder valueList = new StringBuilder();
                for (String value : entry.getValue()) {
                    if (valueList.length() > 0) {
                        valueList.append(',');
                    }
                    valueList.append(value);
                }
                connection.setRequestProperty(entry.getKey(),
                        valueList.toString());
            }
        }
        connection.connect();
        int rc = connection.getResponseCode();
        if (resHead != null) {
            Map<String, List<String>> head = connection.getHeaderFields();
            resHead.putAll(head);
        }
        InputStream is;
        if (rc < 400) {
            is = connection.getInputStream();
        } else {
            is = connection.getErrorStream();
        }
        if (is != null) {
            BufferedInputStream bis = null;
            try {
                bis = new BufferedInputStream(is);
                byte[] buf = new byte[2048];
                int rd = 0;
                while((rd = bis.read(buf)) > 0) {
                    out.append(buf, 0, rd);
                }
            } finally {
                if (bis != null) {
                    try {
                        bis.close();
                    } catch (IOException e) {
                        // Ignore
                    }
                }
            }
        }
        return rc;
    }

    public static ByteChunk postUrl(byte[] body, String path)
            throws IOException {
        ByteChunk out = new ByteChunk();
        postUrl(body, path, out, null);
        return out;
    }

    public static int postUrl(byte[] body, String path, ByteChunk out,
            Map<String, List<String>> resHead) throws IOException {
        return postUrl(body, path, out, null, resHead);
    }

    public static int postUrl(final byte[] body, String path, ByteChunk out,
            Map<String, List<String>> reqHead,
            Map<String, List<String>> resHead) throws IOException {
            BytesStreamer s = new BytesStreamer() {
            boolean done = false;
            @Override
            public byte[] next() {
                done = true;
                return body;

            }

            @Override
            public int getLength() {
                return body!=null?body.length:0;
            }

            @Override
            public int available() {
                if (done) return 0;
                else return getLength();
            }
        };
        return postUrl(false,s,path,out,reqHead,resHead);
    }


    public static int postUrl(boolean stream, BytesStreamer streamer, String path, ByteChunk out,
                Map<String, List<String>> reqHead,
                Map<String, List<String>> resHead) throws IOException {

        URL url = new URL(path);
        HttpURLConnection connection =
            (HttpURLConnection) url.openConnection();
        connection.setDoOutput(true);
        connection.setReadTimeout(1000000);
        if (reqHead != null) {
            for (Map.Entry<String, List<String>> entry : reqHead.entrySet()) {
                StringBuilder valueList = new StringBuilder();
                for (String value : entry.getValue()) {
                    if (valueList.length() > 0) {
                        valueList.append(',');
                    }
                    valueList.append(value);
                }
                connection.setRequestProperty(entry.getKey(),
                        valueList.toString());
            }
        }
        if (streamer != null && stream) {
            if (streamer.getLength()>0) {
                connection.setFixedLengthStreamingMode(streamer.getLength());
            } else {
                connection.setChunkedStreamingMode(1024);
            }
        }

        connection.connect();

        // Write the request body
        OutputStream os = null;
        try {
            os = connection.getOutputStream();
            while (streamer!=null && streamer.available()>0) {
                byte[] next = streamer.next();
                os.write(next);
                os.flush();
            }

        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (IOException ioe) {
                    // Ignore
                }
            }
        }

        int rc = connection.getResponseCode();
        if (resHead != null) {
            Map<String, List<String>> head = connection.getHeaderFields();
            resHead.putAll(head);
        }
        InputStream is;
        if (rc < 400) {
            is = connection.getInputStream();
        } else {
            is = connection.getErrorStream();
        }

        BufferedInputStream bis = null;
        try {
            bis = new BufferedInputStream(is);
            byte[] buf = new byte[2048];
            int rd = 0;
            while((rd = bis.read(buf)) > 0) {
                out.append(buf, 0, rd);
            }
        } finally {
            if (bis != null) {
                try {
                    bis.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
        return rc;
    }

    private static class TomcatWithFastSessionIDs extends Tomcat {

        @Override
        public void start() throws LifecycleException {                      // 初始化每个 Context 里面的 Session 管理器
            // Use fast, insecure session ID generation for all tests
            Server server = getServer();
            for (Service service : server.findServices()) {                     // 到 Service 层
                Container e = service.getContainer();                           // 到 Engine 层
                for (Container h : e.findChildren()) {                          // 到  Host 层
                    for (Container c : h.findChildren()) {                      // 到 Context 层
                        StandardManager m =                                     // 这里的 SandardManager 其实就是 Session 管理器
                                (StandardManager) ((Context) c).getManager();   // 初始化 Session 管理器
                        if (m == null) {
                            m = new StandardManager();
                            m.setSecureRandomClass(
                                    "org.apache.catalina.startup.FastNonSecureRandom");
                            ((Context) c).setManager(m);
                        }
                    }
                }
            }
            super.start();
        }
    }
}

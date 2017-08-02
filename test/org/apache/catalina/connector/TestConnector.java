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
package org.apache.catalina.connector;

import java.net.SocketTimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.TesterServlet;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.startup.TomcatBaseTest;
import org.apache.tomcat.util.buf.ByteChunk;

/**
 * Test cases for {@link Connector}.
 */
public class TestConnector extends TomcatBaseTest {

    @Test
    public void testStop() throws Exception {
        Tomcat tomcat = getTomcatInstance();

        Context root = tomcat.addContext("", TEMP_DIR);         // 我在这里加入了 2个 Servlet, 对应的在 StandardContext 里面会有对应两个 StandardWrapper 生成
        Wrapper w = Tomcat.addServlet(root, "tester", new TesterServlet());
        Wrapper w2 = Tomcat.addServlet(root, "tester2", new TesterServlet());
        w.setAsyncSupported(true);
        w2.setAsyncSupported(true);
        root.addServletMapping("/", "tester");
        root.addServletMapping("/tester/", "tester2");

        Connector connector = tomcat.getConnector();

        tomcat.start();

        ByteChunk bc = new ByteChunk();
        int rc = getUrl("http://localhost:" + getPort() + "/tester/getMyName?name=xjk", bc, null, null);

        assertEquals(200, rc);
        assertEquals("OK", bc.toString());
        logger.info(" bc.toString():" +  bc.toString());

        rc = -1;
        bc.recycle();

        connector.stop();

        try {
            rc = getUrl("http://localhost:" + getPort() + "/", bc, 1000,
                    null, null);
            logger.info("rc:" + rc);
        } catch (SocketTimeoutException ste) {
            // May also see this with NIO
            // Make sure the test passes if we do
            rc = 503;
        }
        logger.info("rc:" + rc);
        assertEquals(503, rc);
    }


    // 若像下面的设置 端口号都是 0 的话, 就会随机的分配一个端口
    @Test
    public void testPort() throws Exception {
        Tomcat tomcat = getTomcatInstance();

        Connector connector1 = tomcat.getConnector();
        connector1.setPort(0);

        Connector connector2 = new Connector();
        connector2.setPort(0);

        tomcat.getService().addConnector(connector2);

        tomcat.start();

        int localPort1 = connector1.getLocalPort();
        int localPort2 = connector2.getLocalPort();

        logger.info("localPort1:" + localPort1);
        logger.info("localPort2:" + localPort2);

        assertTrue(localPort1 > 0);
        assertTrue(localPort2 > 0);
    }
}

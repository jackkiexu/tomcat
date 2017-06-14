package org.apache.catalina.core;

import org.apache.log4j.Logger;
import org.junit.Test;

/**
 * Created by xujiankang on 2017/6/14.
 */
public class TestStandardServer {

    private static final Logger logger = Logger.getLogger(TestStandardServer.class);

    @Test
    public void testSoTimeOut(){
        StandardServer standardServer = new StandardServer();
        standardServer.await();
    }

}

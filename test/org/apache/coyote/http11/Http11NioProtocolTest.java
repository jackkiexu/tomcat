package org.apache.coyote.http11;

import org.apache.log4j.Logger;
import org.junit.Test;

/**
 * Created by xjk on 6/13/17.
 */
public class Http11NioProtocolTest {

    private static final Logger logger = Logger.getLogger(Http11NioProtocolTest.class);


    @Test
    public void testInit()throws Exception{
        Http11NioProtocol http11NioProtocol = new Http11NioProtocol();
        http11NioProtocol.init();
        http11NioProtocol.start();
        logger.info(http11NioProtocol);
    }
}

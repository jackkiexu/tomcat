package org.apache.tomcat.util.net;

import org.junit.Test;

/**
 * Created by xujiankang on 2017/6/12.
 */
public class NioSelectorPoolTest {

    @Test
    public void testNioSelectorPool() throws Exception{
        NioSelectorPool nioSelectorPool = new NioSelectorPool();
        nioSelectorPool.open();

        Thread.sleep(60*1000000);
    }

}

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
package org.apache.tomcat.util.net;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * 参考资料
 * http://gearever.iteye.com/blog/1844203
 * 资料 (推荐)
 * https://mp.weixin.qq.com/s?__biz=MzA4MTc3Nzk4NQ==&mid=2650075890&idx=1&sn=ae57162a5d557bbadcbc9fb0ea1d44e3&mpshare=1&scene=23&srcid=0705tg52LlZLagHCuEqzygqB#rd
 *
 * https://mp.weixin.qq.com/s?__biz=MzA4MTc3Nzk4NQ==&mid=2650075890&idx=1&sn=ae57162a5d557bbadcbc9fb0ea1d44e3&mpshare=1&scene=23&srcid=06125QjgxvOSnhUYwDe2fMWN#rd
 * https://mp.weixin.qq.com/s?__biz=MzA4MTc3Nzk4NQ==&mid=2650075890&idx=1&sn=ae57162a5d557bbadcbc9fb0ea1d44e3&mpshare=1&scene=23&srcid=06203XK7llXuAWHiKFO3bYRc#rd
 *
 * Thread safe non blocking selector pool
 * @version 1.0
 * @since 6.0
 */

public class NioSelectorPool {

    public NioSelectorPool() {
    }

    private static final Log log = LogFactory.getLog(NioSelectorPool.class);

    protected static final boolean SHARED =
        Boolean.valueOf(System.getProperty("org.apache.tomcat.util.net.NioSelectorShared", "true")).booleanValue();

    protected NioBlockingSelector blockingSelector;

    protected volatile Selector SHARED_SELECTOR;

    // 以减少选择器的争用, 在池中使用的最大选择器的个数
    protected int maxSelectors = 200;
    protected long sharedSelectorTimeout = 30000;
    // 以减少选择器的争用, 在池中使用的最大备用选择器的个数, 当  selector 返回池中, 就又这个参数决定 selector 是否还需要继续存在
    protected int maxSpareSelectors = -1;
    protected boolean enabled = true;
    protected AtomicInteger active = new AtomicInteger(0);
    protected AtomicInteger spare = new AtomicInteger(0);
    protected ConcurrentLinkedQueue<Selector> selectors =
            new ConcurrentLinkedQueue<>();

    // 双重检查锁 http://www.infoq.com/cn/articles/double-checked-locking-with-delay-initialization
    protected Selector getSharedSelector() throws IOException {
        if (SHARED && SHARED_SELECTOR == null) {                // 这里可以看做是个 DCL(Double Check Lock), 我们可以看到 SHARED_SELECTOR 是被 volatile 修饰, 重而保证
            synchronized ( NioSelectorPool.class ) {
                if ( SHARED_SELECTOR == null )  {
                    synchronized (Selector.class) {
                        // Selector.open() isn't thread safe
                        // http://bugs.sun.com/view_bug.do?bug_id=6427854
                        // Affects 1.6.0_29, fixed in 1.7.0_01
                        SHARED_SELECTOR = Selector.open();
                    }
                    log.info("Using a shared selector for servlet write/read");
                }
            }
        }
        return  SHARED_SELECTOR;
    }

    /**
     * 在每一次新增 selector 的时候, 需要考虑看看啊池中目前有多少个, 如果没有超出的话, 可以新建 selector, 否则
     * 只能使用池中现有的 selector
     *
     * 下面的 active 与 space 是原子变量 作用是实现 自增, 自减 来实现 selector 控制
     * 下面的操作返回一个 Selector 主要是用于 NioChannel 在第一次写数据不成功后, 注册到 selector 上, 以便进行再次的 写操作
     * 用多个 Selector 的好处, 主要是 selector.select() selector.register() 两者操作的都是 相同的几个 all-keys,  而底层为了编发安全, 在这些操作的代码块上进行了同步, 从而避免对共享资源的竞争
     */
    @SuppressWarnings("resource") // s is closed in put()
    public Selector get() throws IOException{
        if ( SHARED ) {
            return getSharedSelector();             // 若配置 SHARED = true, 则 就共享一个 selector
        }
        if ( (!enabled) || active.incrementAndGet() >= maxSelectors ) {
            if ( enabled ) active.decrementAndGet();
            return null;
        }
        Selector s = null;
        try {
            s = selectors.size()>0?selectors.poll():null;
            if (s == null) {                                        // 若没有超出 maxSelectors 则直接新建一个
                synchronized (Selector.class) {
                    // Selector.open() isn't thread safe
                    // http://bugs.sun.com/view_bug.do?bug_id=6427854
                    // Affects 1.6.0_29, fixed in 1.7.0_01
                    s = Selector.open();
                }
            }
            else spare.decrementAndGet();

        }catch (NoSuchElementException x ) {
            try {
                synchronized (Selector.class) {
                    // Selector.open() isn't thread safe
                    // http://bugs.sun.com/view_bug.do?bug_id=6427854
                    // Affects 1.6.0_29, fixed in 1.7.0_01
                    s = Selector.open();
                }
            } catch (IOException iox) {
            }
        } finally {
            if ( s == null ) active.decrementAndGet();//we were unable to find a selector
        }
        return s;
    }


    /**
     * 下面通过 maxSelectors 与 maxSpaceSelectors 来控制 selector 对象池里面的对象
     */
    public void put(Selector s) throws IOException {
        if ( SHARED ) return;
        if ( enabled ) active.decrementAndGet();
        if ( enabled && (maxSpareSelectors==-1 || spare.get() < Math.min(maxSpareSelectors,maxSelectors)) ) {
            spare.incrementAndGet();
            selectors.offer(s);
        }
        else s.close();
    }

    public void close() throws IOException {
        enabled = false;
        Selector s;
        while ( (s = selectors.poll()) != null ) s.close();
        spare.set(0);
        active.set(0);
        if (blockingSelector!=null) {
            blockingSelector.close();
        }
        if ( SHARED && getSharedSelector()!=null ) {
            getSharedSelector().close();
            SHARED_SELECTOR = null;
        }
    }

    public void open() throws IOException {
        enabled = true;
        getSharedSelector();
        if (SHARED) {
            blockingSelector = new NioBlockingSelector();
            blockingSelector.open(getSharedSelector());
        }

    }

    /**
     * Performs a write using the bytebuffer for data to be written and a
     * selector to block (if blocking is requested). If the
     * <code>selector</code> parameter is null, and blocking is requested then
     * it will perform a busy write that could take up a lot of CPU cycles.
     * @param buf           The buffer containing the data, we will write as long as <code>(buf.hasRemaining()==true)</code>
     * @param socket        The socket to write data to
     * @param selector      The selector to use for blocking, if null then a busy write will be initiated
     * @param writeTimeout  The timeout for this write operation in milliseconds, -1 means no timeout
     * @param block         <code>true</code> to perform a blocking write
     *                      otherwise a non-blocking write will be performed
     * @return int - returns the number of bytes written
     * @throws EOFException if write returns -1
     * @throws SocketTimeoutException if the write times out
     * @throws IOException if an IO Exception occurs in the underlying socket logic
     *
     * NioSelectorPool 承担了 Nio 模式下的 阻塞与 非阻塞的写操作 (what nio 还有阻塞的写, 这个主要是 Servlet 规范规定 Servlet 的写数据要是 阻塞的, 所以... )
     */
    public int write(ByteBuffer buf, NioChannel socket, Selector selector,
                     long writeTimeout, boolean block) throws IOException {
        /**
         * 若 SHARED = true, 则 新开启的 selector 仅仅只有 1 个, 而当前的这次写入操作在 Tomcat 中调用的是 block 的话,
         * 直接调用 NioBlovckingSelector 来写入
         * 若 SHARED != true maxSelectors 与 maxSpaceSelectors 才起作用
         */
        if ( SHARED && block ) {
            return blockingSelector.write(buf,socket,writeTimeout);                            // 若 block 是 true, 阻塞写入模式, 为什么要这里是阻塞的呢? Servlet 规范给规定的
        }
        SelectionKey key = null;
        int written = 0;
        boolean timedout = false;
        int keycount = 1; //assume we can write
        long time = System.currentTimeMillis(); //start the timeout timer
        try {
            while ( (!timedout) && buf.hasRemaining() ) {
                int cnt = 0;
                if ( keycount > 0 ) { //only write if we were registered for a write                // 写入成功
                    cnt = socket.write(buf); //write the data                                       // 写入数据
                    if (cnt == -1) throw new EOFException();

                    written += cnt;
                    if (cnt > 0) {                                                                  // cnt > 0 表示 已经写入成功的数据, continue 再写数据
                        time = System.currentTimeMillis(); //reset our timeout timer
                        continue; //we successfully wrote, try again without a selector
                    }
                    if (cnt==0 && (!block)) break; //don't block                                   // 没有写入成功数据
                }
                /**
                 * 基于低并发来讲, 一次 SocketChannel 写入就会成功 ， guoru 是高并发, 非组赛模式的写入, 需要开启一个 NIO 通道
                 * 将 SocketChannel 注册到新开启的Selector 上, 这样的好处就是不占用 Poller 线程中的 Selector
                 */
                // 如果写数据返回值 cnt = 0, 通常是网络不稳定造成写数据失败, 这时候 将要写的 SocketChannel 注册到新的 selector 上, 等候 WRITE 事件的产生
                if ( selector != null ) {                                                          // 写入不成功
                    //register OP_WRITE to the selector                                             // 将 OP_WRITE 事件注册到 selector 上
                    if (key==null) key = socket.getIOChannel().register(selector, SelectionKey.OP_WRITE);
                    else key.interestOps(SelectionKey.OP_WRITE);
                    if (writeTimeout==0) {
                        timedout = buf.hasRemaining();
                    } else if (writeTimeout<0) {
                        keycount = selector.select();                                             // 其实这里也是阻塞的 (PS: java NIO cpu 空转的 bug 若发生了 ， 那怎么办??? )
                    } else {
                        keycount = selector.select(writeTimeout);
                    }
                }
                if (writeTimeout > 0 && (selector == null || keycount == 0) ) timedout = (System.currentTimeMillis()-time)>=writeTimeout;
            }//while
            if ( timedout ) throw new SocketTimeoutException();
        } finally {
            if (key != null) {
                key.cancel();
                if (selector != null) selector.selectNow();//removes the key from this selector
            }
        }
        return written;
    }

    /**
     * Performs a blocking read using the bytebuffer for data to be read and a selector to block.
     * If the <code>selector</code> parameter is null, then it will perform a busy read that could
     * take up a lot of CPU cycles.
     * @param buf ByteBuffer - the buffer containing the data, we will read as until we have read at least one byte or we timed out
     * @param socket SocketChannel - the socket to write data to
     * @param selector Selector - the selector to use for blocking, if null then a busy read will be initiated
     * @param readTimeout long - the timeout for this read operation in milliseconds, -1 means no timeout
     * @return int - returns the number of bytes read
     * @throws EOFException if read returns -1
     * @throws SocketTimeoutException if the read times out
     * @throws IOException if an IO Exception occurs in the underlying socket logic
     */
    public int read(ByteBuffer buf, NioChannel socket, Selector selector, long readTimeout) throws IOException {
        return read(buf,socket,selector,readTimeout,true);
    }
    /**
     * Performs a read using the bytebuffer for data to be read and a selector to register for events should
     * you have the block=true.
     * If the <code>selector</code> parameter is null, then it will perform a busy read that could
     * take up a lot of CPU cycles.
     * @param buf ByteBuffer - the buffer containing the data, we will read as until we have read at least one byte or we timed out
     * @param socket SocketChannel - the socket to write data to
     * @param selector Selector - the selector to use for blocking, if null then a busy read will be initiated
     * @param readTimeout long - the timeout for this read operation in milliseconds, -1 means no timeout
     * @param block - true if you want to block until data becomes available or timeout time has been reached
     * @return int - returns the number of bytes read
     * @throws EOFException if read returns -1
     * @throws SocketTimeoutException if the read times out
     * @throws IOException if an IO Exception occurs in the underlying socket logic
     */
    public int read(ByteBuffer buf, NioChannel socket, Selector selector, long readTimeout, boolean block) throws IOException {
        if ( SHARED && block ) {
            return blockingSelector.read(buf,socket,readTimeout);
        }
        SelectionKey key = null;
        int read = 0;
        boolean timedout = false;
        int keycount = 1; //assume we can write
        long time = System.currentTimeMillis(); //start the timeout timer
        try {
            while ( (!timedout) ) {
                int cnt = 0;
                if ( keycount > 0 ) { //only read if we were registered for a read
                    cnt = socket.read(buf);
                    if (cnt == -1) throw new EOFException();
                    read += cnt;
                    if (cnt > 0) continue; //read some more
                    if (cnt==0 && (read>0 || (!block) ) ) break; //we are done reading
                }
                if ( selector != null ) {//perform a blocking read
                    //register OP_WRITE to the selector
                    if (key==null) key = socket.getIOChannel().register(selector, SelectionKey.OP_READ);
                    else key.interestOps(SelectionKey.OP_READ);
                    if (readTimeout==0) {
                        timedout = (read==0);
                    } else if (readTimeout<0) {
                        keycount = selector.select();
                    } else {
                        keycount = selector.select(readTimeout);
                    }
                }
                if (readTimeout > 0 && (selector == null || keycount == 0) ) timedout = (System.currentTimeMillis()-time)>=readTimeout;
            }//while
            if ( timedout ) throw new SocketTimeoutException();
        } finally {
            if (key != null) {
                key.cancel();
                if (selector != null) selector.selectNow();//removes the key from this selector
            }
        }
        return read;
    }

    public void setMaxSelectors(int maxSelectors) {
        this.maxSelectors = maxSelectors;
    }

    public void setMaxSpareSelectors(int maxSpareSelectors) {
        this.maxSpareSelectors = maxSpareSelectors;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setSharedSelectorTimeout(long sharedSelectorTimeout) {
        this.sharedSelectorTimeout = sharedSelectorTimeout;
    }

    public int getMaxSelectors() {
        return maxSelectors;
    }

    public int getMaxSpareSelectors() {
        return maxSpareSelectors;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getSharedSelectorTimeout() {
        return sharedSelectorTimeout;
    }

    public ConcurrentLinkedQueue<Selector> getSelectors() {
        return selectors;
    }

    public AtomicInteger getSpare() {
        return spare;
    }
}
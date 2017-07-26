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

package org.apache.catalina.core;

import java.util.concurrent.Executor;

import org.apache.catalina.Container;
import org.apache.catalina.ContainerEvent;
import org.apache.catalina.ContainerListener;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.Server;
import org.apache.catalina.Service;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.ProtocolHandler;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.res.StringManager;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;

/**
 * <p>
 * A {@link LifecycleListener} that triggers the renewal of threads in Executor
 * pools when a {@link Context} is being stopped to avoid thread-local related
 * memory leaks.
 * </p>
 * <p>
 * Note : active threads will be renewed one by one when they come back to the
 * pool after executing their task, see
 * {@link org.apache.tomcat.util.threads.ThreadPoolExecutor}.afterExecute().
 * </p>
 *
 * This listener must be declared in server.xml to be active.
 *
 * 防止因 ThreadLocal 的存在, 而造成内存泄露
 * 在进行 Tomcat 热部署时, 工作线程是不会停止的, 而需要关闭 StandardContext 对应的 WebappClassLoader, 而 ThreadLocal.threadLocalMap 里面有存储了 由WebappClassLoader 加载出来的类, 所以有可能导致 WebappClassLoader 因被引用而不能被 GC, 最终导致内存泄露
 * (PS:  ThreadLocalMap 的生命长度与 Thread 一样, Tomcat中的工作线程池不因 StandardContext 的stop, 而销毁)
 * 见官网 : https://wiki.apache.org/tomcat/MemoryLeakProtection
 */
public class ThreadLocalLeakPreventionListener implements LifecycleListener,
        ContainerListener {

    private static final Log log =
        LogFactory.getLog(ThreadLocalLeakPreventionListener.class);

    private volatile boolean serverStopping = false;

    /**
     * The string manager for this package.
     */
    protected static final StringManager sm =
        StringManager.getManager(Constants.Package);

    /**
     * Listens for {@link LifecycleEvent} for the start of the {@link Server} to
     * initialize itself and then for after_stop events of each {@link Context}.
     */
    @Override
    public void lifecycleEvent(LifecycleEvent event) {
        try {
            Lifecycle lifecycle = event.getLifecycle();
            if (Lifecycle.AFTER_START_EVENT.equals(event.getType()) &&
                    lifecycle instanceof Server) {
                // when the server starts, we register ourself as listener for
                // all context
                // as well as container event listener so that we know when new
                // Context are deployed
                Server server = (Server) lifecycle;
                registerListenersForServer(server);
            }

            if (Lifecycle.BEFORE_STOP_EVENT.equals(event.getType()) &&
                    lifecycle instanceof Server) {
                // Server is shutting down, so thread pools will be shut down so
                // there is no need to clean the threads
                serverStopping = true;
            }

            if (Lifecycle.AFTER_STOP_EVENT.equals(event.getType()) &&
                    lifecycle instanceof Context) {
                /**
                 * 当应用 stop 的时候, 会出发 stop_event 事件, 被 ThrealLocalLeakPreventionListener 所接收到, 然后就会调用线程池的 contextStopping 方法进行现成的 renew 工作
                 * 在这一个 流程中, 如果当前配置了 renewThreadsWhenStoppingContext 的属性的话, 就直接 return
                 */
                stopIdleThreads((Context) lifecycle);           // 停止线程池中的线程
            }
        } catch (Exception e) {
            String msg =
                sm.getString(
                    "threadLocalLeakPreventionListener.lifecycleEvent.error",
                    event);
            log.error(msg, e);
        }
    }

    @Override
    public void containerEvent(ContainerEvent event) {
        try {
            String type = event.getType();
            if (Container.ADD_CHILD_EVENT.equals(type)) {
                processContainerAddChild(event.getContainer(),
                    (Container) event.getData());
            } else if (Container.REMOVE_CHILD_EVENT.equals(type)) {
                processContainerRemoveChild(event.getContainer(),
                    (Container) event.getData());
            }
        } catch (Exception e) {
            String msg =
                sm.getString(
                    "threadLocalLeakPreventionListener.containerEvent.error",
                    event);
            log.error(msg, e);
        }

    }

    private void registerListenersForServer(Server server) {
        for (Service service : server.findServices()) {
            Engine engine = (Engine) service.getContainer();
            engine.addContainerListener(this);
            registerListenersForEngine(engine);
        }

    }

    private void registerListenersForEngine(Engine engine) {
        for (Container hostContainer : engine.findChildren()) {
            Host host = (Host) hostContainer;
            host.addContainerListener(this);
            registerListenersForHost(host);
        }
    }

    private void registerListenersForHost(Host host) {
        for (Container contextContainer : host.findChildren()) {
            Context context = (Context) contextContainer;
            registerContextListener(context);
        }
    }

    private void registerContextListener(Context context) {
        context.addLifecycleListener(this);
    }

    protected void processContainerAddChild(Container parent, Container child) {
        if (log.isDebugEnabled())
            log.debug("Process addChild[parent=" + parent + ",child=" + child +
                "]");

        if (child instanceof Context) {
            registerContextListener((Context) child);
        } else if (child instanceof Engine) {
            registerListenersForEngine((Engine) child);
        } else if (child instanceof Host) {
            registerListenersForHost((Host) child);
        }

    }

    protected void processContainerRemoveChild(Container parent,
        Container child) {

        if (log.isDebugEnabled())
            log.debug("Process removeChild[parent=" + parent + ",child=" +
                child + "]");

        if (child instanceof Context) {
            Context context = (Context) child;
            context.removeLifecycleListener(this);
        } else if (child instanceof Host || child instanceof Engine) {
            child.removeContainerListener(this);
        }
    }

    /**
     * Updates each ThreadPoolExecutor with the current time, which is the time
     * when a context is being stopped.
     *
     * @param context
     *            the context being stopped, used to discover all the Connectors
     *            of its parent Service.
     */
    private void stopIdleThreads(Context context) {                                       // 下面的 ThreadLocal 保护操作很简单, 就是将 connector 里面 protocolHandler 对应的 executor 里面的线程全部关闭掉
        if (serverStopping) return;                 // 如果没有配置该属性, 直接 return

        if (context instanceof StandardContext &&
            !((StandardContext) context).getRenewThreadsWhenStoppingContext()) {            // 判断是否配置 renewThreadsWhenStoppingContext 属性
            log.debug("Not renewing threads when the context is stopping, "
                + "it is configured not to do it.");
            return;
        }

        Engine engine = (Engine) context.getParent().getParent();
        Service service = engine.getService();
        Connector[] connectors = service.findConnectors();
        if (connectors != null) {
            for (Connector connector : connectors) {
                ProtocolHandler handler = connector.getProtocolHandler();
                Executor executor = null;
                if (handler != null) {
                    executor = handler.getExecutor();
                }

                if (executor instanceof ThreadPoolExecutor) {
                    ThreadPoolExecutor threadPoolExecutor =
                        (ThreadPoolExecutor) executor;
                    threadPoolExecutor.contextStopping();       // 停止
                } else if (executor instanceof StandardThreadExecutor) {
                    StandardThreadExecutor stdThreadExecutor =
                        (StandardThreadExecutor) executor;
                    stdThreadExecutor.contextStopping();        // 停止
                }

            }
        }
    }
}

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
package org.apache.catalina.mapper;

import org.apache.catalina.Container;
import org.apache.catalina.ContainerEvent;
import org.apache.catalina.ContainerListener;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Service;
import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.Wrapper;
import org.apache.catalina.util.LifecycleMBeanBase;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.res.StringManager;


/**
 * Mapper listener.
 *
 * 参考资料
 * http://mp.weixin.qq.com/s?__biz=MzA4MTc3Nzk4NQ==&mid=2650076405&idx=1&sn=01fb0eb30c85744a874aa4894d9a45cf&chksm=878f90dbb0f819cdc8bbb5371f27bb7b835afa8e042bd0df880a05c5649f83ca2031ff34fe4a&mpshare=1&scene=23&srcid=06141Mk3xUVzfDCzh14X2hQx#rd
 * @author Remy Maucherat
 * @author Costin Manolache
 */
public class MapperListener extends LifecycleMBeanBase
        implements ContainerListener, LifecycleListener {


    private static final Log log = LogFactory.getLog(MapperListener.class);


    // ----------------------------------------------------- Instance Variables
    /**
     * Associated mapper.
     */
    private final Mapper mapper;

    /**
     * Associated service
     */
    private final Service service;


    /**
     * The string manager for this package.
     */
    private static final StringManager sm =
        StringManager.getManager(Constants.Package);

    /**
     * The domain (effectively the engine) this mapper is associated with
     */
    private final String domain = null;

    // ----------------------------------------------------------- Constructors


    /**
     * Create mapper listener.
     */
    public MapperListener(Mapper mapper, Service service) {
        this.mapper = mapper;
        this.service = service;
    }


    // ------------------------------------------------------- Lifecycle Methods

    @Override
    public void startInternal() throws LifecycleException {

        setState(LifecycleState.STARTING);

        // Find any components that have already been initialized since the
        // MBean listener won't be notified as those components will have
        // already registered their MBeans
        findDefaultHost();                                  // 获取对应的默认 Host

        Engine engine = (Engine) service.getContainer();
        addListeners(engine);                               // 给tomcat后端组件加上 lifecycle事件

        Container[] conHosts = engine.findChildren();
        for (Container conHost : conHosts) {
            Host host = (Host) conHost;
            if (!LifecycleState.NEW.equals(host.getState())) {
                // Registering the host will register the context and wrappers
                registerHost(host);                         // 注册现有的 Host和 Wrapper到 Mapper数据结构中
            }
        }
    }


    @Override
    public void stopInternal() throws LifecycleException {
        setState(LifecycleState.STOPPING);
    }


    @Override
    protected String getDomainInternal() {
        if (service instanceof LifecycleMBeanBase) {
            return ((LifecycleMBeanBase) service).getDomain();
        } else {
            return null;
        }
    }


    @Override
    protected String getObjectNameKeyProperties() {
        // Same as connector but Mapper rather than Connector
        return ("type=Mapper");
    }

    // --------------------------------------------- Container Listener methods

    @Override
    public void containerEvent(ContainerEvent event) {

        // Host, Context, Wrapper 的添加事件
        if (Container.ADD_CHILD_EVENT.equals(event.getType())) {
            Container child = (Container) event.getData();
            addListeners(child);
            // If child is started then it is too late for life-cycle listener
            // to register the child so register it here
            if (child.getState().isAvailable()) {
                if (child instanceof Host) {
                    registerHost((Host) child);
                } else if (child instanceof Context) {
                    registerContext((Context) child);
                } else if (child instanceof Wrapper) {
                    registerWrapper((Wrapper) child);
                }
            }
        } // Host, Context, Wrapper 的删除事件
        else if (Container.REMOVE_CHILD_EVENT.equals(event.getType())) {
            Container child = (Container) event.getData();
            removeListeners(child);
            // No need to unregister - life-cycle listener will handle this when
            // the child stops
        }  // Host的别名处理
        else if (Host.ADD_ALIAS_EVENT.equals(event.getType())) {
            // Handle dynamically adding host aliases
            mapper.addHostAlias(((Host) event.getSource()).getName(),
                    event.getData().toString());
        } else if (Host.REMOVE_ALIAS_EVENT.equals(event.getType())) {
            // Handle dynamically removing host aliases
            mapper.removeHostAlias(event.getData().toString());
        } // wrapper的mapping增加, 实际对应的就是 servlet的servlet-mapping
        else if (Wrapper.ADD_MAPPING_EVENT.equals(event.getType())) {
            // Handle dynamically adding wrappers
            Wrapper wrapper = (Wrapper) event.getSource();
            Context context = (Context) wrapper.getParent();
            String contextPath = context.getPath();
            if ("/".equals(contextPath)) {
                contextPath = "";
            }
            String version = ((Context) wrapper.getParent()).getWebappVersion();
            String hostName = context.getParent().getName();
            String wrapperName = wrapper.getName();
            String mapping = (String) event.getData();
            boolean jspWildCard = ("jsp".equals(wrapperName)
                    && mapping.endsWith("/*"));
            mapper.addWrapper(hostName, contextPath, version, mapping, wrapper,
                    jspWildCard, context.isResourceOnlyServlet(wrapperName));
        } // wrapper的mapping减少
        else if (Wrapper.REMOVE_MAPPING_EVENT.equals(event.getType())) {
            // Handle dynamically removing wrappers
            Wrapper wrapper = (Wrapper) event.getSource();

            String contextPath = ((Context) wrapper.getParent()).getPath();
            if ("/".equals(contextPath)) {
                contextPath = "";
            }
            String version = ((Context) wrapper.getParent()).getWebappVersion();
            String hostName = wrapper.getParent().getParent().getName();

            String mapping = (String) event.getData();

            mapper.removeWrapper(hostName, contextPath, version, mapping);
        } // welcome 文件的增删清空
        else if (Context.ADD_WELCOME_FILE_EVENT.equals(event.getType())) {
            // Handle dynamically adding welcome files
            Context context = (Context) event.getSource();

            String hostName = context.getParent().getName();

            String contextPath = context.getPath();
            if ("/".equals(contextPath)) {
                contextPath = "";
            }

            String welcomeFile = (String) event.getData();

            mapper.addWelcomeFile(hostName, contextPath,
                    context.getWebappVersion(), welcomeFile);
        } else if (Context.REMOVE_WELCOME_FILE_EVENT.equals(event.getType())) {
            // Handle dynamically removing welcome files
            Context context = (Context) event.getSource();

            String hostName = context.getParent().getName();

            String contextPath = context.getPath();
            if ("/".equals(contextPath)) {
                contextPath = "";
            }

            String welcomeFile = (String) event.getData();

            mapper.removeWelcomeFile(hostName, contextPath,
                    context.getWebappVersion(), welcomeFile);
        } else if (Context.CLEAR_WELCOME_FILES_EVENT.equals(event.getType())) {
            // Handle dynamically clearing welcome files
            Context context = (Context) event.getSource();

            String hostName = context.getParent().getName();

            String contextPath = context.getPath();
            if ("/".equals(contextPath)) {
                contextPath = "";
            }

            mapper.clearWelcomeFiles(hostName, contextPath,
                    context.getWebappVersion());
        }
    }


    // ------------------------------------------------------ Protected Methods
    // 参考 https://mp.weixin.qq.com/s?__biz=MzA4MTc3Nzk4NQ==&mid=2650076429&idx=1&sn=ee98c998f6dfe4ddd75254585eabf6f9&chksm=878f9123b0f81835790a2180be6bed47f794034f06a94f2c544581f60e0bdf388f231760f668&mpshare=1&scene=23&srcid=06144eHTZgNrSSWaWpQhPq2w#rd
    // 因为每个 Engine 下面有 n 个 Host, 我们进行 Mapping 路由的时候, 需要找一个默认的 Host 进行映射
    private void findDefaultHost() {

        Engine engine = (Engine) service.getContainer();        // 获取 Engine的配置
        /**
         * 当路由的时候, 发现当前 Engine 中没有多余的 Host, 就用这个 DefaultHost进行路由
         * 或者请求访问的时候, unknown host, 最终也是由这个 defaultHost 进行出面解决
         * 其次, 就是这个 JVMRoute 属性最关键了, 该属性之所以像上述的描述那样可以为唯一标识
         * 一个 tomcat的 servlet 实例, 其原因就是该 jvmroute 是在 Cluster 中参与了生成 sessionID 的算法
         *
         */
        // 获取默认的 defaultHost
        String defaultHost = engine.getDefaultHost();           // 这里的 defaultHost 就是在 server.xml 里面的 <Engine name="Catalina" defaultHost="localhost" />

        boolean found = false;

        // 进行校验
        if (defaultHost != null && defaultHost.length() >0) {
            Container[] containers = engine.findChildren();

            for (Container container : containers) {
                Host host = (Host) container;
                if (defaultHost.equalsIgnoreCase(host.getName())) {
                    found = true;
                    break;
                }

                String[] aliases = host.findAliases();
                for (String alias : aliases) {
                    if (defaultHost.equalsIgnoreCase(alias)) {
                        found = true;
                        break;
                    }
                }
            }
        }

        if(found) {     // 最终进行设置 host
            mapper.setDefaultHostName(defaultHost);
        } else {
            log.warn(sm.getString("mapperListener.unknownDefaultHost",
                    defaultHost, service));
        }
    }


    /**
     * Register host.
     */
    private void registerHost(Host host) {

        String[] aliases = host.findAliases();
        mapper.addHost(host.getName(), aliases, host);

        for (Container container : host.findChildren()) {
            if (container.getState().isAvailable()) {
                registerContext((Context) container);
            }
        }
        if(log.isDebugEnabled()) {
            log.debug(sm.getString("mapperListener.registerHost",
                    host.getName(), domain, service));
        }
    }


    /**
     * Unregister host.
     */
    private void unregisterHost(Host host) {

        String hostname = host.getName();

        mapper.removeHost(hostname);

        if(log.isDebugEnabled()) {
            log.debug(sm.getString("mapperListener.unregisterHost", hostname,
                    domain, service));
        }
    }


    /**
     * Unregister wrapper.
     */
    private void unregisterWrapper(Wrapper wrapper) {

        String contextPath = ((Context) wrapper.getParent()).getPath();
        String wrapperName = wrapper.getName();

        if ("/".equals(contextPath)) {
            contextPath = "";
        }
        String version = ((Context) wrapper.getParent()).getWebappVersion();
        String hostName = wrapper.getParent().getParent().getName();

        String[] mappings = wrapper.findMappings();

        for (String mapping : mappings) {
            mapper.removeWrapper(hostName, contextPath, version,  mapping);
        }

        if(log.isDebugEnabled()) {
            log.debug(sm.getString("mapperListener.unregisterWrapper",
                    wrapperName, contextPath, service));
        }
    }


    /**
     * Register context.
     */
    private void registerContext(Context context) {

        String contextPath = context.getPath();
        if ("/".equals(contextPath)) {
            contextPath = "";
        }
        Host host = (Host)context.getParent();

        WebResourceRoot resources = context.getResources();
        String[] welcomeFiles = context.findWelcomeFiles();

        mapper.addContextVersion(host.getName(), host, contextPath,
                context.getWebappVersion(), context, welcomeFiles, resources);

        for (Container container : context.findChildren()) {
            registerWrapper((Wrapper) container);
        }

        if(log.isDebugEnabled()) {
            log.debug(sm.getString("mapperListener.registerContext",
                    contextPath, service));
        }
    }


    /**
     * Unregister context.
     */
    private void unregisterContext(Context context) {

        // Don't un-map a context that is paused
        if (context.getPaused()){
            return;
        }

        String contextPath = context.getPath();
        if ("/".equals(contextPath)) {
            contextPath = "";
        }
        String hostName = context.getParent().getName();

        if(log.isDebugEnabled()) {
            log.debug(sm.getString("mapperListener.unregisterContext",
                    contextPath, service));
        }

        mapper.removeContextVersion(context, hostName, contextPath,
                context.getWebappVersion());
    }


    /**
     * Register wrapper.
     */
    private void registerWrapper(Wrapper wrapper) {

        String wrapperName = wrapper.getName();
        Context context = (Context) wrapper.getParent();
        String contextPath = context.getPath();
        if ("/".equals(contextPath)) {
            contextPath = "";
        }
        String version = ((Context) wrapper.getParent()).getWebappVersion();
        String hostName = context.getParent().getName();

        String[] mappings = wrapper.findMappings();

        for (String mapping : mappings) {
            boolean jspWildCard = (wrapperName.equals("jsp")
                                   && mapping.endsWith("/*"));
            mapper.addWrapper(hostName, contextPath, version, mapping, wrapper,
                              jspWildCard,
                              context.isResourceOnlyServlet(wrapperName));
        }

        if(log.isDebugEnabled()) {
            log.debug(sm.getString("mapperListener.registerWrapper",
                    wrapperName, contextPath, service));
        }
    }

    @Override
    public void lifecycleEvent(LifecycleEvent event) {
        if (event.getType().equals(Lifecycle.AFTER_START_EVENT)) {
            Object obj = event.getSource();
            if (obj instanceof Wrapper) {
                Wrapper w = (Wrapper) obj;
                // Only if the Context has started. If it has not, then it will
                // have its own "after_start" event later.
                if (w.getParent().getState().isAvailable()) {
                    registerWrapper(w);
                }
            } else if (obj instanceof Context) {
                Context c = (Context) obj;
                // Only if the Host has started. If it has not, then it will
                // have its own "after_start" event later.
                if (c.getParent().getState().isAvailable()) {
                    registerContext(c);
                }
            } else if (obj instanceof Host) {
                registerHost((Host) obj);
            }
        } else if (event.getType().equals(Lifecycle.BEFORE_STOP_EVENT)) {
            Object obj = event.getSource();
            if (obj instanceof Wrapper) {
                unregisterWrapper((Wrapper) obj);
            } else if (obj instanceof Context) {
                Context c = (Context) obj;
                // Only unregister if not paused. If paused, need to keep
                // registration in place to prevent 404's during reload
                if (!c.getPaused()) {
                    unregisterContext(c);
                }
            } else if (obj instanceof Host) {
                unregisterHost((Host) obj);
            }
        }
    }


    /**
     * Add this mapper to the container and all child containers
     *
     * @param container
     */
    private void addListeners(Container container) {
        container.addContainerListener(this);
        container.addLifecycleListener(this);
        for (Container child : container.findChildren()) {
            addListeners(child);
        }
    }


    /**
     * Remove this mapper from the container and all child containers
     *
     * @param container
     */
    private void removeListeners(Container container) {
        container.removeContainerListener(this);
        container.removeLifecycleListener(this);
        for (Container child : container.findChildren()) {
            removeListeners(child);
        }
    }

    @Override
    public String toString() {
        return "MapperListener{" +
                "mapper=" + mapper +
                ", domain='" + domain + '\'' +
                '}';
    }
}

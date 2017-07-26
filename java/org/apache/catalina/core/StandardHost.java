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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

import javax.management.ObjectName;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Globals;
import org.apache.catalina.Host;
import org.apache.catalina.JmxEnabled;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.Valve;
import org.apache.catalina.loader.WebappClassLoader;
import org.apache.tomcat.util.ExceptionUtils;

/**
 * Standard implementation of the <b>Host</b> interface.  Each
 * child container must be a Context implementation to process the
 * requests directed to a particular web application.
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 */
public class StandardHost extends ContainerBase implements Host {

    private static final org.apache.juli.logging.Log log=
        org.apache.juli.logging.LogFactory.getLog( StandardHost.class );

    // ----------------------------------------------------------- Constructors


    /**
     * Create a new StandardHost component with the default basic Valve.
     * 初始化步骤
     * 1. 初始化 Valve 处理管道 Pipeline, 并且加入 对应的 StandardHostValve(StandardHostValve 主要是通过 org.apache.catalina.connector.Request 里面的信息, 进行路由到对应的 StandardContext, 并且将当前线程的 ContextClassLoader 设置为 Context 锁对应的 WebappClassLoader)
     */
    public StandardHost() {

        super();
        pipeline.setBasic(new StandardHostValve());                     // 初始化 pipeline 及 对应的 StandardHostValve

    }


    // ----------------------------------------------------- Instance Variables


    /**
     * The set of aliases for this Host.
     */
    // 当前 host 对象的别名的数组
    private String[] aliases = new String[0];

    // 锁
    private final Object aliasesLock = new Object();


    /**
     * The application root for this Host.
     */
    // 默认的 app 的路径是 tomcat 根路径下的 webapps 文件夹
    private String appBase = "webapps";
    private volatile File appBaseFile = null;   // 对默认文件夹的引用

    /**
     * The XML root for this Host.
     */
    // host 的xml配置文件所在的目录 (这个配置文件不一定存在)
    private String xmlBase = null;

    /**
     * host's default config path
     */
    // host 的默认配置文件的路径 ${catalina.base}/config/${engineName}/${hostname}
    // 我们在 ${catalina.base}/config/server.xml 里面发现 默认的Engine 就是 Catalina
    private volatile File hostConfigBase = null;

    /**
     * The auto deploy flag for this Host.
     */
    // 默认自动部署
    private boolean autoDeploy = true;


    /**
     * The Java class name of the default context configuration class
     * for deployed web applications.
     */
    // 默认的 StandardContext 的自动部署监听器
    private String configClass =
        "org.apache.catalina.startup.ContextConfig";


    /**
     * The Java class name of the default Context implementation class for
     * deployed web applications.
     */
    // 默认的 StandardContext
    private String contextClass =
        "org.apache.catalina.core.StandardContext";


    /**
     * The deploy on startup flag for this Host.
     */
    // 默认在启动的时候就部署项目
    private boolean deployOnStartup = true;


    /**
     * deploy Context XML config files property.
     */
    private boolean deployXML = !Globals.IS_SECURITY_ENABLED;


    /**
     * Should XML files be copied to
     * $CATALINA_BASE/conf/&lt;engine&gt;/&lt;host&gt; by default when
     * a web application is deployed?
     */
    private boolean copyXML = false;


    /**
     * The Java class name of the default error reporter implementation class
     * for deployed web applications.
     */
    // 默认的记录处理错误欣欣的 Valve
    private String errorReportValveClass =
        "org.apache.catalina.valves.ErrorReportValve";


    /**
     * Unpack WARs property.
     */
    // 默认支持解压包
    private boolean unpackWARs = true;


    /**
     * Work Directory base for applications.
     */
    // app 的工作目录
    private String workDir = null;


    /**
     * Should we create directories upon startup for appBase and xmlBase
     */
    private boolean createDirs = true;


    /**
     * Track the class loaders for the child web applications so memory leaks
     * can be detected.
     */
    // 追踪每个 ClassLoader 用于定位内存泄露
    private final Map<ClassLoader, String> childClassLoaders =
            new WeakHashMap<>();


    /**
     * Any file or directory in {@link #appBase} that this pattern matches will
     * be ignored by the automatic deployment process (both
     * {@link #deployOnStartup} and {@link #autoDeploy}).
     */
    private Pattern deployIgnore = null;


    private boolean undeployOldVersions = false;


    // ------------------------------------------------------------- Properties

    @Override
    public boolean getUndeployOldVersions() {
        return undeployOldVersions;
    }


    @Override
    public void setUndeployOldVersions(boolean undeployOldVersions) {
        this.undeployOldVersions = undeployOldVersions;
    }


    @Override
    public ExecutorService getStartStopExecutor() {
        return startStopExecutor;
    }


    /**
     * Return the application root for this Host.  This can be an absolute
     * pathname, a relative pathname, or a URL.
     */
    @Override
    public String getAppBase() {
        return (this.appBase);
    }


    /**
     * ({@inheritDoc}
     */
    @Override
    public File getAppBaseFile() {

        if (appBaseFile != null) {
            return appBaseFile;
        }

        File file = new File(getAppBase());

        // If not absolute, make it absolute
        if (!file.isAbsolute()) {
            file = new File(getCatalinaBase(), file.getPath());
        }

        // Make it canonical if possible
        try {
            file = file.getCanonicalFile();
        } catch (IOException ioe) {
            // Ignore
        }

        this.appBaseFile = file;
        return file;
    }


    /**
     * Set the application root for this Host.  This can be an absolute
     * pathname, a relative pathname, or a URL.
     *
     * @param appBase The new application root
     */
    @Override
    public void setAppBase(String appBase) {

        String oldAppBase = this.appBase;
        this.appBase = appBase;
        support.firePropertyChange("appBase", oldAppBase, this.appBase);
        this.appBaseFile = null;
    }


    /**
     * Return the XML root for this Host.  This can be an absolute
     * pathname, a relative pathname, or a URL.
     * If null, defaults to
     * ${catalina.base}/conf/&lt;engine name&gt;/&lt;host name&gt; directory
     */
    @Override
    public String getXmlBase() {

        return (this.xmlBase);

    }


    /**
     * Set the Xml root for this Host.  This can be an absolute
     * pathname, a relative pathname, or a URL.
     * If null, defaults to
     * ${catalina.base}/conf/&lt;engine name&gt;/&lt;host name&gt; directory
     *
     * @param xmlBase The new XML root
     */
    @Override
    public void setXmlBase(String xmlBase) {

        String oldXmlBase = this.xmlBase;
        this.xmlBase = xmlBase;
        support.firePropertyChange("xmlBase", oldXmlBase, this.xmlBase);

    }


    /**
     * ({@inheritDoc}
     */
    @Override
    public File getConfigBaseFile() {
        if (hostConfigBase != null) {
            return hostConfigBase;
        }
        String path = null;
        if (getXmlBase()!=null) {
            path = getXmlBase();
        } else {
            StringBuilder xmlDir = new StringBuilder("conf");
            Container parent = getParent();
            if (parent instanceof Engine) {
                xmlDir.append('/');
                xmlDir.append(parent.getName());
            }
            xmlDir.append('/');
            xmlDir.append(getName());
            path = xmlDir.toString();
        }
        File file = new File(path);
        if (!file.isAbsolute())
            file = new File(getCatalinaBase(), path);
        try {
            file = file.getCanonicalFile();
        } catch (IOException e) {// ignore
        }
        this.hostConfigBase = file;
        return file;
    }


    /**
     * Returns true if the Host will attempt to create directories for appBase and xmlBase
     * unless they already exist.
     */
    @Override
    public boolean getCreateDirs() {
        return createDirs;
    }

    /**
     * Set to true if the Host should attempt to create directories for xmlBase and appBase upon startup
     * @param createDirs
     */
    @Override
    public void setCreateDirs(boolean createDirs) {
        this.createDirs = createDirs;
    }

    /**
     * Return the value of the auto deploy flag.  If true, it indicates that
     * this host's child webapps will be dynamically deployed.
     */
    @Override
    public boolean getAutoDeploy() {

        return (this.autoDeploy);

    }


    /**
     * Set the auto deploy flag value for this host.
     *
     * @param autoDeploy The new auto deploy flag
     */
    @Override
    public void setAutoDeploy(boolean autoDeploy) {

        boolean oldAutoDeploy = this.autoDeploy;
        this.autoDeploy = autoDeploy;
        support.firePropertyChange("autoDeploy", oldAutoDeploy,
                                   this.autoDeploy);

    }


    /**
     * Return the Java class name of the context configuration class
     * for new web applications.
     */
    @Override
    public String getConfigClass() {

        return (this.configClass);

    }


    /**
     * Set the Java class name of the context configuration class
     * for new web applications.
     *
     * @param configClass The new context configuration class
     */
    @Override
    public void setConfigClass(String configClass) {

        String oldConfigClass = this.configClass;
        this.configClass = configClass;
        support.firePropertyChange("configClass",
                                   oldConfigClass, this.configClass);

    }


    /**
     * Return the Java class name of the Context implementation class
     * for new web applications.
     */
    public String getContextClass() {

        return (this.contextClass);

    }


    /**
     * Set the Java class name of the Context implementation class
     * for new web applications.
     *
     * @param contextClass The new context implementation class
     */
    public void setContextClass(String contextClass) {

        String oldContextClass = this.contextClass;
        this.contextClass = contextClass;
        support.firePropertyChange("contextClass",
                                   oldContextClass, this.contextClass);

    }


    /**
     * Return the value of the deploy on startup flag.  If true, it indicates
     * that this host's child webapps should be discovered and automatically
     * deployed at startup time.
     */
    @Override
    public boolean getDeployOnStartup() {

        return (this.deployOnStartup);

    }


    /**
     * Set the deploy on startup flag value for this host.
     *
     * @param deployOnStartup The new deploy on startup flag
     */
    @Override
    public void setDeployOnStartup(boolean deployOnStartup) {

        boolean oldDeployOnStartup = this.deployOnStartup;
        this.deployOnStartup = deployOnStartup;
        support.firePropertyChange("deployOnStartup", oldDeployOnStartup,
                                   this.deployOnStartup);

    }


    /**
     * Deploy XML Context config files flag accessor.
     */
    public boolean isDeployXML() {

        return deployXML;

    }


    /**
     * Deploy XML Context config files flag mutator.
     */
    public void setDeployXML(boolean deployXML) {

        this.deployXML = deployXML;

    }


    /**
     * Return the copy XML config file flag for this component.
     */
    public boolean isCopyXML() {

        return this.copyXML;

    }


    /**
     * Set the copy XML config file flag for this component.
     *
     * @param copyXML The new copy XML flag
     */
    public void setCopyXML(boolean copyXML) {

        this.copyXML = copyXML;

    }


    /**
     * Return the Java class name of the error report valve class
     * for new web applications.
     */
    public String getErrorReportValveClass() {

        return (this.errorReportValveClass);

    }


    /**
     * Set the Java class name of the error report valve class
     * for new web applications.
     *
     * @param errorReportValveClass The new error report valve class
     */
    public void setErrorReportValveClass(String errorReportValveClass) {

        String oldErrorReportValveClassClass = this.errorReportValveClass;
        this.errorReportValveClass = errorReportValveClass;
        support.firePropertyChange("errorReportValveClass",
                                   oldErrorReportValveClassClass,
                                   this.errorReportValveClass);

    }


    /**
     * Return the canonical, fully qualified, name of the virtual host
     * this Container represents.
     */
    @Override
    public String getName() {

        return (name);

    }


    /**
     * Set the canonical, fully qualified, name of the virtual host
     * this Container represents.
     *
     * @param name Virtual host name
     *
     * @exception IllegalArgumentException if name is null
     */
    @Override
    public void setName(String name) {

        if (name == null)
            throw new IllegalArgumentException
                (sm.getString("standardHost.nullName"));

        name = name.toLowerCase(Locale.ENGLISH);      // Internally all names are lower case

        String oldName = this.name;
        this.name = name;
        support.firePropertyChange("name", oldName, this.name);

    }


    /**
     * Unpack WARs flag accessor.
     */
    public boolean isUnpackWARs() {

        return (unpackWARs);

    }


    /**
     * Unpack WARs flag mutator.
     */
    public void setUnpackWARs(boolean unpackWARs) {

        this.unpackWARs = unpackWARs;

    }


    /**
     * Host work directory base.
     */
    public String getWorkDir() {

        return (workDir);
    }


    /**
     * Host work directory base.
     */
    public void setWorkDir(String workDir) {

        this.workDir = workDir;
    }


    /**
     * Return the regular expression that defines the files and directories in
     * the host's {@link #getAppBase} that will be ignored by the automatic
     * deployment process.
     */
    @Override
    public String getDeployIgnore() {
        if (deployIgnore == null) {
            return null;
        }
        return this.deployIgnore.toString();
    }


    /**
     * Return the compiled regular expression that defines the files and
     * directories in the host's {@link #getAppBase} that will be ignored by the
     * automatic deployment process.
     */
    @Override
    public Pattern getDeployIgnorePattern() {
        return this.deployIgnore;
    }


    /**
     * Set the regular expression that defines the files and directories in
     * the host's {@link #getAppBase} that will be ignored by the automatic
     * deployment process.
     */
    @Override
    public void setDeployIgnore(String deployIgnore) {
        String oldDeployIgnore;
        if (this.deployIgnore == null) {
            oldDeployIgnore = null;
        } else {
            oldDeployIgnore = this.deployIgnore.toString();
        }
        if (deployIgnore == null) {
            this.deployIgnore = null;
        } else {
            this.deployIgnore = Pattern.compile(deployIgnore);
        }
        support.firePropertyChange("deployIgnore",
                                   oldDeployIgnore,
                                   deployIgnore);
    }


    // --------------------------------------------------------- Public Methods


    /**
     * Add an alias name that should be mapped to this same Host.
     *
     * @param alias The alias to be added
     */
    @Override
    public void addAlias(String alias) {

        alias = alias.toLowerCase(Locale.ENGLISH);

        synchronized (aliasesLock) {
            // Skip duplicate aliases
            for (int i = 0; i < aliases.length; i++) {
                if (aliases[i].equals(alias))
                    return;
            }
            // Add this alias to the list
            String newAliases[] = new String[aliases.length + 1];
            for (int i = 0; i < aliases.length; i++)
                newAliases[i] = aliases[i];
            newAliases[aliases.length] = alias;
            aliases = newAliases;
        }
        // Inform interested listeners
        fireContainerEvent(ADD_ALIAS_EVENT, alias);

    }


    /**
     * Add a child Container, only if the proposed child is an implementation
     * of Context.
     *
     * @param child Child container to be added
     */
    @Override
    public void addChild(Container child) {

        child.addLifecycleListener(new MemoryLeakTrackingListener());   // 给StandardHost 添加 内存泄露监听器, 其实就是一个 WeakHashMap 来存储每个 StandardContext 对应的 ClassLoader, 在重启后, 监听, classLoader 是否还存在

        if (!(child instanceof Context))
            throw new IllegalArgumentException
                (sm.getString("standardHost.notContext"));
        super.addChild(child);

    }


    /**
     * Used to ensure the regardless of {@link Context} implementation, a record
     * is kept of the class loader used every time a context starts.
     *
     * Tomcat 内存泄露监听器, 其实就是 一个 StandardContext 对应一个 WebappClassloader
     * 在 StandardContext 重启后, 先前的 WebappClassloader 是否已经被 gc 回收掉来判断
     *
     * ClassLoader 何时被 GC:
     *      classloader及加载的class类在没有实例引用的情况下
     * 参考资料
     * https://stackoverflow.com/questions/2344964/when-and-how-is-a-java-classloader-marked-for-garbage-collection
     *
     * MemoryLeakTrackingListener 归属于 StandardContext
     * 其用 WeakHashMap 装载 WebAppClassLoader, 等关闭 StandardContext 之后, 在看看 与之对应的 WebappClassLoader 是否存活, 若还存在, 则说明 WebappClassLoader 没有没 GC, 存在 Perm 区域内存泄露
     * 见 StandardHost.findReloadedContextMemoryLeaks()
     */
    private class MemoryLeakTrackingListener implements LifecycleListener {
        @Override
        public void lifecycleEvent(LifecycleEvent event) {
            if (event.getType().equals(Lifecycle.AFTER_START_EVENT)) {
                if (event.getSource() instanceof Context) {
                    Context context = ((Context) event.getSource());
                    childClassLoaders.put(context.getLoader().getClassLoader(),
                            context.getServletContext().getContextPath());
                }
            }
        }
    }


    /**
     * Attempt to identify the contexts that have a class loader memory leak.
     * This is usually triggered on context reload. Note: This method attempts
     * to force a full garbage collection. This should be used with extreme
     * caution on a production system.
     */
    public String[] findReloadedContextMemoryLeaks() {

        System.gc();

        List<String> result = new ArrayList<>();

        for (Map.Entry<ClassLoader, String> entry :
                childClassLoaders.entrySet()) {
            ClassLoader cl = entry.getKey();
            if (cl instanceof WebappClassLoader) {
                if (!((WebappClassLoader) cl).isStarted()) {
                    result.add(entry.getValue());
                }
            }
        }

        return result.toArray(new String[result.size()]);
    }

    /**
     * Return the set of alias names for this Host.  If none are defined,
     * a zero length array is returned.
     */
    @Override
    public String[] findAliases() {

        synchronized (aliasesLock) {
            return (this.aliases);
        }

    }


    /**
     * Remove the specified alias name from the aliases for this Host.
     *
     * @param alias Alias name to be removed
     */
    @Override
    public void removeAlias(String alias) {

        alias = alias.toLowerCase(Locale.ENGLISH);

        synchronized (aliasesLock) {

            // Make sure this alias is currently present
            int n = -1;
            for (int i = 0; i < aliases.length; i++) {
                if (aliases[i].equals(alias)) {
                    n = i;
                    break;
                }
            }
            if (n < 0)
                return;

            // Remove the specified alias
            int j = 0;
            String results[] = new String[aliases.length - 1];
            for (int i = 0; i < aliases.length; i++) {
                if (i != n)
                    results[j++] = aliases[i];
            }
            aliases = results;

        }

        // Inform interested listeners
        fireContainerEvent(REMOVE_ALIAS_EVENT, alias);

    }


    /**
     * Return a String representation of this component.
     */
    @Override
    public String toString() {

        StringBuilder sb = new StringBuilder();
        if (getParent() != null) {
            sb.append(getParent().toString());
            sb.append(".");
        }
        sb.append("StandardHost[");
        sb.append(getName());
        sb.append("]");
        return (sb.toString());

    }

    /**
     * Start this component and implement the requirements
     * of {@link org.apache.catalina.util.LifecycleBase#startInternal()}.
     *
     * @exception LifecycleException if this component detects a fatal error
     *  that prevents this component from being used
     */
    /**
     * 操作步骤
     * 1. 将 ErrorReportValve 加入其对应的 pipeline (ErrorReportValve 主要是对服务器的一次啊错误进行处理, 错误的信息加载 Response 里面)
     * 2. 调用父类 ContainerBase 的 startInternal 方法 进行启动一些公共属性
     */
    @Override
    protected synchronized void startInternal() throws LifecycleException {

        // Set error report valve
        String errorValve = getErrorReportValveClass();
        if ((errorValve != null) && (!errorValve.equals(""))) {
            try {
                boolean found = false;
                Valve[] valves = getPipeline().getValves();
                for (Valve valve : valves) {
                    if (errorValve.equals(valve.getClass().getName())) {
                        found = true;
                        break;
                    }
                }
                if(!found) {                                        // 初始化 错误报告的 valve ErrorReportValve
                    Valve valve =
                        (Valve) Class.forName(errorValve).newInstance();
                    getPipeline().addValve(valve);
                }
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error(sm.getString(
                        "standardHost.invalidErrorReportValveClass",
                        errorValve), t);
            }
        }
        super.startInternal();                                     // 调用父类 ContainerBase 的 startInternal 方法
    }


    // -------------------- JMX  --------------------
    /**
      * Return the MBean Names of the Valves associated with this Host
      *
      * @exception Exception if an MBean cannot be created or registered
      */
     public String [] getValveNames() throws Exception {
         Valve [] valves = this.getPipeline().getValves();
         String [] mbeanNames = new String[valves.length];
         for (int i = 0; i < valves.length; i++) {
             if (valves[i] instanceof JmxEnabled) {
                 ObjectName oname = ((JmxEnabled) valves[i]).getObjectName();
                 if (oname != null) {
                     mbeanNames[i] = oname.toString();
                 }
             }
         }

         return mbeanNames;

     }

    public String[] getAliases() {
        synchronized (aliasesLock) {
            return aliases;
        }
    }

    @Override
    protected String getObjectNameKeyProperties() {

        StringBuilder keyProperties = new StringBuilder("type=Host");
        keyProperties.append(getMBeanKeyProperties());

        return keyProperties.toString();
    }

}

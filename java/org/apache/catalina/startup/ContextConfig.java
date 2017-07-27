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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.SessionCookieConfig;
import javax.servlet.annotation.HandlesTypes;

import org.apache.catalina.Authenticator;
import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Globals;
import org.apache.catalina.Host;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.Pipeline;
import org.apache.catalina.Server;
import org.apache.catalina.Service;
import org.apache.catalina.Valve;
import org.apache.catalina.WebResource;
import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.Wrapper;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.util.ContextName;
import org.apache.catalina.util.Introspection;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.log4j.Logger;
import org.apache.tomcat.JarScanType;
import org.apache.tomcat.JarScanner;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.bcel.classfile.AnnotationElementValue;
import org.apache.tomcat.util.bcel.classfile.AnnotationEntry;
import org.apache.tomcat.util.bcel.classfile.ArrayElementValue;
import org.apache.tomcat.util.bcel.classfile.ClassFormatException;
import org.apache.tomcat.util.bcel.classfile.ClassParser;
import org.apache.tomcat.util.bcel.classfile.ElementValue;
import org.apache.tomcat.util.bcel.classfile.ElementValuePair;
import org.apache.tomcat.util.bcel.classfile.JavaClass;
import org.apache.tomcat.util.descriptor.XmlErrorHandler;
import org.apache.tomcat.util.descriptor.web.ApplicationListener;
import org.apache.tomcat.util.descriptor.web.ContextEjb;
import org.apache.tomcat.util.descriptor.web.ContextEnvironment;
import org.apache.tomcat.util.descriptor.web.ContextLocalEjb;
import org.apache.tomcat.util.descriptor.web.ContextResource;
import org.apache.tomcat.util.descriptor.web.ContextResourceEnvRef;
import org.apache.tomcat.util.descriptor.web.ContextService;
import org.apache.tomcat.util.descriptor.web.ErrorPage;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.util.descriptor.web.FragmentJarScannerCallback;
import org.apache.tomcat.util.descriptor.web.JspPropertyGroup;
import org.apache.tomcat.util.descriptor.web.LoginConfig;
import org.apache.tomcat.util.descriptor.web.MessageDestinationRef;
import org.apache.tomcat.util.descriptor.web.MultipartDef;
import org.apache.tomcat.util.descriptor.web.SecurityConstraint;
import org.apache.tomcat.util.descriptor.web.SecurityRoleRef;
import org.apache.tomcat.util.descriptor.web.ServletDef;
import org.apache.tomcat.util.descriptor.web.SessionConfig;
import org.apache.tomcat.util.descriptor.web.WebXml;
import org.apache.tomcat.util.descriptor.web.WebXmlParser;
import org.apache.tomcat.util.digester.Digester;
import org.apache.tomcat.util.digester.RuleSet;
import org.apache.tomcat.util.res.StringManager;
import org.apache.tomcat.util.scan.Jar;
import org.apache.tomcat.util.scan.JarFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

/**
 * Startup event listener for a <b>Context</b> that configures the properties
 * of that Context, and the associated defined servlets.
 *
 * 主要工作: 解析 web.xml中的 servlet, 请求映射, Fil等相关配置
 * 主要功能的 :
 *  1: 组装 web.xml 的解析器 WebXmlParser
 *  2: 根据 Host的 appBase 以及 Context的 docBase 计算 docBase 的绝对路径
 *  3. 扫描 web.xml 文件, 在遇到全局 web.xml 或 host 层面的 web.xml, 则应用层面的 web.xml 的属性能覆盖上面两个, 这里面的知识点非常多, 通过 SPI 机制加载 ServletContainerInitializer, 并且将它们 set 到对应的 StandardContext
 *  4. 解析应用程序注解配置 主要是 (listener, Filter, Servlet类的, 最后会将这些资源信息加入到 StandardContext.NamingResourcesImpl 里面, 在实例化 Servlet/Filter/Listener 时会用到)
 * @author Craig R. McClanahan
 */
public class ContextConfig implements LifecycleListener {
    public Logger logger4j = Logger.getLogger(ContextConfig.class);
    private static final Log log = LogFactory.getLog( ContextConfig.class );


    /**
     * The string resources for this package.
     */
    protected static final StringManager sm =
        StringManager.getManager(Constants.Package);


    protected static final LoginConfig DUMMY_LOGIN_CONFIG =
        new LoginConfig("NONE", null, null, null);


    /**
     * The set of Authenticators that we know how to configure.  The key is
     * the name of the implemented authentication method, and the value is
     * the fully qualified Java class name of the corresponding Valve.
     */
    protected static final Properties authenticators;

    static {
        // Load our mapping properties for the standard authenticators
        InputStream is =
                ContextConfig.class.getClassLoader().getResourceAsStream(
                    "org/apache/catalina/startup/Authenticators.properties");
        Properties props = null;
        props = new Properties();
        if (is != null) {
            try {
                props.load(is);
            } catch (IOException e) {
                props = null;
            }
        }
        authenticators = props;

    }

    /**
     * Deployment count.
     */
    protected static long deploymentCount = 0L;


    /**
     * Cache of default web.xml fragments per Host
     */
    protected static final Map<Host,DefaultWebXmlCacheEntry> hostWebXmlCache =
            new ConcurrentHashMap<>();


    // ----------------------------------------------------- Instance Variables
    /**
     * Custom mappings of login methods to authenticators
     */
    protected Map<String,Authenticator> customAuthenticators;


    /**
     * The Context we are associated with.
     */
    protected Context context = null;


    /**
     * The default web application's deployment descriptor location.
     */
    protected String defaultWebXml = null;


    /**
     * Track any fatal errors during startup configuration processing.
     */
    protected boolean ok = false;


    /**
     * Original docBase.
     */
    protected String originalDocBase = null;


    /**
     * Anti-locking docBase. It is a path to a copy of the web application
     * in the java.io.tmpdir directory. This path is always an absolute one.
     */
    private File antiLockingDocBase = null;


    /**
     * Map of ServletContainerInitializer to classes they expressed interest in.
     */
    protected final Map<ServletContainerInitializer, Set<Class<?>>> initializerClassMap =
            new LinkedHashMap<>();

    /**
     * Map of Types to ServletContainerInitializer that are interested in those
     * types.
     */
    protected final Map<Class<?>, Set<ServletContainerInitializer>> typeInitializerMap =
            new HashMap<>();

    /**
     * Cache of JavaClass objects (byte code) by fully qualified class name.
     * Only populated if it is necessary to scan the super types and interfaces
     * as part of the processing for {@link HandlesTypes}.
     */
    protected final Map<String,JavaClassCacheEntry> javaClassCache =
            new HashMap<>();

    /**
     * Flag that indicates if at least one {@link HandlesTypes} entry is present
     * that represents an annotation.
     */
    protected boolean handlesTypesAnnotations = false;

    /**
     * Flag that indicates if at least one {@link HandlesTypes} entry is present
     * that represents a non-annotation.
     */
    protected boolean handlesTypesNonAnnotations = false;

    private WebXmlParser webXmlParser;

    // ------------------------------------------------------------- Properties
    /**
     * Return the location of the default deployment descriptor
     */
    public String getDefaultWebXml() {
        if( defaultWebXml == null ) {
            defaultWebXml=Constants.DefaultWebXml;
        }

        return (this.defaultWebXml);

    }


    /**
     * Set the location of the default deployment descriptor
     *
     * @param path Absolute/relative path to the default web.xml
     */
    public void setDefaultWebXml(String path) {

        this.defaultWebXml = path;

    }


    /**
     * Sets custom mappings of login methods to authenticators.
     *
     * @param customAuthenticators Custom mappings of login methods to
     * authenticators
     */
    public void setCustomAuthenticators(
            Map<String,Authenticator> customAuthenticators) {
        this.customAuthenticators = customAuthenticators;
    }


    // --------------------------------------------------------- Public Methods


    /**
     * Process events for an associated Context.
     *
     * @param event The lifecycle event that has occurred
     */
    @Override
    public void lifecycleEvent(LifecycleEvent event) {
        logger4j.info("event:" + event);
        // Identify the context we are associated with
        try {
            context = (Context) event.getLifecycle();
        } catch (ClassCastException e) {
            log.error(sm.getString("contextConfig.cce", event.getLifecycle()), e);
            return;
        }

        // Process the event that has occurred
        // 在 StandardContext 进行 startInitInternal 时会出发 Lifecycle.CONFIGURE_START_EVENT 事件, ContextConfig 会进行相应的处理
        if (event.getType().equals(Lifecycle.CONFIGURE_START_EVENT)) {
            configureStart();
        } else if (event.getType().equals(Lifecycle.BEFORE_START_EVENT)) {
            beforeStart();
        } else if (event.getType().equals(Lifecycle.AFTER_START_EVENT)) {
            // Restore docBase for management tools
            if (originalDocBase != null) {
                context.setDocBase(originalDocBase);
            }
        } else if (event.getType().equals(Lifecycle.CONFIGURE_STOP_EVENT)) {
            configureStop();
        } else if (event.getType().equals(Lifecycle.AFTER_INIT_EVENT)) {
            init();
        } else if (event.getType().equals(Lifecycle.AFTER_DESTROY_EVENT)) {
            destroy();
        }

    }


    // -------------------------------------------------------- protected Methods


    /**
     * Process the application classes annotations, if it exists.
     */
    protected void applicationAnnotationsConfig() {

        long t1=System.currentTimeMillis();

        WebAnnotationSet.loadApplicationAnnotations(context);

        long t2=System.currentTimeMillis();
        if (context instanceof StandardContext) {
            ((StandardContext) context).setStartupTime(t2-t1+
                    ((StandardContext) context).getStartupTime());
        }
    }


    /**
     * Set up an Authenticator automatically if required, and one has not
     * already been configured.
     */
    protected void authenticatorConfig() {

        LoginConfig loginConfig = context.getLoginConfig();

        SecurityConstraint constraints[] = context.findConstraints();
        if (context.getIgnoreAnnotations() &&
                (constraints == null || constraints.length ==0) &&
                !context.getPreemptiveAuthentication())  {
            return;
        } else {
            if (loginConfig == null) {
                // Not metadata-complete or security constraints present, need
                // an authenticator to support @ServletSecurity annotations
                // and/or constraints
                loginConfig = DUMMY_LOGIN_CONFIG;
                context.setLoginConfig(loginConfig);
            }
        }

        // Has an authenticator been configured already?
        if (context.getAuthenticator() != null) {
            return;
        }

        // Has a Realm been configured for us to authenticate against?
        if (context.getRealm() == null) {
            log.error(sm.getString("contextConfig.missingRealm"));
            ok = false;
            return;
        }

        /*
         * First check to see if there is a custom mapping for the login
         * method. If so, use it. Otherwise, check if there is a mapping in
         * org/apache/catalina/startup/Authenticators.properties.
         */
        Valve authenticator = null;
        if (customAuthenticators != null) {
            authenticator = (Valve)
                customAuthenticators.get(loginConfig.getAuthMethod());
        }
        if (authenticator == null) {
            if (authenticators == null) {
                log.error(sm.getString("contextConfig.authenticatorResources"));
                ok = false;
                return;
            }

            // Identify the class name of the Valve we should configure
            String authenticatorName = null;
            authenticatorName =
                    authenticators.getProperty(loginConfig.getAuthMethod());
            if (authenticatorName == null) {
                log.error(sm.getString("contextConfig.authenticatorMissing",
                                 loginConfig.getAuthMethod()));
                ok = false;
                return;
            }

            // Instantiate and install an Authenticator of the requested class
            try {
                Class<?> authenticatorClass = Class.forName(authenticatorName);
                authenticator = (Valve) authenticatorClass.newInstance();
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error(sm.getString(
                                    "contextConfig.authenticatorInstantiate",
                                    authenticatorName),
                          t);
                ok = false;
            }
        }

        if (authenticator != null) {
            Pipeline pipeline = context.getPipeline();
            if (pipeline != null) {
                pipeline.addValve(authenticator);
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString(
                                    "contextConfig.authenticatorConfigured",
                                    loginConfig.getAuthMethod()));
                }
            }
        }
    }


    /**
     * Create (if necessary) and return a Digester configured to process the
     * context configuration descriptor for an application.
     */
    protected Digester createContextDigester() {
        Digester digester = new Digester();
        digester.setValidating(false);
        digester.setRulesValidation(true);
        HashMap<Class<?>, List<String>> fakeAttributes = new HashMap<>();
        ArrayList<String> attrs = new ArrayList<>();
        attrs.add("className");
        fakeAttributes.put(Object.class, attrs);
        digester.setFakeAttributes(fakeAttributes);
        RuleSet contextRuleSet = new ContextRuleSet("", false);
        digester.addRuleSet(contextRuleSet);
        RuleSet namingRuleSet = new NamingRuleSet("Context/");
        digester.addRuleSet(namingRuleSet);
        return digester;
    }


    /**
     * Process the default configuration file, if it exists.
     */
    protected void contextConfig(Digester digester) {

        String defaultContextXml = null;

        // Open the default context.xml file, if it exists
        if (context instanceof StandardContext) {
            defaultContextXml = ((StandardContext)context).getDefaultContextXml();
        }
        // set the default if we don't have any overrides
        if (defaultContextXml == null) {
            defaultContextXml = Constants.DefaultContextXml;                // 默认是 ${catalina.base}/config/context.xml (全局)
        }

        if (!context.getOverride()) {       // 设置是否 overide 已经设置好的 属性
            File defaultContextFile = new File(defaultContextXml);
            if (!defaultContextFile.isAbsolute()) {                           // 判断是否是绝对路径 (其实就是根据是否最前面是否是反斜杠)
                defaultContextFile =                // conf/context.xml
                        new File(context.getCatalinaBase(), defaultContextXml);
            }
            if (defaultContextFile.exists()) {                                // 判断 ${catalina.base}/config/context.xml 是否存在
                try {
                    URL defaultContextUrl = defaultContextFile.toURI().toURL();
                    processContextConfig(digester, defaultContextUrl);
                } catch (MalformedURLException e) {
                    log.error(sm.getString(
                            "contextConfig.badUrl", defaultContextFile), e);
                }
            }
                                                                    // conf/host下面的 context.xml                     (当前host层面的 context.xml)
            File hostContextFile = new File(getHostConfigBase(), Constants.HostContextXml);                 // ${catalina.base}\conf\Catalina\localhost\context.xml.default
            if (hostContextFile.exists()) {                                                         // 判断文件是否存在
                try {
                    URL hostContextUrl = hostContextFile.toURI().toURL();
                    processContextConfig(digester, hostContextUrl);
                } catch (MalformedURLException e) {
                    log.error(sm.getString(
                            "contextConfig.badUrl", hostContextFile), e);
                }
            }
        }
        if (context.getConfigFile() != null) {                                       // conf/host/context下面的 context.xml (StandardContext 层面的 context.xml)
            processContextConfig(digester, context.getConfigFile());
        }

    }


    /**
     * Process a context.xml.
     */
    protected void processContextConfig(Digester digester, URL contextXml) {

        if (log.isDebugEnabled()) {
            log.debug("Processing context [" + context.getName()
                    + "] configuration file [" + contextXml + "]");
        }

        InputSource source = null;
        InputStream stream = null;

        try {
            source = new InputSource(contextXml.toString());
            URLConnection xmlConn = contextXml.openConnection();
            xmlConn.setUseCaches(false);
            stream = xmlConn.getInputStream();
        } catch (Exception e) {
            log.error(sm.getString("contextConfig.contextMissing",
                      contextXml) , e);
        }

        if (source == null) {
            return;
        }

        try {
            source.setByteStream(stream);
            digester.setClassLoader(this.getClass().getClassLoader());
            digester.setUseContextClassLoader(false);
            digester.push(context.getParent());
            digester.push(context);
            XmlErrorHandler errorHandler = new XmlErrorHandler();
            digester.setErrorHandler(errorHandler);
            digester.parse(source);
            if (errorHandler.getWarnings().size() > 0 ||
                    errorHandler.getErrors().size() > 0) {
                errorHandler.logFindings(log, contextXml.toString());
                ok = false;
            }
            if (log.isDebugEnabled()) {
                log.debug("Successfully processed context [" + context.getName()
                        + "] configuration file [" + contextXml + "]");
            }
        } catch (SAXParseException e) {
            log.error(sm.getString("contextConfig.contextParse",
                    context.getName()), e);
            log.error(sm.getString("contextConfig.defaultPosition",
                             "" + e.getLineNumber(),
                             "" + e.getColumnNumber()));
            ok = false;
        } catch (Exception e) {
            log.error(sm.getString("contextConfig.contextParse",
                    context.getName()), e);
            ok = false;
        } finally {
            try {
                if (stream != null) {
                    stream.close();
                }
            } catch (IOException e) {
                log.error(sm.getString("contextConfig.contextClose"), e);
            }
        }
    }


    /**
     * Adjust docBase.
     * 根据 Host的 appBase 以及 Context的 docBase 计算 docBase 的绝对路径
     */
    protected void fixDocBase()
        throws IOException {

        Host host = (Host) context.getParent();
        File appBase = host.getAppBaseFile();

        String docBase = context.getDocBase();
        if (docBase == null) {
            // Trying to guess the docBase according to the path
            String path = context.getPath();
            if (path == null) {
                return;
            }
            ContextName cn = new ContextName(path, context.getWebappVersion());
            docBase = cn.getBaseName();
        }

        File file = new File(docBase);
        if (!file.isAbsolute()) {   // 相对路径
            docBase = (new File(appBase, docBase)).getPath();
        } else {
            docBase = file.getCanonicalPath();  // 绝对路径
        }
        file = new File(docBase);
        String origDocBase = docBase;

        ContextName cn = new ContextName(context.getPath(),                             // 每个 Context 都有一个对应的描述对象
                context.getWebappVersion());
        String pathName = cn.getBaseName();

        boolean unpackWARs = true;                                                      // 这里是获取是否解压 war 包的属性 (下一层级的属性能覆盖上一级的属性)
        if (host instanceof StandardHost) {
            unpackWARs = ((StandardHost) host).isUnpackWARs();
            if (unpackWARs && context instanceof StandardContext) {
                unpackWARs =  ((StandardContext) context).getUnpackWAR();
            }
        }
        // 如果 docBase 是一个 war 包, 且需要解压
        // 则 解压 war 包, 并更新 docBase 路径
        if (docBase.toLowerCase(Locale.ENGLISH).endsWith(".war") && !file.isDirectory() && unpackWARs) {    // 是个 war 且不是目录, 而且还没有解压
            URL war = new URL("jar:" + (new File(docBase)).toURI().toURL() + "!/");
            docBase = ExpandWar.expand(host, war, pathName);                                                  // 这里就是进行 war 包的解压
            file = new File(docBase);
            docBase = file.getCanonicalPath();
            if (context instanceof StandardContext) {
                ((StandardContext) context).setOriginalDocBase(origDocBase);
            }
        } else if (docBase.toLowerCase(Locale.ENGLISH).endsWith(".war") &&          // 如果是 war 包 但不需要解压, 则只进行检查
                !file.isDirectory() && !unpackWARs) {
            URL war =
                new URL("jar:" + (new File (docBase)).toURI().toURL() + "!/");
            ExpandWar.validate(host, war, pathName);
        } else {
            File docDir = new File(docBase);
            if (!docDir.exists()) {
                File warFile = new File(docBase + ".war");
                if (warFile.exists()) {                                                 // 判断对应部署的 war 包是否存在
                    URL war =
                        new URL("jar:" + warFile.toURI().toURL() + "!/");
                    if (unpackWARs) {
                        docBase = ExpandWar.expand(host, war, pathName);
                        file = new File(docBase);
                        docBase = file.getCanonicalPath();
                    } else {
                        docBase = warFile.getCanonicalPath();
                        ExpandWar.validate(host, war, pathName);
                    }
                }
                if (context instanceof StandardContext) {
                    ((StandardContext) context).setOriginalDocBase(origDocBase);
                }
            }
        }

        if (docBase.startsWith(appBase.getPath() + File.separatorChar)) {
            docBase = docBase.substring(appBase.getPath().length());
            docBase = docBase.replace(File.separatorChar, '/');
            if (docBase.startsWith("/")) {
                docBase = docBase.substring(1);
            }
        } else {
            docBase = docBase.replace(File.separatorChar, '/');
        }

        context.setDocBase(docBase);                                        // 设置 StandardContext 的 baseDoc

    }


    protected void antiLocking() {

        if ((context instanceof StandardContext)
            && ((StandardContext) context).getAntiResourceLocking()) {

            Host host = (Host) context.getParent();
            String docBase = context.getDocBase();
            if (docBase == null) {
                return;
            }
            originalDocBase = docBase;

            File docBaseFile = new File(docBase);
            if (!docBaseFile.isAbsolute()) {
                docBaseFile = new File(host.getAppBaseFile(), docBase);
            }

            String path = context.getPath();
            if (path == null) {
                return;
            }
            ContextName cn = new ContextName(path, context.getWebappVersion());
            docBase = cn.getBaseName();

            if (originalDocBase.toLowerCase(Locale.ENGLISH).endsWith(".war")) {
                antiLockingDocBase = new File(          // 拷贝到临时目录中
                        System.getProperty("java.io.tmpdir"),
                        deploymentCount++ + "-" + docBase + ".war");
            } else {
                antiLockingDocBase = new File(
                        System.getProperty("java.io.tmpdir"),
                        deploymentCount++ + "-" + docBase);
            }
            antiLockingDocBase = antiLockingDocBase.getAbsoluteFile();

            if (log.isDebugEnabled()) {
                log.debug("Anti locking context[" + context.getName()
                        + "] setting docBase to " +
                        antiLockingDocBase.getPath());
            }

            // Cleanup just in case an old deployment is lying around
            // 清除上一次的 antiLockingDocBase 临时文件
            ExpandWar.delete(antiLockingDocBase);
            if (ExpandWar.copy(docBaseFile, antiLockingDocBase)) {
                // 清除上一次的 antiLockingDocBase 的临时文件
                context.setDocBase(antiLockingDocBase.getPath());
            }
        }
    }


    /**
     * Process a "init" event for this Context.
     */
    protected void init() {
        // Called from StandardContext.init()

        Digester contextDigester = createContextDigester();                     // 构建 解析 Context 的 Digester
        contextDigester.getParser();

        if (log.isDebugEnabled()) {
            log.debug(sm.getString("contextConfig.init"));
        }
        context.setConfigured(false);
        ok = true;

        contextConfig(contextDigester);

        webXmlParser = new WebXmlParser(context.getXmlNamespaceAware(),
                context.getXmlValidation(), context.getXmlBlockExternal());
    }


    /**
     * Process a "before start" event for this Context.
     * 更新 Context 的 docbase 属性 和解决 Web 目录锁的问题
     */
    protected synchronized void beforeStart() {
        // docBase 属性指向的 War 包 解压后的文件夹目录
        try {
            fixDocBase();                       // 确定 docbase 相对目录
        } catch (IOException e) {
            log.error(sm.getString(
                    "contextConfig.fixDocBase", context.getName()), e);
        }
        // 若 antiResourceLocking 是 true 时, Tomcat 会将 当前的 Web 应用目录复制到 临时文件夹下, 以避免对源目录资源的加锁
        antiLocking();
    }


    /**
     * Process a "contextConfig" event for this Context.
     * 解析 web.xml, 创建 Wrapper, Filter, ServletContextListener 等一系列 Web 容器相关的对象, 完成 Web 容器的初始化s
     */
    protected synchronized void configureStart() {
        // Called from StandardContext.start()

        if (log.isDebugEnabled()) {
            log.debug(sm.getString("contextConfig.start"));
        }

        if (log.isDebugEnabled()) {
            log.debug(sm.getString("contextConfig.xmlSettings",
                    context.getName(),
                    Boolean.valueOf(context.getXmlValidation()),
                    Boolean.valueOf(context.getXmlNamespaceAware())));
        }

        webConfig();                            // 扫描  global, host, context, web_Fragment 类型的 web.xml(会扫描每个 jar 下的资源), 合并成 1 个, 根据里面的配置信息映射成实体类(Servlet, Filter, Listener, Session), 通过 SPI 机制获取 JAR 下面的 所有 ServletContainerInitializer 的实现类, 并且加载到 Context 里面

        if (!context.getIgnoreAnnotations()) {  // 如果 ignoreAnnotations 为 false(默认 false), 则解析应用程序注解配置 主要是 (listener, Filter, Servlet类的, 最后会将这些资源信息加入到 StandardContext.NamingResourcesImpl 里面, 在实例化 Servlet/Filter/Listener 时会用到)
            applicationAnnotationsConfig();
        }
        if (ok) {
            validateSecurityRoles();            // 校验角色权限之类信息
        }

        // Configure an authenticator if we need one
        if (ok) {
            authenticatorConfig();             // 配置权限限制
        }

        // Dump the contents of this pipeline if requested
        if (log.isDebugEnabled()) {
            log.debug("Pipeline Configuration:");
            Pipeline pipeline = context.getPipeline();
            Valve valves[] = null;
            if (pipeline != null) {
                valves = pipeline.getValves();
            }
            if (valves != null) {
                for (int i = 0; i < valves.length; i++) {
                    log.debug("  " + valves[i].getClass().getName());
                }
            }
            log.debug("======================");
        }

        // Make our application available if no problems were encountered
        if (ok) {
            context.setConfigured(true);
        } else {
            log.error(sm.getString("contextConfig.unavailable"));
            context.setConfigured(false);
        }

    }


    /**
     * Process a "stop" event for this Context.
     */
    protected synchronized void configureStop() {

        if (log.isDebugEnabled()) {
            log.debug(sm.getString("contextConfig.stop"));
        }

        int i;

        // Removing children
        Container[] children = context.findChildren();
        for (i = 0; i < children.length; i++) {
            context.removeChild(children[i]);
        }

        // Removing application parameters
        /*
        ApplicationParameter[] applicationParameters =
            context.findApplicationParameters();
        for (i = 0; i < applicationParameters.length; i++) {
            context.removeApplicationParameter
                (applicationParameters[i].getName());
        }
        */

        // Removing security constraints
        SecurityConstraint[] securityConstraints = context.findConstraints();
        for (i = 0; i < securityConstraints.length; i++) {
            context.removeConstraint(securityConstraints[i]);
        }

        // Removing Ejbs
        /*
        ContextEjb[] contextEjbs = context.findEjbs();
        for (i = 0; i < contextEjbs.length; i++) {
            context.removeEjb(contextEjbs[i].getName());
        }
        */

        // Removing environments
        /*
        ContextEnvironment[] contextEnvironments = context.findEnvironments();
        for (i = 0; i < contextEnvironments.length; i++) {
            context.removeEnvironment(contextEnvironments[i].getName());
        }
        */

        // Removing errors pages
        ErrorPage[] errorPages = context.findErrorPages();
        for (i = 0; i < errorPages.length; i++) {
            context.removeErrorPage(errorPages[i]);
        }

        // Removing filter defs
        FilterDef[] filterDefs = context.findFilterDefs();
        for (i = 0; i < filterDefs.length; i++) {
            context.removeFilterDef(filterDefs[i]);
        }

        // Removing filter maps
        FilterMap[] filterMaps = context.findFilterMaps();
        for (i = 0; i < filterMaps.length; i++) {
            context.removeFilterMap(filterMaps[i]);
        }

        // Removing local ejbs
        /*
        ContextLocalEjb[] contextLocalEjbs = context.findLocalEjbs();
        for (i = 0; i < contextLocalEjbs.length; i++) {
            context.removeLocalEjb(contextLocalEjbs[i].getName());
        }
        */

        // Removing Mime mappings
        String[] mimeMappings = context.findMimeMappings();
        for (i = 0; i < mimeMappings.length; i++) {
            context.removeMimeMapping(mimeMappings[i]);
        }

        // Removing parameters
        String[] parameters = context.findParameters();
        for (i = 0; i < parameters.length; i++) {
            context.removeParameter(parameters[i]);
        }

        // Removing resource env refs
        /*
        String[] resourceEnvRefs = context.findResourceEnvRefs();
        for (i = 0; i < resourceEnvRefs.length; i++) {
            context.removeResourceEnvRef(resourceEnvRefs[i]);
        }
        */

        // Removing resource links
        /*
        ContextResourceLink[] contextResourceLinks =
            context.findResourceLinks();
        for (i = 0; i < contextResourceLinks.length; i++) {
            context.removeResourceLink(contextResourceLinks[i].getName());
        }
        */

        // Removing resources
        /*
        ContextResource[] contextResources = context.findResources();
        for (i = 0; i < contextResources.length; i++) {
            context.removeResource(contextResources[i].getName());
        }
        */

        // Removing security role
        String[] securityRoles = context.findSecurityRoles();
        for (i = 0; i < securityRoles.length; i++) {
            context.removeSecurityRole(securityRoles[i]);
        }

        // Removing servlet mappings
        String[] servletMappings = context.findServletMappings();
        for (i = 0; i < servletMappings.length; i++) {
            context.removeServletMapping(servletMappings[i]);
        }

        // FIXME : Removing status pages

        // Removing welcome files
        String[] welcomeFiles = context.findWelcomeFiles();
        for (i = 0; i < welcomeFiles.length; i++) {
            context.removeWelcomeFile(welcomeFiles[i]);
        }

        // Removing wrapper lifecycles
        String[] wrapperLifecycles = context.findWrapperLifecycles();
        for (i = 0; i < wrapperLifecycles.length; i++) {
            context.removeWrapperLifecycle(wrapperLifecycles[i]);
        }

        // Removing wrapper listeners
        String[] wrapperListeners = context.findWrapperListeners();
        for (i = 0; i < wrapperListeners.length; i++) {
            context.removeWrapperListener(wrapperListeners[i]);
        }

        // Remove (partially) folders and files created by antiLocking
        if (antiLockingDocBase != null) {
            // No need to log failure - it is expected in this case
            ExpandWar.delete(antiLockingDocBase, false);
        }

        // Reset ServletContextInitializer scanning
        initializerClassMap.clear();
        typeInitializerMap.clear();

        ok = true;

    }


    /**
     * Process a "destroy" event for this Context.
     */
    protected synchronized void destroy() {
        // Called from StandardContext.destroy()
        if (log.isDebugEnabled()) {
            log.debug(sm.getString("contextConfig.destroy"));
        }

        // Skip clearing the work directory if Tomcat is being shutdown
        Server s = getServer();
        if (s != null && !s.getState().isAvailable()) {
            return;
        }

        // Changed to getWorkPath per Bugzilla 35819.
        if (context instanceof StandardContext) {
            String workDir = ((StandardContext) context).getWorkPath();
            if (workDir != null) {
                ExpandWar.delete(new File(workDir));
            }
        }
    }


    private Server getServer() {
        Container c = context;
        while (c != null && !(c instanceof Engine)) {
            c = c.getParent();
        }

        if (c == null) {
            return null;
        }

        Service s = ((Engine)c).getService();

        if (s == null) {
            return null;
        }

        return s.getServer();
    }

    /**
     * Validate the usage of security role names in the web application
     * deployment descriptor.  If any problems are found, issue warning
     * messages (for backwards compatibility) and add the missing roles.
     * (To make these problems fatal instead, simply set the <code>ok</code>
     * instance variable to <code>false</code> as well).
     */
    protected void validateSecurityRoles() {

        // Check role names used in <security-constraint> elements
        SecurityConstraint constraints[] = context.findConstraints();
        for (int i = 0; i < constraints.length; i++) {
            String roles[] = constraints[i].findAuthRoles();
            for (int j = 0; j < roles.length; j++) {
                if (!"*".equals(roles[j]) &&
                    !context.findSecurityRole(roles[j])) {
                    log.warn(sm.getString("contextConfig.role.auth", roles[j]));
                    context.addSecurityRole(roles[j]);
                }
            }
        }

        // Check role names used in <servlet> elements
        Container wrappers[] = context.findChildren();
        for (int i = 0; i < wrappers.length; i++) {
            Wrapper wrapper = (Wrapper) wrappers[i];
            String runAs = wrapper.getRunAs();
            if ((runAs != null) && !context.findSecurityRole(runAs)) {
                log.warn(sm.getString("contextConfig.role.runas", runAs));
                context.addSecurityRole(runAs);
            }
            String names[] = wrapper.findSecurityReferences();
            for (int j = 0; j < names.length; j++) {
                String link = wrapper.findSecurityReference(names[j]);
                if ((link != null) && !context.findSecurityRole(link)) {
                    log.warn(sm.getString("contextConfig.role.link", link));
                    context.addSecurityRole(link);
                }
            }
        }

    }


    protected File getHostConfigBase() {
        File file = null;
        if (context.getParent() instanceof Host) {
            file = ((Host)context.getParent()).getConfigBaseFile();
        }
        return file;
    }

    /**
     * Scan the web.xml files that apply to the web application and merge them
     * using the rules defined in the spec. For the global web.xml files,
     * where there is duplicate configuration, the most specific level wins. ie
     * an application's web.xml takes precedence over the host level or global
     * web.xml file.
     *
     * 参考资料
     * http://dba10g.blog.51cto.com/764602/1775723
     * 扫描 web.xml 文件, 在遇到全局 web.xml 或 host 层面的 web.xml, 则应用层面的 web.xml 的属性能覆盖上面两个
     */
    protected void webConfig() {
        /*
         * Anything and everything can override the global and host defaults.
         * This is implemented in two parts
         * - Handle as a web fragment that gets added after everything else so
         *   everything else takes priority
         * - Mark Servlets as overridable so SCI configuration can replace
         *   configuration from the defaults (SCI 指 ServletContainerInitializer)
         */

        /*
         * The rules for annotation scanning are not as clear-cut as one might
         * think. Tomcat implements the following process:
         * - As per SRV.1.6.2, Tomcat will scan for annotations regardless of
         *   which Servlet spec version is declared in web.xml. The EG has
         *   confirmed this is the expected behaviour.
         * - As per http://java.net/jira/browse/SERVLET_SPEC-36, if the main
         *   web.xml is marked as metadata-complete, JARs are still processed
         *   for SCIs.
         * - If metadata-complete=true and an absolute ordering is specified,
         *   JARs excluded from the ordering are also excluded from the SCI
         *   processing.
         * - If an SCI has a @HandlesType annotation then all classes (except
         *   those in JARs excluded from an absolute ordering) need to be
         *   scanned to check if they match.
         */

        /**
         * 1. 解析默认配置 生成 WebXml 对象
         * 2. 解析 Web 应用的 web.xml 文件
         */
        Set<WebXml> defaults = new HashSet<>();
        defaults.add(getDefaultWebXmlFragment());                                       // 获取 global与 host 层面的 web.xml 的 WebXml 对象

        WebXml webXml = createWebXml();                                                 // 创建 WebXml 对象

        // Parse context level web.xml
        InputSource contextWebXml = getContextWebXmlSource();                           // 获取 ${catalina.base}/webapps/${contextname}/Web-INF/web.xml 文件
        if (!webXmlParser.parseWebXml(contextWebXml, webXml, false)) {               // 将Context 层面的 web.xml 文件解析到 webXml 对象里面
            ok = false;
        }

        ServletContext sContext = context.getServletContext();

        // Ordering is important here

        // Step 1. Identify all the JARs packaged with the application and those
        // provided by the container. If any of the application JARs have a
        // web-fragment.xml it will be parsed at this point. web-fragment.xml
        // files are ignored for container provided JARs.

                                                                                    // 扫描 Web 应用所有的 JAR 包, 如果包含 META-INF/web-fragment.xml, 则解析文件并创建 WebXML 对象
        Map<String,WebXml> fragments = processJarsForWebFragments(webXml);          // 其实做的就是扫描 jar 包下面是否有 META-INF/web-fragment.xml

        // Step 2. Order the fragments.
        Set<WebXml> orderedFragments = null;
        orderedFragments =
                WebXml.orderWebFragments(webXml, fragments, sContext);              // 对所有扫描的 jar  包进行排序

        // Step 3. Look for ServletContainerInitializer implementations
        /**
         *  找到 ServletContainerInitializer 实现, 并创建 实例, 查找范围分为两部分
         *  1. Web应用下的 包, 如果是 javax.servlet.context.orderedLibs 不为空, 金搜索 该属性包含的包, 否则搜索 WEB-INF/lib下的所有的包
         *  2. 容器包: 搜索所有包
         */
        // 基于SPI 机制查找 ServletContainerInitializer 的实现, web 中间件 中很多会运用到这个  SPI 机制 及 ServletContainerInitializer 机制
        if (ok) {
            processServletContainerInitializers(sContext);
        }

        if  (!webXml.isMetadataComplete() || typeInitializerMap.size() > 0) {
            // Step 4. Process /WEB-INF/classes for annotations and                    // 处理 /WEB-INF/classes 下面的类的注解 (主要是 Servlet 及 Filter)
            // @HandlesTypes matches
            if (ok) {
                WebResource[] webResources =
                        context.getResources().listResources("/WEB-INF/classes");

                for (WebResource webResource : webResources) {
                    processAnnotationsWebResource(webResource, webXml,
                            webXml.isMetadataComplete());
                }
            }

            // Step 5. Process JARs for annotations for annotations and
            // @HandlesTypes matches - only need to process those fragments we
            // are going to use (remember orderedFragments includes any
            // container fragments)
            if (ok) {                                                               // 处理 jar 包文件的注解
                processAnnotations(
                        orderedFragments, webXml.isMetadataComplete());
            }

            // Cache, if used, is no longer required so clear it
            javaClassCache.clear();
        }

        if (!webXml.isMetadataComplete()) {
            // Step 6. Merge web-fragment.xml files into the main web.xml
            // file.
            if (ok) {                                                             // 将 web 配置文件合并到一起
                ok = webXml.merge(orderedFragments);
            }

            // Step 7. Apply global defaults                                      // 合并全局的 web.xml ${catalina.base}/conf/web.xml
            // Have to merge defaults before JSP conversion since defaults
            // provide JSP servlet definition.
            webXml.merge(defaults);

            // Step 8. Convert explicitly mentioned jsps to servlets             // 明确将 *.jsp 的处理交由 servlets, 而不是原先的 JspServlet
            if (ok) {
                convertJsps(webXml);
            }

            // Step 9. Apply merged web.xml to Context                          // 将  web.xml 里面配置的信息运用到 Context 里面
            if (ok) {
                configureContext(webXml);
            }
        } else {
            webXml.merge(defaults);
            convertJsps(webXml);
            configureContext(webXml);                                          // 这一步非常重要, 就是将 web.xml 里面的配置(servlet, filter, listener, session) 映射成对应对象, 加入到 Context 里面
        }

        // Step 9a. Make the merged web.xml available to other
        // components, specifically Jasper, to save those components
        // from having to re-generate it.
        // TODO Use a ServletContainerInitializer for Jasper
        String mergedWebXml = webXml.toXml();                                   // 这里的 mergedWebXml 就是合并 xml 里面的配置信息
        sContext.setAttribute(
               org.apache.tomcat.util.scan.Constants.MERGED_WEB_XML,         // 将合并后的 xml 放到 Context 的属性里面
               mergedWebXml);
        if (context.getLogEffectiveWebXml()) {
            log.info("web.xml:\n" + mergedWebXml);
        }

        // Always need to look for static resources
        // Step 10. Look for static resources packaged in JARs                  // 搜索 jar 包里面的静态资源
        if (ok) {
            // Spec does not define an order.
            // Use ordered JARs followed by remaining JARs
            Set<WebXml> resourceJars = new LinkedHashSet<>();
            if (orderedFragments != null) {                                   // orderedFragments 里面是 classpath 下对应的 jar 包
                for (WebXml fragment : orderedFragments) {
                    resourceJars.add(fragment);
                }
            }
            for (WebXml fragment : fragments.values()) {
                if (!resourceJars.contains(fragment)) {
                    resourceJars.add(fragment);
                }
            }
            processResourceJARs(resourceJars);                          // 扫描 jar 包下面的静态资源
            // See also StandardContext.resourcesStart() for
            // WEB-INF/classes/META-INF/resources configuration
        }

        // Step 11. Apply the ServletContainerInitializer config to the      // 将 ServletContainerInitializer 配置运用到上下文中
        // context
        if (ok) {
            for (Map.Entry<ServletContainerInitializer,
                    Set<Class<?>>> entry :
                        initializerClassMap.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    context.addServletContainerInitializer(
                            entry.getKey(), null);
                } else {
                    context.addServletContainerInitializer(
                            entry.getKey(), entry.getValue());
                }
            }
        }
    }


    private void configureContext(WebXml webxml) {
        // As far as possible, process in alphabetical order so it is easy to
        // check everything is present
        // Some validation depends on correct public ID
        context.setPublicId(webxml.getPublicId());

        // Everything else in order
        context.setEffectiveMajorVersion(webxml.getMajorVersion());
        context.setEffectiveMinorVersion(webxml.getMinorVersion());
        // web.xml 中定义的 context 参数
        for (Entry<String, String> entry : webxml.getContextParams().entrySet()) {
            context.addParameter(entry.getKey(), entry.getValue());                          // 将 web.xml 里面的定义的 parameters 加入到 StandardContext 的 parameters
        }
        context.setDenyUncoveredHttpMethods(
                webxml.getDenyUncoveredHttpMethods());
        context.setDisplayName(webxml.getDisplayName());
        context.setDistributable(webxml.isDistributable());

        /**
         * StandardContext 和 StandardServer 类似, 也是通过 配置文件 web.xml 或原注解配置中解析出来的 NamingResources
         */
        for (ContextLocalEjb ejbLocalRef : webxml.getEjbLocalRefs().values()) {
            context.getNamingResources().addLocalEjb(ejbLocalRef);
        }
        for (ContextEjb ejbRef : webxml.getEjbRefs().values()) {
            context.getNamingResources().addEjb(ejbRef);
        }
        for (ContextEnvironment environment : webxml.getEnvEntries().values()) {            // 加入 web.xml 里面 env-entry 中的数据
            context.getNamingResources().addEnvironment(environment);
        }
        for (ErrorPage errorPage : webxml.getErrorPages().values()) {
            context.addErrorPage(errorPage);
        }
        for (FilterDef filter : webxml.getFilters().values()) {                             // 加入 web.xml 里面 Filter 配置的信息
            if (filter.getAsyncSupported() == null) {
                filter.setAsyncSupported("false");
            }
            context.addFilterDef(filter);
        }
        for (FilterMap filterMap : webxml.getFilterMappings()) {                            // 加入配置文件 filter-mapping 的信息
            context.addFilterMap(filterMap);
        }
        context.setJspConfigDescriptor(webxml.getJspConfigDescriptor());                    // taglibs 标签库的配置
        for (String listener : webxml.getListeners()) {                                     // 加入 ApplicationListener 的信息
            context.addApplicationListener(
                    new ApplicationListener(listener, false));
        }
        for (Entry<String, String> entry :
                webxml.getLocaleEncodingMappings().entrySet()) {
            context.addLocaleEncodingMappingParameter(entry.getKey(),
                    entry.getValue());
        }
        // Prevents IAE
        if (webxml.getLoginConfig() != null) {                                                      // 加入配置文件 login-config 的信息
            context.setLoginConfig(webxml.getLoginConfig());
        }
        for (MessageDestinationRef mdr :
                webxml.getMessageDestinationRefs().values()) {
            context.getNamingResources().addMessageDestinationRef(mdr);
        }

        // messageDestinations were ignored in Tomcat 6, so ignore here

        context.setIgnoreAnnotations(webxml.isMetadataComplete());
        for (Entry<String, String> entry :
                webxml.getMimeMappings().entrySet()) {
            context.addMimeMapping(entry.getKey(), entry.getValue());
        }
        // Name is just used for ordering
        for (ContextResourceEnvRef resource :
                webxml.getResourceEnvRefs().values()) {  // 绑定到 JNDI 上
            context.getNamingResources().addResourceEnvRef(resource);
        }
        for (ContextResource resource : webxml.getResourceRefs().values()) {
            context.getNamingResources().addResource(resource);
        }
        boolean allAuthenticatedUsersIsAppRole =
                webxml.getSecurityRoles().contains(
                        SecurityConstraint.ROLE_ALL_AUTHENTICATED_USERS);
        for (SecurityConstraint constraint : webxml.getSecurityConstraints()) {                         // 配置文件中 security-role 的配置
            if (allAuthenticatedUsersIsAppRole) {
                constraint.treatAllAuthenticatedUsersAsApplicationRole();
            }
            context.addConstraint(constraint);
        }
        for (String role : webxml.getSecurityRoles()) {                                                 // 配置文件中 security-role 的配置
            context.addSecurityRole(role);
        }
        for (ContextService service : webxml.getServiceRefs().values()) {
            context.getNamingResources().addService(service);
        }
        for (ServletDef servlet : webxml.getServlets().values()) {                                      // 重点来了, 这里是创建 web.xml 里面描述的 Servlet 的信息
            Wrapper wrapper = context.createWrapper();                                                 // 创建 StandardWrapper
            // Description is ignored
            // Display name is ignored
            // Icons are ignored

            // jsp-file gets passed to the JSP Servlet as an init-param

            if (servlet.getLoadOnStartup() != null) {                                                  // Wrapper 设置 loadOnStartUp 值
                wrapper.setLoadOnStartup(servlet.getLoadOnStartup().intValue());
            }
            if (servlet.getEnabled() != null) {
                wrapper.setEnabled(servlet.getEnabled().booleanValue());
            }
            wrapper.setName(servlet.getServletName());                                                  // 设置 Servlet 的名称
            Map<String,String> params = servlet.getParameterMap();                                     // 初始化 Servlet 的初始化参数
            for (Entry<String, String> entry : params.entrySet()) {
                wrapper.addInitParameter(entry.getKey(), entry.getValue());
            }
            wrapper.setRunAs(servlet.getRunAs());
            Set<SecurityRoleRef> roleRefs = servlet.getSecurityRoleRefs();                             // servlet 的 securityRole (这个很少用到)
            for (SecurityRoleRef roleRef : roleRefs) {
                wrapper.addSecurityReference(
                        roleRef.getName(), roleRef.getLink());
            }
            wrapper.setServletClass(servlet.getServletClass());                                       // 设置 Servlet 对应的 class
            MultipartDef multipartdef = servlet.getMultipartDef();                                    // servlet 中文件上传的参数设置
            if (multipartdef != null) {
                if (multipartdef.getMaxFileSize() != null &&
                        multipartdef.getMaxRequestSize()!= null &&
                        multipartdef.getFileSizeThreshold() != null) {
                    wrapper.setMultipartConfigElement(new MultipartConfigElement(
                            multipartdef.getLocation(),
                            Long.parseLong(multipartdef.getMaxFileSize()),
                            Long.parseLong(multipartdef.getMaxRequestSize()),
                            Integer.parseInt(
                                    multipartdef.getFileSizeThreshold())));
                } else {
                    wrapper.setMultipartConfigElement(new MultipartConfigElement(
                            multipartdef.getLocation()));
                }
            }
            if (servlet.getAsyncSupported() != null) {                                                 // servlet 是否支持异步
                wrapper.setAsyncSupported(
                        servlet.getAsyncSupported().booleanValue());
            }
            wrapper.setOverridable(servlet.isOverridable());
            context.addChild(wrapper);                                                                // 将 servlet 的包装器放入 Context 里面
        }
        for (Entry<String, String> entry :                                                          // 放入 ServletMapping
                webxml.getServletMappings().entrySet()) {
            context.addServletMapping(entry.getKey(), entry.getValue());
        }
        SessionConfig sessionConfig = webxml.getSessionConfig();                                    // 装载 Session 的配置信息(主要还是 session 的超时时间, 默认 30 分钟)
        if (sessionConfig != null) {
            if (sessionConfig.getSessionTimeout() != null) {
                context.setSessionTimeout(
                        sessionConfig.getSessionTimeout().intValue());
            }
            SessionCookieConfig scc =
                context.getServletContext().getSessionCookieConfig();
            scc.setName(sessionConfig.getCookieName());
            scc.setDomain(sessionConfig.getCookieDomain());
            scc.setPath(sessionConfig.getCookiePath());
            scc.setComment(sessionConfig.getCookieComment());
            if (sessionConfig.getCookieHttpOnly() != null) {
                scc.setHttpOnly(sessionConfig.getCookieHttpOnly().booleanValue());
            }
            if (sessionConfig.getCookieSecure() != null) {
                scc.setSecure(sessionConfig.getCookieSecure().booleanValue());
            }
            if (sessionConfig.getCookieMaxAge() != null) {
                scc.setMaxAge(sessionConfig.getCookieMaxAge().intValue());
            }
            if (sessionConfig.getSessionTrackingModes().size() > 0) {
                context.getServletContext().setSessionTrackingModes(
                        sessionConfig.getSessionTrackingModes());
            }
        }

        // Context doesn't use version directly

        for (String welcomeFile : webxml.getWelcomeFiles()) {                                           // web.xml 里面的欢迎页面
            /*
             * The following will result in a welcome file of "" so don't add
             * that to the context
             * <welcome-file-list>
             *   <welcome-file/>
             * </welcome-file-list>
             */
            if (welcomeFile != null && welcomeFile.length() > 0) {
                context.addWelcomeFile(welcomeFile);
            }
        }

        // Do this last as it depends on servlets
        for (JspPropertyGroup jspPropertyGroup :
                webxml.getJspPropertyGroups()) {
            String jspServletName = context.findServletMapping("*.jsp");
            if (jspServletName == null) {
                jspServletName = "jsp";
            }
            if (context.findChild(jspServletName) != null) {
                for (String urlPattern : jspPropertyGroup.getUrlPatterns()) {
                    context.addServletMapping(urlPattern, jspServletName, true);
                }
            } else {
                if(log.isDebugEnabled()) {
                    for (String urlPattern : jspPropertyGroup.getUrlPatterns()) {
                        log.debug("Skiping " + urlPattern + " , no servlet " +
                                jspServletName);
                    }
                }
            }
        }

        for (Entry<String, String> entry :
                webxml.getPostConstructMethods().entrySet()) {
            context.addPostConstructMethod(entry.getKey(), entry.getValue());
        }

        for (Entry<String, String> entry :
            webxml.getPreDestroyMethods().entrySet()) {
            context.addPreDestroyMethod(entry.getKey(), entry.getValue());
        }
    }


    private WebXml getDefaultWebXmlFragment() {

        // Host should never be null
        Host host = (Host) context.getParent();

        DefaultWebXmlCacheEntry entry = hostWebXmlCache.get(host);

        InputSource globalWebXml = getGlobalWebXmlSource();             // 得到全局的 web.xml 在 ${catalina.base}/conf/web.xml (默认 存在)
        InputSource hostWebXml = getHostWebXmlSource();                 // 得到 Host 层面的 web.xml 在 ${catalina.base}/config/engineName/hostName/web.xml.default(默认 null)

        long globalTimeStamp = 0;
        long hostTimeStamp = 0;

        if (globalWebXml != null) {
            try {
                URL url = new URL(globalWebXml.getSystemId());
                globalTimeStamp = url.openConnection().getLastModified();
            } catch (MalformedURLException e) {
                globalTimeStamp = -1;
            } catch (IOException e) {
                globalTimeStamp = -1;
            }
        }

        if (hostWebXml != null) {
            try {
                URL url = new URL(hostWebXml.getSystemId());
                hostTimeStamp = url.openConnection().getLastModified();
            } catch (MalformedURLException e) {
                hostTimeStamp = -1;
            } catch (IOException e) {
                hostTimeStamp = -1;
            }
        }

        if (entry != null && entry.getGlobalTimeStamp() == globalTimeStamp &&
                entry.getHostTimeStamp() == hostTimeStamp) {
            return entry.getWebXml();
        }

        // Parsing global web.xml is relatively expensive. Use a sync block to
        // make sure it only happens once. Use the pipeline since a lock will
        // already be held on the host by another thread
        synchronized (host.getPipeline()) {
            entry = hostWebXmlCache.get(host);
            if (entry != null && entry.getGlobalTimeStamp() == globalTimeStamp &&
                    entry.getHostTimeStamp() == hostTimeStamp) {
                return entry.getWebXml();
            }

            WebXml webXmlDefaultFragment = createWebXml();                              // 创建一个 WebXml 对象
            webXmlDefaultFragment.setOverridable(true);                                // 设置 Overridable = true, 表示其属性可以被覆盖
            // Set to distributable else every app will be prevented from being
            // distributable when the default fragment is merged with the main
            // web.xml
            webXmlDefaultFragment.setDistributable(true);
            // When merging, the default welcome files are only used if the app has
            // not defined any welcomes files.
            webXmlDefaultFragment.setAlwaysAddWelcomeFiles(false);

            // Parse global web.xml if present
            if (globalWebXml == null) {
                // This is unusual enough to log
                log.info(sm.getString("contextConfig.defaultMissing"));
            } else {
                if (!webXmlParser.parseWebXml(                                        // 解析全局的 web.xml 配置文件
                        globalWebXml, webXmlDefaultFragment, false)) {
                    ok = false;
                }
            }

            // Parse host level web.xml if present
            // Additive apart from welcome pages
            webXmlDefaultFragment.setReplaceWelcomeFiles(true);

            if (!webXmlParser.parseWebXml(                                           // 解析 Host 层面的 web.xml 配置文件, 也就是 ${catalina.base}/config/engineName/hostName/web.xml.default, 默认不存在
                    hostWebXml, webXmlDefaultFragment, false)) {
                ok = false;
            }

            // Don't update the cache if an error occurs
            if (globalTimeStamp != -1 && hostTimeStamp != -1) {
                entry = new DefaultWebXmlCacheEntry(webXmlDefaultFragment,
                        globalTimeStamp, hostTimeStamp);
                hostWebXmlCache.put(host, entry);                                   // 这里存放的是 global + host 层面的 web.xml 的配置 (PS: 还有 context 层面的 web.xml)
            }

            return webXmlDefaultFragment;
        }
    }


    private void convertJsps(WebXml webXml) {
        Map<String,String> jspInitParams;
        ServletDef jspServlet = webXml.getServlets().get("jsp");
        if (jspServlet == null) {
            jspInitParams = new HashMap<>();
            Wrapper w = (Wrapper) context.findChild("jsp");
            if (w != null) {
                String[] params = w.findInitParameters();
                for (String param : params) {
                    jspInitParams.put(param, w.findInitParameter(param));
                }
            }
        } else {
            jspInitParams = jspServlet.getParameterMap();
        }
        for (ServletDef servletDef: webXml.getServlets().values()) {
            if (servletDef.getJspFile() != null) {
                convertJsp(servletDef, jspInitParams);
            }
        }
    }

    private void convertJsp(ServletDef servletDef,
            Map<String,String> jspInitParams) {
        servletDef.setServletClass(org.apache.catalina.core.Constants.JSP_SERVLET_CLASS);
        String jspFile = servletDef.getJspFile();
        if ((jspFile != null) && !jspFile.startsWith("/")) {
            if (context.isServlet22()) {
                if(log.isDebugEnabled()) {
                    log.debug(sm.getString("contextConfig.jspFile.warning",
                                       jspFile));
                }
                jspFile = "/" + jspFile;
            } else {
                throw new IllegalArgumentException
                    (sm.getString("contextConfig.jspFile.error", jspFile));
            }
        }
        servletDef.getParameterMap().put("jspFile", jspFile);
        servletDef.setJspFile(null);
        for (Map.Entry<String, String> initParam: jspInitParams.entrySet()) {
            servletDef.addInitParameter(initParam.getKey(), initParam.getValue());
        }
    }

    protected WebXml createWebXml() {
        return new WebXml();
    }

    /**
     * Scan JARs for ServletContainerInitializer implementations.
     */
    protected void processServletContainerInitializers(ServletContext servletContext) {

        List<ServletContainerInitializer> detectedScis;
        try {
            WebappServiceLoader<ServletContainerInitializer> loader =
                    new WebappServiceLoader<>(servletContext, context.getContainerSciFilter());
            detectedScis = loader.load(ServletContainerInitializer.class);                          // 通过 SPI 机制加载 接口对应的实现类 (在这前面已经把所有包里面的 service 里面有 SPI 的配置都加载出来)
        } catch (IOException e) {
            log.error(sm.getString(
                    "contextConfig.servletContainerInitializerFail",
                    context.getName()),
                e);
            ok = false;
            return;
        }

        for (ServletContainerInitializer sci : detectedScis) {
            initializerClassMap.put(sci, new HashSet<Class<?>>());

            HandlesTypes ht;
            try {
                ht = sci.getClass().getAnnotation(HandlesTypes.class);                  // 查看 ServletContainerInitializer的实现类 上面是否有注解
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.info(sm.getString("contextConfig.sci.debug",
                            sci.getClass().getName()),
                            e);
                } else {
                    log.info(sm.getString("contextConfig.sci.info",
                            sci.getClass().getName()));
                }
                continue;
            }
            if (ht == null) {
                continue;
            }
            Class<?>[] types = ht.value();
            if (types == null) {
                continue;
            }

            for (Class<?> type : types) {
                if (type.isAnnotation()) {
                    handlesTypesAnnotations = true;
                } else {
                    handlesTypesNonAnnotations = true;
                }
                Set<ServletContainerInitializer> scis =
                        typeInitializerMap.get(type);
                if (scis == null) {
                    scis = new HashSet<>();
                    typeInitializerMap.put(type, scis);
                }
                scis.add(sci);
            }
        }
    }

    /**
     * Scan JARs that contain web-fragment.xml files that will be used to
     * configure this application to see if they also contain static resources.
     * If static resources are found, add them to the context. Resources are
     * added in web-fragment.xml priority order.
     */
    protected void processResourceJARs(Set<WebXml> fragments) {
        for (WebXml fragment : fragments) {
            URL url = fragment.getURL();
            Jar jar = null;
            try {
                if ("jar".equals(url.getProtocol())) {
                    jar = JarFactory.newInstance(url);
                    jar.nextEntry();
                    String entryName = jar.getEntryName();
                    while (entryName != null) {
                        if (entryName.startsWith("META-INF/resources/")) {
                            context.getResources().createWebResourceSet(
                                    WebResourceRoot.ResourceSetType.RESOURCE_JAR,
                                    "/", url, "/META-INF/resources");
                            break;
                        }
                        jar.nextEntry();
                        entryName = jar.getEntryName();
                    }
                } else if ("file".equals(url.getProtocol())) {
                    File file = new File(url.toURI());
                    File resources = new File(file, "META-INF/resources/");
                    if (resources.isDirectory()) {
                        context.getResources().createWebResourceSet(
                                WebResourceRoot.ResourceSetType.RESOURCE_JAR,
                                "/", file.getAbsolutePath(), null, "/");
                    }
                }
            } catch (IOException ioe) {
                log.error(sm.getString("contextConfig.resourceJarFail", url,
                        context.getName()));
            } catch (URISyntaxException e) {
                log.error(sm.getString("contextConfig.resourceJarFail", url,
                    context.getName()));
            } finally {
                if (jar != null) {
                    jar.close();
                }
            }
        }
    }


    /**
     * Identify the default web.xml to be used and obtain an input source for
     * it.
     */
    protected InputSource getGlobalWebXmlSource() {
        // Is a default web.xml specified for the Context?
        if (defaultWebXml == null && context instanceof StandardContext) {
            defaultWebXml = ((StandardContext) context).getDefaultWebXml();
        }
        // Set the default if we don't have any overrides
        if (defaultWebXml == null) {        // 获取默认的 web.xml ${catalina.base}/conf/web.xml
            getDefaultWebXml();
        }

        // Is it explicitly suppressed, e.g. in embedded environment?
        if (Constants.NoDefaultWebXml.equals(defaultWebXml)) {
            return null;
        }
        return getWebXmlSource(defaultWebXml,
                context.getCatalinaBase().getPath());
    }

    /**
     * Identify the host web.xml to be used and obtain an input source for
     * it.
     */
    protected InputSource getHostWebXmlSource() {
        File hostConfigBase = getHostConfigBase();
        if (hostConfigBase == null)
            return null;

        return getWebXmlSource(Constants.HostWebXml, hostConfigBase.getPath());         // 获取 ${catalina.base}\conf\Catalina\localhost/web.xml.default
    }

    /**
     * Identify the application web.xml to be used and obtain an input source
     * for it.
     */
    @SuppressWarnings("resource") // stream is meant to be left open here
    protected InputSource getContextWebXmlSource() {                            // 获取 context 层面的 web.xml 配置文件
        InputStream stream = null;
        InputSource source = null;
        URL url = null;

        String altDDName = null;

        // Open the application web.xml file, if it exists
        ServletContext servletContext = context.getServletContext();
        if (servletContext != null) {
            altDDName = (String)servletContext.getAttribute(
                                                        Globals.ALT_DD_ATTR);
            if (altDDName != null) {
                try {
                    stream = new FileInputStream(altDDName);
                    url = new File(altDDName).toURI().toURL();
                } catch (FileNotFoundException e) {
                    log.error(sm.getString("contextConfig.altDDNotFound",
                                           altDDName));
                } catch (MalformedURLException e) {
                    log.error(sm.getString("contextConfig.applicationUrl"));
                }
            }
            else {
                stream = servletContext.getResourceAsStream     // 获取 /WEB-INF/web.xml 的数据流
                    (Constants.ApplicationWebXml);
                try {
                    url = servletContext.getResource(
                            Constants.ApplicationWebXml);
                } catch (MalformedURLException e) {
                    log.error(sm.getString("contextConfig.applicationUrl"));
                }
            }
        }
        if (stream == null || url == null) {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("contextConfig.applicationMissing") + " " + context);
            }
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        } else {
            source = new InputSource(url.toExternalForm());
            source.setByteStream(stream);
        }

        return source;
    }

    /**
     *
     * @param filename  Name of the file (possibly with one or more leading path
     *                  segments) to read
     * @param path      Location that filename is relative to
     */
    protected InputSource getWebXmlSource(String filename, String path) {
        File file = new File(filename);
        if (!file.isAbsolute()) {
            file = new File(path, filename);
        }

        InputStream stream = null;
        InputSource source = null;

        try {
            if (!file.exists()) {
                // Use getResource and getResourceAsStream
                stream =
                    getClass().getClassLoader().getResourceAsStream(filename);
                if(stream != null) {
                    source =
                        new InputSource(getClass().getClassLoader().getResource(
                                filename).toURI().toString());
                }
            } else {
                source = new InputSource(file.getAbsoluteFile().toURI().toString());
                stream = new FileInputStream(file);
            }

            if (stream != null && source != null) {
                source.setByteStream(stream);
            }
        } catch (Exception e) {
            log.error(sm.getString(
                    "contextConfig.defaultError", filename, file), e);
        }

        return source;
    }


    /**
     * Scan /WEB-INF/lib for JARs and for each one found add it and any
     * /META-INF/web-fragment.xml to the resulting Map. web-fragment.xml files
     * will be parsed before being added to the map. Every JAR will be added and
     * <code>null</code> will be used if no web-fragment.xml was found. Any JARs
     * known not contain fragments will be skipped.
     *
     * @return A map of JAR name to processed web fragment (if any)
     */
    protected Map<String,WebXml> processJarsForWebFragments(WebXml application) {

        JarScanner jarScanner = context.getJarScanner();
        boolean delegate = false;
        if (context instanceof StandardContext) {
            delegate = ((StandardContext) context).getDelegate();
        }
        boolean parseRequired = true;
        Set<String> absoluteOrder = application.getAbsoluteOrdering();
        if (absoluteOrder != null && absoluteOrder.isEmpty() &&
                !context.getXmlValidation()) {
            // Skip parsing when there is an empty absolute ordering and
            // validation is not enabled
            parseRequired = false;
        }
        FragmentJarScannerCallback callback =
                new FragmentJarScannerCallback(webXmlParser, delegate, parseRequired);

        jarScanner.scan(JarScanType.PLUGGABILITY,
                context.getServletContext(), callback);

        if (!callback.isOk()) {
            ok = false;
        }
        return callback.getFragments();                             // 这里的 Fragments 对应的是 classPath 对应下的 jar
    }

    protected void processAnnotations(Set<WebXml> fragments,
            boolean handlesTypesOnly) {
        for(WebXml fragment : fragments) {
            // Only need to scan for @HandlesTypes matches if any of the
            // following are true:
            // - it has already been determined only @HandlesTypes is required
            //   (e.g. main web.xml has metadata-complete="true"
            // - this fragment is for a container JAR (Servlet 3.1 section 8.1)
            // - this fragment has metadata-complete="true"
            boolean htOnly = handlesTypesOnly || !fragment.getWebappJar() ||
                    fragment.isMetadataComplete();

            WebXml annotations = new WebXml();
            // no impact on distributable
            annotations.setDistributable(true);
            URL url = fragment.getURL();
            processAnnotationsUrl(url, annotations, htOnly);
            Set<WebXml> set = new HashSet<>();
            set.add(annotations);
            // Merge annotations into fragment - fragment takes priority
            fragment.merge(set);
        }
    }

    protected void processAnnotationsWebResource(WebResource webResource,
            WebXml fragment, boolean handlesTypesOnly) {

        if (webResource.isDirectory()) {
            WebResource[] webResources =
                    webResource.getWebResourceRoot().listResources(
                            webResource.getWebappPath());
            for (WebResource r : webResources) {
                processAnnotationsWebResource(r, fragment, handlesTypesOnly);
            }
        } else if (webResource.isFile() &&
                webResource.getName().endsWith(".class")) {
            InputStream is = null;
            try {
                is = webResource.getInputStream();
                processAnnotationsStream(is, fragment, handlesTypesOnly);
            } catch (IOException e) {
                log.error(sm.getString("contextConfig.inputStreamWebResource",
                        webResource.getWebappPath()),e);
            } catch (ClassFormatException e) {
                log.error(sm.getString("contextConfig.inputStreamWebResource",
                        webResource.getWebappPath()),e);
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (Throwable t) {
                        ExceptionUtils.handleThrowable(t);
                    }
                }
            }
        }
    }


    protected void processAnnotationsUrl(URL url, WebXml fragment,
            boolean handlesTypesOnly) {
        if (url == null) {
            // Nothing to do.
            return;
        } else if ("jar".equals(url.getProtocol())) {
            processAnnotationsJar(url, fragment, handlesTypesOnly);
        } else if ("file".equals(url.getProtocol())) {
            try {
                processAnnotationsFile(
                        new File(url.toURI()), fragment, handlesTypesOnly);
            } catch (URISyntaxException e) {
                log.error(sm.getString("contextConfig.fileUrl", url), e);
            }
        } else {
            log.error(sm.getString("contextConfig.unknownUrlProtocol",
                    url.getProtocol(), url));
        }

    }


    protected void processAnnotationsJar(URL url, WebXml fragment,
            boolean handlesTypesOnly) {

        Jar jar = null;
        InputStream is;

        try {
            jar = JarFactory.newInstance(url);

            jar.nextEntry();
            String entryName = jar.getEntryName();
            while (entryName != null) {
                if (entryName.endsWith(".class")) {
                    is = null;
                    try {
                        is = jar.getEntryInputStream();
                        processAnnotationsStream(
                                is, fragment, handlesTypesOnly);
                    } catch (IOException e) {
                        log.error(sm.getString("contextConfig.inputStreamJar",
                                entryName, url),e);
                    } catch (ClassFormatException e) {
                        log.error(sm.getString("contextConfig.inputStreamJar",
                                entryName, url),e);
                    } finally {
                        if (is != null) {
                            try {
                                is.close();
                            } catch (IOException ioe) {
                                // Ignore
                            }
                        }
                    }
                }
                jar.nextEntry();
                entryName = jar.getEntryName();
            }
        } catch (IOException e) {
            log.error(sm.getString("contextConfig.jarFile", url), e);
        } finally {
            if (jar != null) {
                jar.close();
            }
        }
    }


    protected void processAnnotationsFile(File file, WebXml fragment,
            boolean handlesTypesOnly) {

        if (file.isDirectory()) {
            String[] dirs = file.list();
            for (String dir : dirs) {
                processAnnotationsFile(
                        new File(file,dir), fragment, handlesTypesOnly);
            }
        } else if (file.canRead() && file.getName().endsWith(".class")) {
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(file);
                processAnnotationsStream(fis, fragment, handlesTypesOnly);
            } catch (IOException e) {
                log.error(sm.getString("contextConfig.inputStreamFile",
                        file.getAbsolutePath()),e);
            } catch (ClassFormatException e) {
                log.error(sm.getString("contextConfig.inputStreamFile",
                        file.getAbsolutePath()),e);
            } finally {
                if (fis != null) {
                    try {
                        fis.close();
                    } catch (Throwable t) {
                        ExceptionUtils.handleThrowable(t);
                    }
                }
            }
        }
    }


    protected void processAnnotationsStream(InputStream is, WebXml fragment,
            boolean handlesTypesOnly)
            throws ClassFormatException, IOException {

        ClassParser parser = new ClassParser(is, null);
        JavaClass clazz = parser.parse();
        checkHandlesTypes(clazz);

        if (handlesTypesOnly) {
            return;
        }

        String className = clazz.getClassName();

        AnnotationEntry[] annotationsEntries = clazz.getAnnotationEntries();

        for (AnnotationEntry ae : annotationsEntries) {
            String type = ae.getAnnotationType();
            if ("Ljavax/servlet/annotation/WebServlet;".equals(type)) {
                processAnnotationWebServlet(className, ae, fragment);
            }else if ("Ljavax/servlet/annotation/WebFilter;".equals(type)) {
                processAnnotationWebFilter(className, ae, fragment);
            }else if ("Ljavax/servlet/annotation/WebListener;".equals(type)) {
                fragment.addListener(className);
            } else {
                // Unknown annotation - ignore
            }
        }
    }

    /**
     * For classes packaged with the web application, the class and each
     * super class needs to be checked for a match with {@link HandlesTypes} or
     * for an annotation that matches {@link HandlesTypes}.
     * @param javaClass
     */
    protected void checkHandlesTypes(JavaClass javaClass) {

        // Skip this if we can
        if (typeInitializerMap.size() == 0) {
            return;
        }

        if ((javaClass.getAccessFlags() &
                org.apache.tomcat.util.bcel.Constants.ACC_ANNOTATION) > 0) {
            // Skip annotations.
            return;
        }

        String className = javaClass.getClassName();

        Class<?> clazz = null;
        if (handlesTypesNonAnnotations) {
            // This *might* be match for a HandlesType.
            populateJavaClassCache(className, javaClass);
            JavaClassCacheEntry entry = javaClassCache.get(className);
            if (entry.getSciSet() == null) {
                try {
                    populateSCIsForCacheEntry(entry);
                } catch (StackOverflowError soe) {
                    throw new IllegalStateException(sm.getString(
                            "contextConfig.annotationsStackOverflow",
                            context.getName(),
                            classHierarchyToString(className, entry)));
                }
            }
            if (entry.getSciSet().size() > 0) {
                // Need to try and load the class
                clazz = Introspection.loadClass(context, className);
                if (clazz == null) {
                    // Can't load the class so no point continuing
                    return;
                }

                for (ServletContainerInitializer sci :
                        entry.getSciSet()) {
                    Set<Class<?>> classes = initializerClassMap.get(sci);
                    if (classes == null) {
                        classes = new HashSet<>();
                        initializerClassMap.put(sci, classes);
                    }
                    classes.add(clazz);
                }
            }
        }

        if (handlesTypesAnnotations) {
            for (Map.Entry<Class<?>, Set<ServletContainerInitializer>> entry :
                    typeInitializerMap.entrySet()) {
                if (entry.getKey().isAnnotation()) {
                    AnnotationEntry[] annotationEntries =
                            javaClass.getAnnotationEntries();
                    for (AnnotationEntry annotationEntry : annotationEntries) {
                        if (entry.getKey().getName().equals(
                                getClassName(annotationEntry.getAnnotationType()))) {
                            if (clazz == null) {
                                clazz = Introspection.loadClass(
                                        context, className);
                                if (clazz == null) {
                                    // Can't load the class so no point
                                    // continuing
                                    return;
                                }
                            }
                            for (ServletContainerInitializer sci : entry.getValue()) {
                                initializerClassMap.get(sci).add(clazz);
                            }
                            break;
                        }
                    }
                }
            }
        }
    }


    private String classHierarchyToString(String className,
            JavaClassCacheEntry entry) {
        JavaClassCacheEntry start = entry;
        StringBuilder msg = new StringBuilder(className);
        msg.append("->");

        String parentName = entry.getSuperclassName();
        JavaClassCacheEntry parent = javaClassCache.get(parentName);
        int count = 0;

        while (count < 100 && parent != null && parent != start) {
            msg.append(parentName);
            msg.append("->");

            count ++;
            parentName = parent.getSuperclassName();
            parent = javaClassCache.get(parentName);
        }

        msg.append(parentName);

        return msg.toString();
    }

    private void populateJavaClassCache(String className, JavaClass javaClass) {
        if (javaClassCache.containsKey(className)) {
            return;
        }

        // Add this class to the cache
        javaClassCache.put(className, new JavaClassCacheEntry(javaClass));

        populateJavaClassCache(javaClass.getSuperclassName());

        for (String iterface : javaClass.getInterfaceNames()) {
            populateJavaClassCache(iterface);
        }
    }

    private void populateJavaClassCache(String className) {
        if (!javaClassCache.containsKey(className)) {
            String name = className.replace('.', '/') + ".class";
            InputStream is =
                    context.getLoader().getClassLoader().getResourceAsStream(name);
            if (is == null) {
                return;
            }
            ClassParser parser = new ClassParser(is, null);
            try {
                JavaClass clazz = parser.parse();
                populateJavaClassCache(clazz.getClassName(), clazz);
            } catch (ClassFormatException e) {
                log.debug(sm.getString("contextConfig.invalidSciHandlesTypes",
                        className), e);
            } catch (IOException e) {
                log.debug(sm.getString("contextConfig.invalidSciHandlesTypes",
                        className), e);
            } finally {
                try {
                    is.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    private void populateSCIsForCacheEntry(JavaClassCacheEntry cacheEntry) {
        Set<ServletContainerInitializer> result = new HashSet<>();

        // Super class
        String superClassName = cacheEntry.getSuperclassName();
        JavaClassCacheEntry superClassCacheEntry =
                javaClassCache.get(superClassName);

        // Avoid an infinite loop with java.lang.Object
        if (cacheEntry.equals(superClassCacheEntry)) {
            cacheEntry.setSciSet(new HashSet<ServletContainerInitializer>());
            return;
        }

        // May be null of the class is not present or could not be loaded.
        if (superClassCacheEntry != null) {
            if (superClassCacheEntry.getSciSet() == null) {
                populateSCIsForCacheEntry(superClassCacheEntry);
            }
            result.addAll(superClassCacheEntry.getSciSet());
        }
        result.addAll(getSCIsForClass(superClassName));

        // Interfaces
        for (String interfaceName : cacheEntry.getInterfaceNames()) {
            JavaClassCacheEntry interfaceEntry =
                    javaClassCache.get(interfaceName);
            // A null could mean that the class not present in application or
            // that there is nothing of interest. Either way, nothing to do here
            // so move along
            if (interfaceEntry != null) {
                if (interfaceEntry.getSciSet() == null) {
                    populateSCIsForCacheEntry(interfaceEntry);
                }
                result.addAll(interfaceEntry.getSciSet());
            }
            result.addAll(getSCIsForClass(interfaceName));
        }

        cacheEntry.setSciSet(result);
    }

    private Set<ServletContainerInitializer> getSCIsForClass(String className) {
        for (Map.Entry<Class<?>, Set<ServletContainerInitializer>> entry :
                typeInitializerMap.entrySet()) {
            Class<?> clazz = entry.getKey();
            if (!clazz.isAnnotation()) {
                if (clazz.getName().equals(className)) {
                    return entry.getValue();
                }
            }
        }
        return Collections.emptySet();
    }

    private static final String getClassName(String internalForm) {
        if (!internalForm.startsWith("L")) {
            return internalForm;
        }

        // Assume starts with L, ends with ; and uses / rather than .
        return internalForm.substring(1,
                internalForm.length() - 1).replace('/', '.');
    }

    protected void processAnnotationWebServlet(String className,
            AnnotationEntry ae, WebXml fragment) {
        String servletName = null;
        // must search for name s. Spec Servlet API 3.0 - 8.2.3.3.n.ii page 81
        ElementValuePair[] evps = ae.getElementValuePairs();
        for (ElementValuePair evp : evps) {
            String name = evp.getNameString();
            if ("name".equals(name)) {
                servletName = evp.getValue().stringifyValue();
                break;
            }
        }
        if (servletName == null) {
            // classname is default servletName as annotation has no name!
            servletName = className;
        }
        ServletDef servletDef = fragment.getServlets().get(servletName);

        boolean isWebXMLservletDef;
        if (servletDef == null) {
            servletDef = new ServletDef();
            servletDef.setServletName(servletName);
            servletDef.setServletClass(className);
            isWebXMLservletDef = false;
        } else {
            isWebXMLservletDef = true;
        }

        boolean urlPatternsSet = false;
        String[] urlPatterns = null;

        // ElementValuePair[] evps = ae.getElementValuePairs();
        for (ElementValuePair evp : evps) {
            String name = evp.getNameString();
            if ("value".equals(name) || "urlPatterns".equals(name)) {
                if (urlPatternsSet) {
                    throw new IllegalArgumentException(sm.getString(
                            "contextConfig.urlPatternValue", className));
                }
                urlPatternsSet = true;
                urlPatterns = processAnnotationsStringArray(evp.getValue());
            } else if ("description".equals(name)) {
                if (servletDef.getDescription() == null) {
                    servletDef.setDescription(evp.getValue().stringifyValue());
                }
            } else if ("displayName".equals(name)) {
                if (servletDef.getDisplayName() == null) {
                    servletDef.setDisplayName(evp.getValue().stringifyValue());
                }
            } else if ("largeIcon".equals(name)) {
                if (servletDef.getLargeIcon() == null) {
                    servletDef.setLargeIcon(evp.getValue().stringifyValue());
                }
            } else if ("smallIcon".equals(name)) {
                if (servletDef.getSmallIcon() == null) {
                    servletDef.setSmallIcon(evp.getValue().stringifyValue());
                }
            } else if ("asyncSupported".equals(name)) {
                if (servletDef.getAsyncSupported() == null) {
                    servletDef.setAsyncSupported(evp.getValue()
                            .stringifyValue());
                }
            } else if ("loadOnStartup".equals(name)) {
                if (servletDef.getLoadOnStartup() == null) {
                    servletDef
                            .setLoadOnStartup(evp.getValue().stringifyValue());
                }
            } else if ("initParams".equals(name)) {
                Map<String, String> initParams = processAnnotationWebInitParams(evp
                        .getValue());
                if (isWebXMLservletDef) {
                    Map<String, String> webXMLInitParams = servletDef
                            .getParameterMap();
                    for (Map.Entry<String, String> entry : initParams
                            .entrySet()) {
                        if (webXMLInitParams.get(entry.getKey()) == null) {
                            servletDef.addInitParameter(entry.getKey(), entry
                                    .getValue());
                        }
                    }
                } else {
                    for (Map.Entry<String, String> entry : initParams
                            .entrySet()) {
                        servletDef.addInitParameter(entry.getKey(), entry
                                .getValue());
                    }
                }
            }
        }
        if (!isWebXMLservletDef && urlPatterns != null) {
            fragment.addServlet(servletDef);
        }
        if (urlPatterns != null) {
            if (!fragment.getServletMappings().containsValue(servletName)) {
                for (String urlPattern : urlPatterns) {
                    fragment.addServletMapping(urlPattern, servletName);
                }
            }
        }

    }

    /**
     * process filter annotation and merge with existing one!
     * FIXME: refactoring method too long and has redundant subroutines with
     *        processAnnotationWebServlet!
     * @param className
     * @param ae
     * @param fragment
     */
    protected void processAnnotationWebFilter(String className,
            AnnotationEntry ae, WebXml fragment) {
        String filterName = null;
        // must search for name s. Spec Servlet API 3.0 - 8.2.3.3.n.ii page 81
        ElementValuePair[] evps = ae.getElementValuePairs();
        for (ElementValuePair evp : evps) {
            String name = evp.getNameString();
            if ("filterName".equals(name)) {
                filterName = evp.getValue().stringifyValue();
                break;
            }
        }
        if (filterName == null) {
            // classname is default filterName as annotation has no name!
            filterName = className;
        }
        FilterDef filterDef = fragment.getFilters().get(filterName);
        FilterMap filterMap = new FilterMap();

        boolean isWebXMLfilterDef;
        if (filterDef == null) {
            filterDef = new FilterDef();
            filterDef.setFilterName(filterName);
            filterDef.setFilterClass(className);
            isWebXMLfilterDef = false;
        } else {
            isWebXMLfilterDef = true;
        }

        boolean urlPatternsSet = false;
        boolean servletNamesSet = false;
        boolean dispatchTypesSet = false;
        String[] urlPatterns = null;

        for (ElementValuePair evp : evps) {
            String name = evp.getNameString();
            if ("value".equals(name) || "urlPatterns".equals(name)) {
                if (urlPatternsSet) {
                    throw new IllegalArgumentException(sm.getString(
                            "contextConfig.urlPatternValue", className));
                }
                urlPatterns = processAnnotationsStringArray(evp.getValue());
                urlPatternsSet = urlPatterns.length > 0;
                for (String urlPattern : urlPatterns) {
                    filterMap.addURLPattern(urlPattern);
                }
            } else if ("servletNames".equals(name)) {
                String[] servletNames = processAnnotationsStringArray(evp
                        .getValue());
                servletNamesSet = servletNames.length > 0;
                for (String servletName : servletNames) {
                    filterMap.addServletName(servletName);
                }
            } else if ("dispatcherTypes".equals(name)) {
                String[] dispatcherTypes = processAnnotationsStringArray(evp
                        .getValue());
                dispatchTypesSet = dispatcherTypes.length > 0;
                for (String dispatcherType : dispatcherTypes) {
                    filterMap.setDispatcher(dispatcherType);
                }
            } else if ("description".equals(name)) {
                if (filterDef.getDescription() == null) {
                    filterDef.setDescription(evp.getValue().stringifyValue());
                }
            } else if ("displayName".equals(name)) {
                if (filterDef.getDisplayName() == null) {
                    filterDef.setDisplayName(evp.getValue().stringifyValue());
                }
            } else if ("largeIcon".equals(name)) {
                if (filterDef.getLargeIcon() == null) {
                    filterDef.setLargeIcon(evp.getValue().stringifyValue());
                }
            } else if ("smallIcon".equals(name)) {
                if (filterDef.getSmallIcon() == null) {
                    filterDef.setSmallIcon(evp.getValue().stringifyValue());
                }
            } else if ("asyncSupported".equals(name)) {
                if (filterDef.getAsyncSupported() == null) {
                    filterDef
                            .setAsyncSupported(evp.getValue().stringifyValue());
                }
            } else if ("initParams".equals(name)) {
                Map<String, String> initParams = processAnnotationWebInitParams(evp
                        .getValue());
                if (isWebXMLfilterDef) {
                    Map<String, String> webXMLInitParams = filterDef
                            .getParameterMap();
                    for (Map.Entry<String, String> entry : initParams
                            .entrySet()) {
                        if (webXMLInitParams.get(entry.getKey()) == null) {
                            filterDef.addInitParameter(entry.getKey(), entry
                                    .getValue());
                        }
                    }
                } else {
                    for (Map.Entry<String, String> entry : initParams
                            .entrySet()) {
                        filterDef.addInitParameter(entry.getKey(), entry
                                .getValue());
                    }
                }

            }
        }
        if (!isWebXMLfilterDef) {
            fragment.addFilter(filterDef);
            if (urlPatternsSet || servletNamesSet) {
                filterMap.setFilterName(filterName);
                fragment.addFilterMapping(filterMap);
            }
        }
        if (urlPatternsSet || dispatchTypesSet) {
            Set<FilterMap> fmap = fragment.getFilterMappings();
            FilterMap descMap = null;
            for (FilterMap map : fmap) {
                if (filterName.equals(map.getFilterName())) {
                    descMap = map;
                    break;
                }
            }
            if (descMap != null) {
                String[] urlsPatterns = descMap.getURLPatterns();
                if (urlPatternsSet
                        && (urlsPatterns == null || urlsPatterns.length == 0)) {
                    for (String urlPattern : filterMap.getURLPatterns()) {
                        descMap.addURLPattern(urlPattern);
                    }
                }
                String[] dispatcherNames = descMap.getDispatcherNames();
                if (dispatchTypesSet
                        && (dispatcherNames == null || dispatcherNames.length == 0)) {
                    for (String dis : filterMap.getDispatcherNames()) {
                        descMap.setDispatcher(dis);
                    }
                }
            }
        }

    }

    protected String[] processAnnotationsStringArray(ElementValue ev) {
        ArrayList<String> values = new ArrayList<>();
        if (ev instanceof ArrayElementValue) {
            ElementValue[] arrayValues =
                ((ArrayElementValue) ev).getElementValuesArray();
            for (ElementValue value : arrayValues) {
                values.add(value.stringifyValue());
            }
        } else {
            values.add(ev.stringifyValue());
        }
        String[] result = new String[values.size()];
        return values.toArray(result);
    }

    protected Map<String,String> processAnnotationWebInitParams(
            ElementValue ev) {
        Map<String, String> result = new HashMap<>();
        if (ev instanceof ArrayElementValue) {
            ElementValue[] arrayValues =
                ((ArrayElementValue) ev).getElementValuesArray();
            for (ElementValue value : arrayValues) {
                if (value instanceof AnnotationElementValue) {
                    ElementValuePair[] evps = ((AnnotationElementValue)
                            value).getAnnotationEntry().getElementValuePairs();
                    String initParamName = null;
                    String initParamValue = null;
                    for (ElementValuePair evp : evps) {
                        if ("name".equals(evp.getNameString())) {
                            initParamName = evp.getValue().stringifyValue();
                        } else if ("value".equals(evp.getNameString())) {
                            initParamValue = evp.getValue().stringifyValue();
                        } else {
                            // Ignore
                        }
                    }
                    result.put(initParamName, initParamValue);
                }
            }
        }
        return result;
    }

    private static class DefaultWebXmlCacheEntry {
        private final WebXml webXml;
        private final long globalTimeStamp;
        private final long hostTimeStamp;

        public DefaultWebXmlCacheEntry(WebXml webXml, long globalTimeStamp,
                long hostTimeStamp) {
            this.webXml = webXml;
            this.globalTimeStamp = globalTimeStamp;
            this.hostTimeStamp = hostTimeStamp;
        }

        public WebXml getWebXml() {
            return webXml;
        }

        public long getGlobalTimeStamp() {
            return globalTimeStamp;
        }

        public long getHostTimeStamp() {
            return hostTimeStamp;
        }
    }

    private static class JavaClassCacheEntry {
        public final String superclassName;

        public final String[] interfaceNames;

        private Set<ServletContainerInitializer> sciSet = null;

        public JavaClassCacheEntry(JavaClass javaClass) {
            superclassName = javaClass.getSuperclassName();
            interfaceNames = javaClass.getInterfaceNames();
        }

        public String getSuperclassName() {
            return superclassName;
        }

        public String[] getInterfaceNames() {
            return interfaceNames;
        }

        public Set<ServletContainerInitializer> getSciSet() {
            return sciSet;
        }

        public void setSciSet(Set<ServletContainerInitializer> sciSet) {
            this.sciSet = sciSet;
        }
    }
}

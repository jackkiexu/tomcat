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
package org.apache.catalina.loader;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilePermission;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.security.AccessControlException;
import java.security.AccessController;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.Policy;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.jar.Attributes;
import java.util.jar.Attributes.Name;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.catalina.Globals;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.WebResource;
import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.webresources.TomcatURLStreamHandlerFactory;
import org.apache.log4j.Logger;
import org.apache.tomcat.InstrumentableClassLoader;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.res.StringManager;

/**
 * Specialized web application class loader.
 * <p>
 * This class loader is a full reimplementation of the
 * <code>URLClassLoader</code> from the JDK. It is designed to be fully
 * compatible with a normal <code>URLClassLoader</code>, although its internal
 * behavior may be completely different.
 * <p>
 * <strong>IMPLEMENTATION NOTE</strong> - By default, this class loader follows
 * the delegation model required by the specification. The system class
 * loader will be queried first, then the local repositories, and only then
 * delegation to the parent class loader will occur. This allows the web
 * application to override any shared class except the classes from J2SE.
 * Special handling is provided from the JAXP XML parser interfaces, the JNDI
 * interfaces, and the classes from the servlet API, which are never loaded
 * from the webapp repositories. The <code>delegate</code> property
 * allows an application to modify this behavior to move the parent class loader
 * ahead of the local repositories.
 * <p>
 * <strong>IMPLEMENTATION NOTE</strong> - Due to limitations in Jasper
 * compilation technology, any repository which contains classes from
 * the servlet API will be ignored by the class loader.
 * <p>
 * <strong>IMPLEMENTATION NOTE</strong> - The class loader generates source
 * URLs which include the full JAR URL when a class is loaded from a JAR file,
 * which allows setting security permission at the class level, even when a
 * class is contained inside a JAR.
 * <p>
 * <strong>IMPLEMENTATION NOTE</strong> - Local repositories are searched in
 * the order they are added via the initial constructor and/or any subsequent
 * calls to <code>addRepository()</code> or <code>addJar()</code>.
 * <p>
 * <strong>IMPLEMENTATION NOTE</strong> - No check for sealing violations or
 * security is made unless a security manager is present.
 * <p>
 * <strong>IMPLEMENTATION NOTE</strong> - As of 8.0, this class
 * loader implements {@link InstrumentableClassLoader}, permitting web
 * application classes to instrument other classes in the same web
 * application. It does not permit instrumentation of system or container
 * classes or classes in other web apps.
 *
 * @author Remy Maucherat
 * @author Craig R. McClanahan
 *
 * 参考资料
 * https://wiki.apache.org/tomcat/MemoryLeakProtection
 */
public class WebappClassLoader extends URLClassLoader
        implements Lifecycle, InstrumentableClassLoader {
    public static Logger log = Logger.getLogger(WebappClassLoader.class);
//    private static final org.apache.juli.logging.Log log=
//        org.apache.juli.logging.LogFactory.getLog( WebappClassLoader.class );

    /**
     * List of ThreadGroup names to ignore when scanning for web application
     * started threads that need to be shut down.
     */
    private static final List<String> JVM_THREAD_GROUP_NAMES = new ArrayList<>();

    private static final String JVN_THREAD_GROUP_SYSTEM = "system";

    private static final String CLASS_FILE_SUFFIX = ".class";

    static {
        JVM_THREAD_GROUP_NAMES.add(JVN_THREAD_GROUP_SYSTEM);
        JVM_THREAD_GROUP_NAMES.add("RMI Runtime");
    }

    protected class PrivilegedFindResourceByName
        implements PrivilegedAction<ResourceEntry> {

        protected final String name;
        protected final String path;

        PrivilegedFindResourceByName(String name, String path) {
            this.name = name;
            this.path = path;
        }

        @Override
        public ResourceEntry run() {
            return findResourceInternal(name, path);
        }

    }


    protected static final class PrivilegedGetClassLoader
        implements PrivilegedAction<ClassLoader> {

        public final Class<?> clazz;

        public PrivilegedGetClassLoader(Class<?> clazz){
            this.clazz = clazz;
        }

        @Override
        public ClassLoader run() {
            return clazz.getClassLoader();
        }
    }


    // ------------------------------------------------------- Static Variables

    /**
     * Regular expression of package names which are not allowed to be loaded
     * from a webapp class loader without delegating first.
     */
    protected final Matcher packageTriggersDeny = Pattern.compile(              // 在 delegating = false 的情况下, 被这个正则匹配到的 class 不会被 WebappClassLoader 进行加载 (其实就是 Tomcat 中的代码不能被 WebappClassLoader 来加载)
            "^javax\\.el\\.|" +
            "^javax\\.servlet\\.|" +
            "^org\\.apache\\.(catalina|coyote|el|jasper|juli|naming|tomcat)\\."
            ).matcher("");


    /**
     * Regular expression of package names which are allowed to be loaded from a
     * webapp class loader without delegating first and override any set by
     * {@link #packageTriggersDeny}.
     */
    protected final Matcher packageTriggersPermit =                            // 在 delegating = false 的情况下, 下面正则匹配到的类会被 WebappClassLoader 进行加载
            Pattern.compile("^javax\\.servlet\\.jsp\\.jstl\\.").matcher("");


    /**
     * The string manager for this package.
     */
    protected static final StringManager sm =                                   // 日志记录工具
        StringManager.getManager(Constants.Package);


    // ----------------------------------------------------------- Constructors

    /**
     * Construct a new ClassLoader with no defined repositories and no
     * parent ClassLoader.
     */
    public WebappClassLoader() {

        super(new URL[0]);

        ClassLoader p = getParent();                            // 这里做个检查, 若构造函数传来的 parent 是 null, 则 将 AppClassLoader 赋值给 WebAppClassLoader 的 parent
        if (p == null) {
            p = getSystemClassLoader();
        }
        this.parent = p;

        ClassLoader j = String.class.getClassLoader();
        if (j == null) {                                       // 下面几步是 获取 Launcher.ExtClassLoader 赋值给 j2seClassLoader (主要是在类加载时会被用到)
            j = getSystemClassLoader();
            while (j.getParent() != null) {
                j = j.getParent();
            }
        }
        this.j2seClassLoader = j;
        securityManager = System.getSecurityManager();      // 这里的操作主要是判断 Java 程序是否启动安全策略
        if (securityManager != null) {
            refreshPolicy();
        }
    }


    /**
     * Construct a new ClassLoader with no defined repositories and the given
     * parent ClassLoader.
     * <p>
     * Method is used via reflection -
     * see {@link WebappLoader#createClassLoader()}
     *
     * @param parent Our parent class loader
     */
    public WebappClassLoader(ClassLoader parent) {                  // 1. 在 Tomcat 8.x.x 中运行时, 会发现 parent 就是 commonClassLoader

        super(new URL[0], parent);

        ClassLoader p = getParent();                                // 2. 这里做个检查, 若构造函数传来的 parent 是 null, 则 将 AppClassLoader 赋值给 WebAppClassLoader 的 parent
        if (p == null) {
            p = getSystemClassLoader();
        }
        this.parent = p;
                                                                    // 3. 下面几步是 获取 Launcher.ExtClassLoader 赋值给 j2seClassLoader (主要是在类加载时会被用到)
        ClassLoader j = String.class.getClassLoader();
        if (j == null) {
            j = getSystemClassLoader();
            while (j.getParent() != null) {
                j = j.getParent();
            }
        }
        this.j2seClassLoader = j;                               // 4. 这里进行赋值的就是 Launcher.ExtClassLoader

        securityManager = System.getSecurityManager();          // 5. 这里的操作主要是判断 Java 程序是否启动安全策略
        if (securityManager != null) {
            refreshPolicy();
        }
    }


    // ----------------------------------------------------- Instance Variables

    /**
     * Associated web resources for this webapp.
     * TODO Review the use of resources in this class to see if further
     *      simplifications can be made.
     */
    protected WebResourceRoot resources = null;                             // 这个 WebappClassLoader 加载的资源


    /**
     * The cache of ResourceEntry for classes and resources we have loaded,
     * keyed by resource path, not binary name. Path is used as the key since
     * resources may be requested by binary name (classes) or path (other
     * resources such as property files) and the mapping from binary name to
     * path is unambiguous but the reverse mapping is ambiguous.
     */
                                                                                // 加载资源的时候会将 文件缓存在这个 Map 里面, 下次就可以根据 ResourceEntry.lastModified 来判断是否需要热部署
    protected final Map<String, ResourceEntry> resourceEntries = new ConcurrentHashMap<>();


    /**
     * Should this class loader delegate to the parent class loader
     * <strong>before</strong> searching its own repositories (i.e. the
     * usual Java2 delegation model)?  If set to <code>false</code>,
     * this class loader will search its own repositories first, and
     * delegate to the parent only if the class or resource is not
     * found locally. Note that the default, <code>false</code>, is
     * the behavior called for by the servlet specification.
     *
     * 是否在 WebappClassLoader 进行加载资源之前 让其 parentClassLoader 尝试代理加载一下
     * 默认值 false:
     */
    protected boolean delegate = false;

                                                                                // 保存每个加载的资源, 上次修改的时间
    private final HashMap<String,Long> jarModificationTimes = new HashMap<>();


    /**
     * A list of read File and Jndi Permission's required if this loader
     * is for a web application context.
     */
    protected final ArrayList<Permission> permissionList = new ArrayList<>();


    /**
     * The PermissionCollection for each CodeSource for a web
     * application context.
     */
    protected final HashMap<String, PermissionCollection> loaderPC = new HashMap<>();


    /**
     * Instance of the SecurityManager installed.
     */
    protected final SecurityManager securityManager;


    /**
     * The parent class loader.
     */
    protected final ClassLoader parent;                      // WebappClassLoader 的父 parent(在这里 Tomcat 8.x.x, parent  其实就是 commonClassloader)


    /**
     * The bootstrap class loader used to load the J2SE classes. In some
     * implementations this class loader is always <code>null</null> and in
     * those cases {@link ClassLoader#getParent()} will be called recursively on
     * the system class loader and the last non-null result used.
     */
    protected final ClassLoader j2seClassLoader;            // 这个 classLoader 其实就是 ExtClassLoader (PS: 所有的 WebappClassLoader 出发到加载 J2SE 的类时, 直接通过 ExtClassLoader / BootstrapClassLoader 来进行加载 )


    /**
     * Has this component been started?
     */
    protected boolean started = false;


    /**
     * need conversion for properties files
     */
    protected boolean needConvert = false;


    /**
     * All permission.
     */
    protected final Permission allPermission = new java.security.AllPermission();


    /**
     * Should Tomcat attempt to null out any static or final fields from loaded
     * classes when a web application is stopped as a work around for apparent
     * garbage collection bugs and application coding errors? There have been
     * some issues reported with log4j when this option is true. Applications
     * without memory leaks using recent JVMs should operate correctly with this
     * option set to <code>false</code>. If not specified, the default value of
     * <code>false</code> will be used.
     */
    private boolean clearReferencesStatic = false;

    /**
     * Should Tomcat attempt to terminate threads that have been started by the
     * web application? Stopping threads is performed via the deprecated (for
     * good reason) <code>Thread.stop()</code> method and is likely to result in
     * instability. As such, enabling this should be viewed as an option of last
     * resort in a development environment and is not recommended in a
     * production environment. If not specified, the default value of
     * <code>false</code> will be used.
     */
    private boolean clearReferencesStopThreads = false;

    /**
     * Should Tomcat attempt to terminate any {@link java.util.TimerThread}s
     * that have been started by the web application? If not specified, the
     * default value of <code>false</code> will be used.
     */
    private boolean clearReferencesStopTimerThreads = false;

    /**
     * Should Tomcat call {@link org.apache.juli.logging.LogFactory#release()}
     * when the class loader is stopped? If not specified, the default value
     * of <code>true</code> is used. Changing the default setting is likely to
     * lead to memory leaks and other issues.
     */
    private boolean clearReferencesLogFactoryRelease = true;

    /**
     * If an HttpClient keep-alive timer thread has been started by this web
     * application and is still running, should Tomcat change the context class
     * loader from the current {@link WebappClassLoader} to
     * {@link WebappClassLoader#parent} to prevent a memory leak? Note that the
     * keep-alive timer thread will stop on its own once the keep-alives all
     * expire however, on a busy system that might not happen for some time.
     */
    private boolean clearReferencesHttpClientKeepAliveThread = true;

    /**
     * Holds the class file transformers decorating this class loader. The
     * CopyOnWriteArrayList is thread safe. It is expensive on writes, but
     * those should be rare. It is very fast on reads, since synchronization
     * is not actually used. Importantly, the ClassLoader will never block
     * iterating over the transformers while loading a class.
     */
    private final List<ClassFileTransformer> transformers = new CopyOnWriteArrayList<>();


    // ------------------------------------------------------------- Properties

    /**
     * Get associated resources.
     */
    public WebResourceRoot getResources() {
        return this.resources;
    }


    /**
     * Set associated resources.
     * WebappLoader 会将 StandardContext 里面的 StandardRoot 设置到这里
     */
    public void setResources(WebResourceRoot resources) {
        this.resources = resources;
    }


    /**
     * Return the context name for this class loader.
     */
    public String getContextName() {
        if (resources == null) {
            return "Unknown";
        } else {
            return resources.getContext().getName();
        }
    }


    /**
     * Return the "delegate first" flag for this class loader.
     */
    public boolean getDelegate() {

        return (this.delegate);

    }


    /**
     * Set the "delegate first" flag for this class loader.
     * If this flag is true, this class loader delegates
     * to the parent class loader
     * <strong>before</strong> searching its own repositories, as
     * in an ordinary (non-servlet) chain of Java class loaders.
     * If set to <code>false</code> (the default),
     * this class loader will search its own repositories first, and
     * delegate to the parent only if the class or resource is not
     * found locally, as per the servlet specification.
     *
     * @param delegate The new "delegate first" flag
     */
    public void setDelegate(boolean delegate) {
        this.delegate = delegate;
    }


    /**
     * If there is a Java SecurityManager create a read permission for the
     * target of the given URL as appropriate.
     *
     * @param url URL for a file or directory on local system
     */
    void addPermission(URL url) {
        if (url == null) {
            return;
        }
        if (securityManager != null) {
            String protocol = url.getProtocol();
            if ("file".equalsIgnoreCase(protocol)) {
                URI uri;
                File f;
                String path;
                try {
                    uri = url.toURI();
                    f = new File(uri);
                    path = f.getCanonicalPath();
                } catch (IOException | URISyntaxException e) {
                    log.warn(sm.getString(
                            "webappClassLoader.addPermisionNoCanonicalFile",
                            url.toExternalForm()));
                    return;
                }
                if (f.isFile()) {
                    // Allow the file to be read
                    addPermission(new FilePermission(path, "read"));
                } else if (f.isDirectory()) {
                    addPermission(new FilePermission(path, "read"));
                    addPermission(new FilePermission(
                            path + File.separator + "-", "read"));
                } else {
                    // File does not exist - ignore (shouldn't happen)
                }
            } else {
                // Unsupported URL protocol
                log.warn(sm.getString(
                        "webappClassLoader.addPermisionNoProtocol",
                        protocol, url.toExternalForm()));
            }
        }
    }


    /**
     * If there is a Java SecurityManager create a Permission.
     *
     * @param permission The permission
     */
    void addPermission(Permission permission) {
        if ((securityManager != null) && (permission != null)) {
            permissionList.add(permission);
        }
    }


    /**
     * Return the clearReferencesStatic flag for this Context.
     */
    public boolean getClearReferencesStatic() {
        return (this.clearReferencesStatic);
    }


    /**
     * Set the clearReferencesStatic feature for this Context.
     *
     * @param clearReferencesStatic The new flag value
     */
    public void setClearReferencesStatic(boolean clearReferencesStatic) {
        this.clearReferencesStatic = clearReferencesStatic;
    }


    /**
     * Return the clearReferencesStopThreads flag for this Context.
     */
    public boolean getClearReferencesStopThreads() {
        return (this.clearReferencesStopThreads);
    }


    /**
     * Set the clearReferencesStopThreads feature for this Context.
     *
     * @param clearReferencesStopThreads The new flag value
     */
    public void setClearReferencesStopThreads(
            boolean clearReferencesStopThreads) {
        this.clearReferencesStopThreads = clearReferencesStopThreads;
    }


    /**
     * Return the clearReferencesStopTimerThreads flag for this Context.
     */
    public boolean getClearReferencesStopTimerThreads() {
        return (this.clearReferencesStopTimerThreads);
    }


    /**
     * Set the clearReferencesStopTimerThreads feature for this Context.
     *
     * @param clearReferencesStopTimerThreads The new flag value
     */
    public void setClearReferencesStopTimerThreads(
            boolean clearReferencesStopTimerThreads) {
        this.clearReferencesStopTimerThreads = clearReferencesStopTimerThreads;
    }


    /**
     * Return the clearReferencesLogFactoryRelease flag for this Context.
     */
    public boolean getClearReferencesLogFactoryRelease() {
        return (this.clearReferencesLogFactoryRelease);
    }


    /**
     * Set the clearReferencesLogFactoryRelease feature for this Context.
     *
     * @param clearReferencesLogFactoryRelease The new flag value
     */
    public void setClearReferencesLogFactoryRelease(
            boolean clearReferencesLogFactoryRelease) {
        this.clearReferencesLogFactoryRelease =
            clearReferencesLogFactoryRelease;
    }


    /**
     * Return the clearReferencesHttpClientKeepAliveThread flag for this
     * Context.
     */
    public boolean getClearReferencesHttpClientKeepAliveThread() {
        return (this.clearReferencesHttpClientKeepAliveThread);
    }


    /**
     * Set the clearReferencesHttpClientKeepAliveThread feature for this
     * Context.
     *
     * @param clearReferencesHttpClientKeepAliveThread The new flag value
     */
    public void setClearReferencesHttpClientKeepAliveThread(
            boolean clearReferencesHttpClientKeepAliveThread) {
        this.clearReferencesHttpClientKeepAliveThread =
            clearReferencesHttpClientKeepAliveThread;
    }


    // ------------------------------------------------------- Reloader Methods

    /**
     * Adds the specified class file transformer to this class loader. The
     * transformer will then be able to modify the bytecode of any classes
     * loaded by this class loader after the invocation of this method.
     *
     * @param transformer The transformer to add to the class loader
     */
    @Override
    public void addTransformer(ClassFileTransformer transformer) {

        if (transformer == null) {
            throw new IllegalArgumentException(sm.getString(
                    "webappClassLoader.addTransformer.illegalArgument", getContextName()));
        }

        if (this.transformers.contains(transformer)) {
            // if the same instance of this transformer was already added, bail out
            log.warn(sm.getString("webappClassLoader.addTransformer.duplicate",
                    transformer, getContextName()));
            return;
        }
        this.transformers.add(transformer);

        log.info(sm.getString("webappClassLoader.addTransformer", transformer, getContextName()));
    }

    /**
     * Removes the specified class file transformer from this class loader.
     * It will no longer be able to modify the byte code of any classes
     * loaded by the class loader after the invocation of this method.
     * However, any classes already modified by this transformer will
     * remain transformed.
     *
     * @param transformer The transformer to remove
     */
    @Override
    public void removeTransformer(ClassFileTransformer transformer) {

        if (transformer == null) {
            return;
        }

        if (this.transformers.remove(transformer)) {
            log.info(sm.getString("webappClassLoader.removeTransformer",
                    transformer, getContextName()));
            return;
        }

    }

    /**
     * Returns a copy of this class loader without any class file
     * transformers. This is a tool often used by Java Persistence API
     * providers to inspect entity classes in the absence of any
     * instrumentation, something that can't be guaranteed within the
     * context of a {@link ClassFileTransformer}'s
     * {@link ClassFileTransformer#transform(ClassLoader, String, Class,
     * ProtectionDomain, byte[]) transform} method.
     * <p>
     * The returned class loader's resource cache will have been cleared
     * so that classes already instrumented will not be retained or
     * returned.
     *
     * @return the transformer-free copy of this class loader.
     */
    @Override
    public WebappClassLoader copyWithoutTransformers() {

        WebappClassLoader result = new WebappClassLoader(getParent());

        result.resources = this.resources;
        result.delegate = this.delegate;
        result.started = this.started;
        result.needConvert = this.needConvert;
        result.clearReferencesStatic = this.clearReferencesStatic;
        result.clearReferencesStopThreads = this.clearReferencesStopThreads;
        result.clearReferencesStopTimerThreads = this.clearReferencesStopTimerThreads;
        result.clearReferencesLogFactoryRelease = this.clearReferencesLogFactoryRelease;
        result.clearReferencesHttpClientKeepAliveThread = this.clearReferencesHttpClientKeepAliveThread;
        result.jarModificationTimes.putAll(this.jarModificationTimes);
        result.permissionList.addAll(this.permissionList);
        result.loaderPC.putAll(this.loaderPC);

        try {
            result.start();
        } catch (LifecycleException e) {
            throw new IllegalStateException(e);
        }

        return result;
    }

    /**
     * Have one or more classes or resources been modified so that a reload
     * is appropriate?
     */
    // 校验 WebappClassLoader 加载的资源是否有修改过, 若有文件修改过, 则进行热部署
    public boolean modified() {

        if (log.isDebugEnabled())
            log.debug("modified()");

        for (Entry<String,ResourceEntry> entry : resourceEntries.entrySet()) {       // 1. 遍历已经加载的资源
            long cachedLastModified = entry.getValue().lastModified;
            long lastModified = resources.getClassLoaderResource(
                    entry.getKey()).getLastModified();                                  // 2. 对比 file 的 lastModified的属性
            if (lastModified != cachedLastModified) {                                   // 3. 若修改时间不对, 则说明文件被修改过, StandardContext 需要重新部署
                if( log.isDebugEnabled() )
                    log.debug(sm.getString("webappClassLoader.resourceModified",
                            entry.getKey(),
                            new Date(cachedLastModified),
                            new Date(lastModified)));
                return true;
            }
        }

        // Check if JARs have been added or removed
        WebResource[] jars = resources.listResources("/WEB-INF/lib");
        // Filter out non-JAR resources

        int jarCount = 0;
        for (WebResource jar : jars) {
            if (jar.getName().endsWith(".jar") && jar.isFile() && jar.canRead()) {      // 4. 比较 /WEB-INF/lib 下的 jar 包是否有修改/增加/减少
                jarCount++;                                                              // 5. 记录 /WEB-INF/lib 下的 jar 的个数
                Long recordedLastModified = jarModificationTimes.get(jar.getName());
                if (recordedLastModified == null) {
                    // Jar has been added
                    log.info(sm.getString("webappClassLoader.jarsAdded",
                            resources.getContext().getName()));
                    return true;
                }
                if (recordedLastModified.longValue() != jar.getLastModified()) {        // 6. 比较一下这次的文件修改时间 与 上次文件的修改时间是否一样, 不一样的话, 直接返回 true, StandardContext 需要重新部署
                    // Jar has been changed
                    log.info(sm.getString("webappClassLoader.jarsModified",
                            resources.getContext().getName()));
                    return true;
                }
            }
        }

        if (jarCount < jarModificationTimes.size()){                                 // 7. 判断 WebappClassloader文件是够有增加/减少, 若有变化的话, 直接返回 true, StandardContext 需要重新部署
            log.info(sm.getString("webappClassLoader.jarsRemoved",
                    resources.getContext().getName()));
            return true;
        }


        // No classes have been modified
        return false;
    }


    /**
     * Render a String representation of this object.
     */
    @Override
    public String toString() {

        StringBuilder sb = new StringBuilder("WebappClassLoader\r\n");
        sb.append("  context: ");
        sb.append(getContextName());
        sb.append("\r\n");
        sb.append("  delegate: ");
        sb.append(delegate);
        sb.append("\r\n");
        if (this.parent != null) {
            sb.append("----------> Parent Classloader:\r\n");
            sb.append(this.parent.toString());
            sb.append("\r\n");
        }
        if (this.transformers.size() > 0) {
            sb.append("----------> Class file transformers:\r\n");
            for (ClassFileTransformer transformer : this.transformers) {
                sb.append(transformer).append("\r\n");
            }
        }
        sb.append("----------->" + this.hashCode());
        return (sb.toString());

    }


    // ---------------------------------------------------- ClassLoader Methods


    /**
     * Expose this method for use by the unit tests.
     */
    protected final Class<?> doDefineClass(String name, byte[] b, int off, int len,     // 这个就是调用父类的 defineClass 来加载二进制文件到内存中生成 class 对象
            ProtectionDomain protectionDomain) {
        return super.defineClass(name, b, off, len, protectionDomain);
    }

    /**
     * Find the specified class in our local repositories, if possible.  If
     * not found, throw <code>ClassNotFoundException</code>.
     *
     * @param name The binary name of the class to be loaded
     *
     * @exception ClassNotFoundException if the class was not found
     */
    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {

        if (log.isDebugEnabled())
            log.debug("    findClass(" + name + ")");

        // Cannot load anything from local repositories if class loader is stopped
        if (!started) {
            throw new ClassNotFoundException(name);
        }

        // (1) Permission to define this class when using a SecurityManager
        if (securityManager != null) {                                          // 1. 趟若设置安全管理器, 则用 securityManager 来进行检测 加载的数据名
            int i = name.lastIndexOf('.');
            if (i >= 0) {
                try {
                    if (log.isTraceEnabled())
                        log.trace("      securityManager.checkPackageDefinition");
                    securityManager.checkPackageDefinition(name.substring(0,i));
                } catch (Exception se) {
                    if (log.isTraceEnabled())
                        log.trace("      -->Exception-->ClassNotFoundException", se);
                    throw new ClassNotFoundException(name, se);
                }
            }
        }

        // Ask our superclass to locate this class, if possible
        // (throws ClassNotFoundException if it is not found)
        Class<?> clazz = null;
        try {
            if (log.isTraceEnabled())
                log.trace("      findClassInternal(" + name + ")");
            try {
                clazz = findClassInternal(name);                                // 2. 调用 findClassInternal 进行资源的查找
            } catch(ClassNotFoundException cnfe) {
                if (log.isDebugEnabled())
                    log.debug("    --> Returning ClassNotFoundException");
                throw cnfe;
            } catch(AccessControlException ace) {
                log.warn("WebappClassLoader.findClassInternal(" + name
                        + ") security exception: " + ace.getMessage(), ace);
                throw new ClassNotFoundException(name, ace);
            } catch (RuntimeException e) {
                if (log.isInfoEnabled())
                    log.trace("      -->RuntimeException Rethrown", e);
                throw e;
            }
        } catch (ClassNotFoundException e) {
            if (log.isInfoEnabled())
                log.trace("    --> Passing on ClassNotFoundException");
            throw e;
        }

        // Return the class we have located
        if (log.isInfoEnabled())
            log.debug("      Returning class " + clazz);

        if (log.isInfoEnabled()) {                                                      // 3. 进行一些日志打印操作
            ClassLoader cl;
            if (Globals.IS_SECURITY_ENABLED){
                cl = AccessController.doPrivileged(
                    new PrivilegedGetClassLoader(clazz));
            } else {
                cl = clazz.getClassLoader();
            }
            log.debug("      Loaded by " + cl.toString());
        }
        return (clazz);

    }


    /**
     * Find the specified resource in our local repository, and return a
     * <code>URL</code> referring to it, or <code>null</code> if this resource
     * cannot be found.
     *
     * @param name Name of the resource to be found
     */
    @Override
    public URL findResource(final String name) {

        if (log.isDebugEnabled())
            log.debug("    findResource(" + name + ")");

        URL url = null;

        String path = nameToPath(name);

        ResourceEntry entry = resourceEntries.get(path);
        if (entry == null) {
            if (securityManager != null) {
                PrivilegedAction<ResourceEntry> dp =
                    new PrivilegedFindResourceByName(name, path);
                entry = AccessController.doPrivileged(dp);
            } else {
                entry = findResourceInternal(name, path);
            }
        }
        if (entry != null) {
            url = entry.source;
        }

        if (log.isDebugEnabled()) {
            if (url != null)
                log.debug("    --> Returning '" + url.toString() + "'");
            else
                log.debug("    --> Resource not found, returning null");
        }
        return (url);

    }


    /**
     * Return an enumeration of <code>URLs</code> representing all of the
     * resources with the given name.  If no resources with this name are
     * found, return an empty enumeration.
     *
     * @param name Name of the resources to be found
     *
     * @exception IOException if an input/output error occurs
     */
    @Override
    public Enumeration<URL> findResources(String name) throws IOException {

        if (log.isDebugEnabled())
            log.debug("    findResources(" + name + ")");

        LinkedHashSet<URL> result = new LinkedHashSet<>();

        String path = nameToPath(name);

        WebResource[] webResources = resources.getClassLoaderResources(path);
        for (WebResource webResource : webResources) {
            if (webResource.exists()) {
                result.add(webResource.getURL());
            }
        }

        return Collections.enumeration(result);
    }


    /**
     * Find the resource with the given name.  A resource is some data
     * (images, audio, text, etc.) that can be accessed by class code in a
     * way that is independent of the location of the code.  The name of a
     * resource is a "/"-separated path name that identifies the resource.
     * If the resource cannot be found, return <code>null</code>.
     * <p>
     * This method searches according to the following algorithm, returning
     * as soon as it finds the appropriate URL.  If the resource cannot be
     * found, returns <code>null</code>.
     * <ul>
     * <li>If the <code>delegate</code> property is set to <code>true</code>,
     *     call the <code>getResource()</code> method of the parent class
     *     loader, if any.</li>
     * <li>Call <code>findResource()</code> to find this resource in our
     *     locally defined repositories.</li>
     * <li>Call the <code>getResource()</code> method of the parent class
     *     loader, if any.</li>
     * </ul>
     *
     * @param name Name of the resource to return a URL for
     */
    @Override
    public URL getResource(String name) {

        if (log.isDebugEnabled())
            log.debug("getResource(" + name + ")");
        URL url = null;

        // (1) Delegate to parent if requested
        if (delegate) {
            if (log.isDebugEnabled())
                log.debug("  Delegating to parent classloader " + parent);
            url = parent.getResource(name);
            if (url != null) {
                if (log.isDebugEnabled())
                    log.debug("  --> Returning '" + url.toString() + "'");
                return (url);
            }
        }

        // (2) Search local repositories
        url = findResource(name);
        if (url != null) {
            if (log.isDebugEnabled())
                log.debug("  --> Returning '" + url.toString() + "'");
            return (url);
        }

        // (3) Delegate to parent unconditionally if not already attempted
        if( !delegate ) {
            url = parent.getResource(name);
            if (url != null) {
                if (log.isDebugEnabled())
                    log.debug("  --> Returning '" + url.toString() + "'");
                return (url);
            }
        }

        // (4) Resource was not found
        if (log.isDebugEnabled())
            log.debug("  --> Resource not found, returning null");
        return (null);

    }


    /**
     * Find the resource with the given name, and return an input stream
     * that can be used for reading it.  The search order is as described
     * for <code>getResource()</code>, after checking to see if the resource
     * data has been previously cached.  If the resource cannot be found,
     * return <code>null</code>.
     *
     * @param name Name of the resource to return an input stream for
     */
    @Override
    public InputStream getResourceAsStream(String name) {

        if (log.isDebugEnabled())
            log.debug("getResourceAsStream(" + name + ")");
        InputStream stream = null;

        // (0) Check for a cached copy of this resource
        stream = findLoadedResource(name);
        if (stream != null) {
            if (log.isDebugEnabled())
                log.debug("  --> Returning stream from cache");
            return (stream);
        }

        // (1) Delegate to parent if requested
        if (delegate) {
            if (log.isDebugEnabled())
                log.debug("  Delegating to parent classloader " + parent);
            stream = parent.getResourceAsStream(name);
            if (stream != null) {
                // FIXME - cache???
                if (log.isDebugEnabled())
                    log.debug("  --> Returning stream from parent");
                return (stream);
            }
        }

        // (2) Search local repositories
        if (log.isDebugEnabled())
            log.debug("  Searching local repositories");
        URL url = findResource(name);
        if (url != null) {
            // FIXME - cache???
            if (log.isDebugEnabled())
                log.debug("  --> Returning stream from local");
            stream = findLoadedResource(name);
            if (stream != null)
                return (stream);
        }

        // (3) Delegate to parent unconditionally
        if (!delegate) {
            if (log.isDebugEnabled())
                log.debug("  Delegating to parent classloader unconditionally " + parent);
            stream = parent.getResourceAsStream(name);
            if (stream != null) {
                // FIXME - cache???
                if (log.isDebugEnabled())
                    log.debug("  --> Returning stream from parent");
                return (stream);
            }
        }

        // (4) Resource was not found
        if (log.isDebugEnabled())
            log.debug("  --> Resource not found, returning null");
        return (null);

    }


    /**
     * Load the class with the specified name.  This method searches for
     * classes in the same manner as <code>loadClass(String, boolean)</code>
     * with <code>false</code> as the second argument.
     *
     * @param name The binary name of the class to be loaded
     *
     * @exception ClassNotFoundException if the class was not found
     */
    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {

        return (loadClass(name, false));

    }


    /**
     * Load the class with the specified name, searching using the following
     * algorithm until it finds and returns the class.  If the class cannot
     * be found, returns <code>ClassNotFoundException</code>.
     * <ul>
     * <li>Call <code>findLoadedClass(String)</code> to check if the
     *     class has already been loaded.  If it has, the same
     *     <code>Class</code> object is returned.</li>
     * <li>If the <code>delegate</code> property is set to <code>true</code>,
     *     call the <code>loadClass()</code> method of the parent class
     *     loader, if any.</li>
     * <li>Call <code>findClass()</code> to find this class in our locally
     *     defined repositories.</li>
     * <li>Call the <code>loadClass()</code> method of our parent
     *     class loader, if any.</li>
     * </ul>
     * If the class was found using the above steps, and the
     * <code>resolve</code> flag is <code>true</code>, this method will then
     * call <code>resolveClass(Class)</code> on the resulting Class object.
     *
     * @param name The binary name of the class to be loaded
     * @param resolve If <code>true</code> then resolve the class
     *
     * @exception ClassNotFoundException if the class was not found
     */
    /** WebappClassLoader loadClass class 流程
     *  1. 判断当前运用是否已经启动, 未启动, 则直接抛异常
     *  2. 调用 findLocaledClass0 从 resourceEntries 中判断 class 是否已经加载 OK
     *  3. 调用 findLoadedClass(内部调用一个 native 方法) 直接查看对应的 WebappClassLoader 是否已经加载过
     *  4. 调用 binaryNameToPath 判断是否 当前 class 是属于 J2SE 范围中的, 若是的则直接通过 ExtClassLoader, BootstrapClassLoader 进行加载 (这里是双亲委派)
     *  5. 在设置 JVM 权限校验的情况下, 调用 securityManager 来进行权限的校验(当前类是否有权限加载这个类, 默认的权限配置文件是 ${catalina.base}/conf/catalina.policy)
     *  6. 判断是否设置了双亲委派机制 或 当前 WebappClassLoader 是否能加载这个 class (通过 filter(name) 来决定), 将最终的值赋值给 delegateLoad
     *  7. 根据上一步中的 delegateLoad 来决定是否用 WebappClassloader.parent(也就是 sharedClassLoader) 来进行加载, 若加载成功, 则直接返回
     *  8. 上一步若未加载成功, 则调用 WebappClassloader.findClass(name) 来进行加载
     *  9. 若上一还是没有加载成功, 则通过 parent 调用 Class.forName 来进行加载
     *  10. 若还没加载成功的话, 那就直接抛异常
     */
    @Override
    public synchronized Class<?> loadClass(String name, boolean resolve)
        throws ClassNotFoundException {

        if (log.isDebugEnabled())
            log.debug("loadClass(" + name + ", " + resolve + ")");
        Class<?> clazz = null;

        // Log access to stopped classloader                                     // 1.  判断程序是否已经启动了, 未启动 OK, 就进行加载, 则直接抛异常
        if (!started) {
            try {
                throw new IllegalStateException();
            } catch (IllegalStateException e) {
                log.info(sm.getString("webappClassLoader.stopped", name), e);
            }
        }

        // (0) Check our previously loaded local class cache
                                                                                 // 2. 当前对象缓存中检查是否已经加载该类, 有的话直接返回 Class
        clazz = findLoadedClass0(name);
        if (clazz != null) {
            if (log.isDebugEnabled())
                log.debug("  Returning class from cache");
            if (resolve)
                resolveClass(clazz);
            return (clazz);
        }

        // (0.1) Check our previously loaded class cache
                                                                                 // 3. 是否已经加载过该类 (这里的加载最终会调用一个 native 方法, 意思就是检查这个 ClassLoader 是否已经加载过对应的 class 了哇)
        clazz = findLoadedClass(name);
        if (clazz != null) {
            if (log.isDebugEnabled())
                log.debug("  Returning class from cache");
            if (resolve)
                resolveClass(clazz);
            return (clazz);
        }

        // (0.2) Try loading the class with the system class loader, to prevent // 代码到这里发现, 上面两步是 1. 查看 resourceEntries 里面的信息, 判断 class 是否加载过, 2. 通过 findLoadedClass 判断 JVM 中是否已经加载过, 但现在 直接用 j2seClassLoader(Luancher.ExtClassLoader 这里的加载过程是双亲委派模式) 来进行加载
        //       the webapp from overriding J2SE classes                        // 这是为什么呢 ? 主要是 这里直接用 ExtClassLoader 来加载 J2SE 所对应的 class, 防止被 WebappClassLoader 加载了
        String resourceName = binaryNameToPath(name, false);                    // 4. 进行 class 名称 转路径的操作 (文件的尾缀是 .class)
        if (j2seClassLoader.getResource(resourceName) != null) {             // 5. 这里的 j2seClassLoader 其实就是 ExtClassLoader, 这里就是 查找 BootstrapClassloader 与 ExtClassLoader 是否有权限加载这个 class (通过 URLClassPath 来确认)
            try {
                clazz = j2seClassLoader.loadClass(name);
                if (clazz != null) {
                    if (resolve)
                        resolveClass(clazz);
                    return (clazz);
                }
            } catch (ClassNotFoundException e) {
                // Ignore
            }
        }

        // (0.5) Permission to access this class when using a SecurityManager     // 6. 这里的 securityManager 与 Java 安全策略是否有关, 默认 (securityManager == null), 所以一开始看代码就不要关注这里
        if (securityManager != null) {
            int i = name.lastIndexOf('.');
            if (i >= 0) {
                try {
                    securityManager.checkPackageAccess(name.substring(0,i));  // 7. 通过 securityManager 对 是否能加载 name 的权限进行检查 (对应的策略都在 ${catalina.base}/conf/catalina.policy 里面进行定义)
                } catch (SecurityException se) {
                    String error = "Security Violation, attempt to use " +
                        "Restricted Class: " + name;
                    log.info(error, se);
                    throw new ClassNotFoundException(error, se);
                }
            }
        }

        boolean delegateLoad = delegate || filter(name);                    // 8. 读取 delegate 的配置信息, filter 主要判断这个 class 是否能由这个 WebappClassLoader 进行加载 (false: 能进行加载, true: 不能被加载)

        // (1) Delegate to our parent if requested
        // 如果配置了 parent-first 模式, 那么委托给父加载器                   // 9. 当进行加载 javax 下面的包 就直接交给 parent(sharedClassLoader) 来进行加载 (为什么? 主要是 这些公共加载的资源统一由 sharedClassLoader 来进行加载, 能减少 Perm 区域的大小)
        if (delegateLoad) {                                                   // 10. 若 delegate 开启, 优先使用 parent classloader( delegate 默认是 false); 这里还有一种可能, 就是 经过 filter(name) 后, 还是返回 true, 那说明 WebappClassLoader 不应该进行加载, 应该交给其 parent 进行加载
            if (log.isDebugEnabled())
                log.debug("  Delegating to parent classloader1 " + parent);
            try {
                clazz = Class.forName(name, false, parent);                 // 11. 通过 parent ClassLoader 来进行加载 (这里构造函数中第二个参数 false 表示: 使用 parent 加载 classs 时不进行初始化操作, 也就是 不会执行这个 class 中 static 里面的初始操作 以及 一些成员变量ed赋值操作, 这一动作也符合 JVM 一贯的 lazy-init 策略)
                if (clazz != null) {
                    if (log.isDebugEnabled())
                        log.debug("  Loading class from parent");
                    if (resolve)
                        resolveClass(clazz);
                    return (clazz);                                         // 12. 通过 parent ClassLoader 加载成功, 则直接返回
                }
            } catch (ClassNotFoundException e) {
                // Ignore
            }
        }

        // (2) Search local repositories
        if (log.isDebugEnabled())
            log.debug("  Searching local repositories");
        try {
            // 从 WebApp 中去加载类, 主要是 WebApp 下的 classes 目录 与 lib 目录
            clazz = findClass(name);                                        // 13. 使用当前的 WebappClassLoader 加载
            if (clazz != null) {
                if (log.isDebugEnabled())
                    log.debug("  Loading class from local repository");
                if (resolve)
                    resolveClass(clazz);
                return (clazz);
            }
        } catch (ClassNotFoundException e) {
            // Ignore
        }

        // (3) Delegate to parent unconditionally
        // 如果在当前 WebApp 中无法加载到, 委托给 StandardClassLoader 从 $catalina_home/lib 中去加载
        if (!delegateLoad) {                                                // 14. 这是在 delegate = false 时, 在本 classLoader 上进行加载后, 再进行操作这里
            if (log.isDebugEnabled())
                log.debug("  Delegating to parent classloader at end: " + parent);
            try {
                clazz = Class.forName(name, false, parent);               // 15. 用 WebappClassLoader 的 parent(ExtClassLoader) 来进行加载
                if (clazz != null) {
                    if (log.isDebugEnabled())
                        log.debug("  Loading class from parent");
                    if (resolve)
                        resolveClass(clazz);
                    return (clazz);
                }
            } catch (ClassNotFoundException e) {
                // Ignore
            }
        }

        throw new ClassNotFoundException(name);                          // 16. 若还是加载不到, 那就抛出异常吧

    }


    /**
     * Get the Permissions for a CodeSource.  If this instance
     * of WebappClassLoader is for a web application context,
     * add read FilePermission or JndiPermissions for the base
     * directory (if unpacked),
     * the context URL, and jar file resources.
     *
     * @param codeSource where the code was loaded from
     * @return PermissionCollection for CodeSource
     */
    @Override
    protected PermissionCollection getPermissions(CodeSource codeSource) {

        String codeUrl = codeSource.getLocation().toString();
        PermissionCollection pc;
        if ((pc = loaderPC.get(codeUrl)) == null) {
            pc = super.getPermissions(codeSource);
            if (pc != null) {
                Iterator<Permission> perms = permissionList.iterator();
                while (perms.hasNext()) {
                    Permission p = perms.next();
                    pc.add(p);
                }
                loaderPC.put(codeUrl,pc);
            }
        }
        return (pc);

    }


    /**
     * {@inheritDoc}
     * <p>
     * Note that list of URLs returned by this method may not be complete. The
     * web application class loader accesses class loader resources via the
     * {@link WebResourceRoot} which supports the arbitrary mapping of
     * additional files, directories and contents of JAR files under
     * WEB-INF/classes. Any such resources will not be included in the URLs
     * returned here.
     */
    @Override
    public URL[] getURLs() {
        return super.getURLs();
    }


    // ------------------------------------------------------ Lifecycle Methods


    /**
     * Add a lifecycle event listener to this component.
     *
     * @param listener The listener to add
     */
    @Override
    public void addLifecycleListener(LifecycleListener listener) {
        // NOOP
    }


    /**
     * Get the lifecycle listeners associated with this lifecycle. If this
     * Lifecycle has no listeners registered, a zero-length array is returned.
     */
    @Override
    public LifecycleListener[] findLifecycleListeners() {
        return new LifecycleListener[0];
    }


    /**
     * Remove a lifecycle event listener from this component.
     *
     * @param listener The listener to remove
     */
    @Override
    public void removeLifecycleListener(LifecycleListener listener) {
        // NOOP
    }


    /**
     * Obtain the current state of the source component.
     *
     * @return The current state of the source component.
     */
    @Override
    public LifecycleState getState() {
        return LifecycleState.NEW;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public String getStateName() {
        return getState().toString();
    }


    @Override
    public void init() {
        // NOOP
    }


    /**
     * Start the class loader.
     *
     * @exception LifecycleException if a lifecycle error occurs
     * 将 /WEB-INF/classes 及 /WEB-INF/lib 封装成 URL 加入到 ClassLoader 的 URLClassPath 里面
     */
    @Override
    public void start() throws LifecycleException {
                                                                                    // 下面的 resources 其实就是  StandardRoot
                                                                                    // WebappClassLoader 进行资源/类 URL 的加载操作 (/WEB-INF/classes  与 WEB-INF/lib 下面资源的 URL)
        WebResource classes = resources.getResource("/WEB-INF/classes");        // 1. 加入 /WEB_INF/classes 的 URL
        if (classes.isDirectory() && classes.canRead()) {
            addURL(classes.getURL());
        }                                                                           // 2. 加入 /WEB_INF/lib 下面的 jar 的URL 加入 URLClassPath
        WebResource[] jars = resources.listResources("/WEB-INF/lib");
        for (WebResource jar : jars) {
            if (jar.getName().endsWith(".jar") && jar.isFile() && jar.canRead()) {
                addURL(jar.getURL());                                               // 3. 这一步就是将 ClassLoader需要加载的 classPath 路径 加入到 URLClassLoader.URLClassPath 里面
                jarModificationTimes.put(                                        // 4. 放一下 jar 文件的 lastModified
                        jar.getName(), Long.valueOf(jar.getLastModified()));
            }
        }

        started = true;
        String encoding = null;
        try {
            encoding = System.getProperty("file.encoding");
        } catch (SecurityException e) {
            return;
        }
        if (encoding.indexOf("EBCDIC")!=-1) {
            needConvert = true;
        }

    }


    public boolean isStarted() {
        return started;
    }

    /**
     * Stop the class loader.
     *
     * @exception LifecycleException if a lifecycle error occurs
     */
    @Override
    public void stop() throws LifecycleException {

        // Clearing references should be done before setting started to
        // false, due to possible side effects
        clearReferences();                 // 1. 清除各种资源

        started = false;

        resourceEntries.clear();        // 2. 清空各种 WebappClassLoader 加载的数据
        jarModificationTimes.clear();  // 3. 清空各种 监视的资源(监视的资源一旦有变动, 就会触发 StandardContext 的重新加载机制)
        resources = null;

        permissionList.clear();         // 4. 下面两个清空的是与 Java 权限相关的资源
        loaderPC.clear();
    }


    @Override
    public void destroy() {
        // NOOP
    }


    // ------------------------------------------------------ Protected Methods

    /**
     * 参考资料
     * https://mp.weixin.qq.com/s?__biz=MzA4MTc3Nzk4NQ==&mid=2650076392&idx=1&sn=871d952a75b80eef073127698faf3536&chksm=878f90c6b0f819d0324748e3087f2de0e54fb6823ecbb138479f9aa38c5826585e62cf674784&mpshare=1&scene=23&srcid=0615MemRBS2YodaqKAZaXcjY#rd
     *
     * Clear references.
     */
    protected void clearReferences() {

        // De-register any remaining JDBC drivers
        clearReferencesJdbc();                         // 1. 清除应用链接的数据源 (调用 JdbcLeakPrevention.clearJdbcDriverRegistrations 来获取所有 这个 WebappClassLoader 加载出来的 JDBC 驱动, 并且调用 DriverManager.deregisterDriver 注销掉)

        // Stop any threads the web application started
        clearReferencesThreads();                      // 2. 清除应用启动的线程 (通过线程组获取所有存活的线程, 针对 Timer 线程, 在清空其内部 queue 后， 通过反射调用 cancel 来停止Timer; 若是 ThreadPoolExecutor 里面的线程则直接调用其 shutdownNow() 方法来关闭整个线程池)

        // Check for leaks triggered by ThreadLocals loaded by this class loader
        checkThreadLocalsForLeaks();                   // 3. 清除 ThreadLocal 缓存

        // Clear RMI Targets loaded by this class loader
        clearReferencesRmiTargets();                   // 4. 清除 rmiTarget (还是通过反射, 拿到rmi 里面的资源)

        // Null out any static or final fields from loaded classes,
        // as a workaround for apparent garbage collection bugs
        if (clearReferencesStatic) {
            clearReferencesStaticFinal();              // 5. static, final 资源清空 (这里就是遍历 WebappClassLoader 加载出来的 class,将其中 static, final 的field 置为null, 加速 GC)
        }

         // Clear the IntrospectionUtils cache.
        IntrospectionUtils.clear();                    // 6. 反射资源清空 (IntrospectionUtils.objectMethods 里面缓存这所有调用它的 class 及method 等信息)

        // Clear the classloader reference in common-logging
        if (clearReferencesLogFactoryRelease) {  // 7. 日志工厂释放(主要是让 ClassLoaderLogManager.ClassLoaderLogInfo 中的 handles 从 logger 里面清除, 见 ClassLoaderLogManager.reset() 方法)
            org.apache.juli.logging.LogFactory.release(this);
        }

        // Clear the resource bundle cache
        // This shouldn't be necessary, the cache uses weak references but
        // it has caused leaks. Oddly, using the leak detection code in
        // standard host allows the class loader to be GC'd. This has been seen
        // on Sun but not IBM JREs. Maybe a bug in Sun's GC impl?
        clearReferencesResourceBundles();             // 8. 资源绑定解除 (清除掉 ResourceBundle 里面的缓存集合 cacheList, 其实清不清除没关系, 因为 LoaderReference 是对 classloader 的一个弱引用, 在没有强引用的情况下, 弱引用的对象马上会被回收掉)

        // Clear the classloader reference in the VM's bean introspector
        java.beans.Introspector.flushCaches();        // 9. 清空缓存 (其实就是清空 Introspector 里面缓存 类 方法的 declaredMethodCache)

        // Clear any custom URLStreamHandlers
        TomcatURLStreamHandlerFactory.release(this); // 10.这个运用额场景比较少, 主要删除 由 当前 WebappClassLoader 加载出来的 URLStreamHandlerFactory
    }


    /**
     * Deregister any JDBC drivers registered by the webapp that the webapp
     * forgot. This is made unnecessary complex because a) DriverManager
     * checks the class loader of the calling class (it would be much easier
     * if it checked the context class loader) b) using reflection would
     * create a dependency on the DriverManager implementation which can,
     * and has, changed.
     *
     * We can't just create an instance of JdbcLeakPrevention as it will be
     * loaded by the common class loader (since it's .class file is in the
     * $CATALINA_HOME/lib directory). This would fail DriverManager's check
     * on the class loader of the calling class. So, we load the bytes via
     * our parent class loader but define the class with this class loader
     * so the JdbcLeakPrevention looks like a webapp class to the
     * DriverManager.
     *
     * If only apps cleaned up after themselves...
     */
    private final void clearReferencesJdbc() {
        InputStream is = getResourceAsStream(
                "org/apache/catalina/loader/JdbcLeakPrevention.class");
        // We know roughly how big the class will be (~ 1K) so allow 2k as a
        // starting point
        byte[] classBytes = new byte[2048];                                           // 直接读取 JdbcLeakPrevention.class 文件 的二进制信息
        int offset = 0;
        try {
            int read = is.read(classBytes, offset, classBytes.length-offset);
            while (read > -1) {
                offset += read;
                if (offset == classBytes.length) {
                    // Buffer full - double size
                    byte[] tmp = new byte[classBytes.length * 2];
                    System.arraycopy(classBytes, 0, tmp, 0, classBytes.length);
                    classBytes = tmp;
                }
                read = is.read(classBytes, offset, classBytes.length-offset);
            }
            Class<?> lpClass =
                defineClass("org.apache.catalina.loader.JdbcLeakPrevention",
                    classBytes, 0, offset, this.getClass().getProtectionDomain());    // 在加载 class 时有个非常重要的参数 getProtectionDomain()
            Object obj = lpClass.newInstance();
            @SuppressWarnings("unchecked")
            List<String> driverNames = (List<String>) obj.getClass().getMethod(        // 最终调用 JdbcLeakPrevention.clearJdbcDriverRegistrations 来获取所有 这个 WebappClassLoader 加载出来的 JDBC 驱动, 并且调用 DriverManager.deregisterDriver 注销掉
                    "clearJdbcDriverRegistrations").invoke(obj);
            for (String name : driverNames) {
                log.error(sm.getString("webappClassLoader.clearJdbc",
                        getContextName(), name));
            }
        } catch (Exception e) {
            // So many things to go wrong above...
            Throwable t = ExceptionUtils.unwrapInvocationTargetException(e);
            ExceptionUtils.handleThrowable(t);
            log.warn(sm.getString(
                    "webappClassLoader.jdbcRemoveFailed", getContextName()), t);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ioe) {
                    log.warn(sm.getString(
                            "webappClassLoader.jdbcRemoveStreamError",
                            getContextName()), ioe);
                }
            }
        }
    }


    /**
     * 参考资料
     * https://mp.weixin.qq.com/s?__biz=MzA4MTc3Nzk4NQ==&mid=2650076392&idx=1&sn=871d952a75b80eef073127698faf3536&chksm=878f90c6b0f819d0324748e3087f2de0e54fb6823ecbb138479f9aa38c5826585e62cf674784&mpshare=1&scene=23&srcid=0615MemRBS2YodaqKAZaXcjY#rd
     */
    private final void clearReferencesStaticFinal() {

        Collection<ResourceEntry> values = resourceEntries.values();
        Iterator<ResourceEntry> loadedClasses = values.iterator();
        //
        // walk through all loaded class to trigger initialization for
        //    any uninitialized classes, otherwise initialization of
        //    one class may call a previously cleared class.
        while(loadedClasses.hasNext()) {                            // 遍历 WebappClassLoader 加载出来的所有的 class
            ResourceEntry entry = loadedClasses.next();
            if (entry.loadedClass != null) {
                Class<?> clazz = entry.loadedClass;
                try {
                    Field[] fields = clazz.getDeclaredFields();
                    for (int i = 0; i < fields.length; i++) {
                        if(Modifier.isStatic(fields[i].getModifiers())) {
                            fields[i].get(null);                    // 将 WebappClassLoader 加载出来的 static 属性置为 null, 加速 GC
                            break;
                        }
                    }
                } catch(Throwable t) {
                    // Ignore
                }
            }
        }
        loadedClasses = values.iterator();
        while (loadedClasses.hasNext()) {
            ResourceEntry entry = loadedClasses.next();
            if (entry.loadedClass != null) {
                Class<?> clazz = entry.loadedClass;
                try {
                    Field[] fields = clazz.getDeclaredFields();
                    for (int i = 0; i < fields.length; i++) {
                        Field field = fields[i];
                        int mods = field.getModifiers();
                        if (field.getType().isPrimitive()
                                || (field.getName().indexOf("$") != -1)) {
                            continue;
                        }
                        if (Modifier.isStatic(mods)) {
                            try {
                                field.setAccessible(true);
                                if (Modifier.isFinal(mods)) {       // 将 WebappClassLoader 加载出来的 final 属性置为 null, 加速 GC
                                    if (!((field.getType().getName().startsWith("java."))
                                            || (field.getType().getName().startsWith("javax.")))) {
                                        nullInstance(field.get(null));
                                    }
                                } else {
                                    field.set(null, null);
                                    if (log.isDebugEnabled()) {
                                        log.debug("Set field " + field.getName()
                                                + " to null in class " + clazz.getName());
                                    }
                                }
                            } catch (Throwable t) {
                                ExceptionUtils.handleThrowable(t);
                                if (log.isDebugEnabled()) {
                                    log.debug("Could not set field " + field.getName()
                                            + " to null in class " + clazz.getName(), t);
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    if (log.isDebugEnabled()) {
                        log.debug("Could not clean fields for class " + clazz.getName(), t);
                    }
                }
            }
        }

    }


    private void nullInstance(Object instance) {
        if (instance == null) {
            return;
        }
        Field[] fields = instance.getClass().getDeclaredFields();
        for (int i = 0; i < fields.length; i++) {
            Field field = fields[i];
            int mods = field.getModifiers();
            if (field.getType().isPrimitive()
                    || (field.getName().indexOf("$") != -1)) {
                continue;
            }
            try {
                field.setAccessible(true);
                if (Modifier.isStatic(mods) && Modifier.isFinal(mods)) {
                    // Doing something recursively is too risky
                    continue;
                }
                Object value = field.get(instance);
                if (null != value) {
                    Class<? extends Object> valueClass = value.getClass();
                    if (!loadedByThisOrChild(valueClass)) {
                        if (log.isDebugEnabled()) {
                            log.debug("Not setting field " + field.getName() +
                                    " to null in object of class " +
                                    instance.getClass().getName() +
                                    " because the referenced object was of type " +
                                    valueClass.getName() +
                                    " which was not loaded by this WebappClassLoader.");
                        }
                    } else {
                        field.set(instance, null);
                        if (log.isDebugEnabled()) {
                            log.debug("Set field " + field.getName()
                                    + " to null in class " + instance.getClass().getName());
                        }
                    }
                }
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                if (log.isDebugEnabled()) {
                    log.debug("Could not set field " + field.getName()
                            + " to null in object instance of class "
                            + instance.getClass().getName(), t);
                }
            }
        }
    }


    /**
     * 参考资料
     * https://mp.weixin.qq.com/s?__biz=MzA4MTc3Nzk4NQ==&mid=2650076392&idx=1&sn=871d952a75b80eef073127698faf3536&chksm=878f90c6b0f819d0324748e3087f2de0e54fb6823ecbb138479f9aa38c5826585e62cf674784&mpshare=1&scene=23&srcid=0615MemRBS2YodaqKAZaXcjY#rd
     *
     * 通过 StandardContext 的几个属性来控制是否 clear掉当前应用创建出来的线程
     * 主要思路:
     * 首先通过 当前的ThreadGroup来拿到 ThreadGroup来拿到当前Tomcat启动(也就是JVM虚拟机)的所有线程
     * 拿到之后对比当前 Thread.contextClassLoader 是否就是当前应用的 webappClassLoader, 如果一样, 说明 Thread
     * 就是当前应用创建出来的线程. 之后 Tomcat 针对 JVM 的线程, Timer线程, JDK线程池 ThreadExecutor中创建的线程等多种类型的线程, 给出其对应的办法
     */
    @SuppressWarnings("deprecation") // thread.stop()
    private void clearReferencesThreads() {

        Thread[] threads = getThreads();                             // 1. getThreads 返回的是一个 JVM 实例中所有的线程数, 而我们处理的线程是 由当前 WebappClassLoader 加载出来的 线程
        List<Thread> executorThreadsToStop = new ArrayList<>();

        // Iterate over the set of threads
        for (Thread thread : threads) {
            if (thread != null) {
                ClassLoader ccl = thread.getContextClassLoader();
                if (ccl == this) {                                  // 2. 判断当前线程是否是由当前 WebappClassLoader 加载出来的
                    // Don't warn about this thread
                    if (thread == Thread.currentThread()) {
                        continue;
                    }

                    // JVM controlled threads
                    // 对于 JVM 线程 保留
                    ThreadGroup tg = thread.getThreadGroup();
                    if (tg != null &&                              // 3. 对应 RMI 或 system 的
                            JVM_THREAD_GROUP_NAMES.contains(tg.getName())) {
                        /**
                         * 对于 keeperalive的Timer线程, 应该由
                         * keeperalive自己的心跳自己结束, 不应该在
                         * 这里强制关掉, 因此这里将该 Thread 交给
                         * 其 classloader的上级, 让其自动扫描后关掉
                         */
                        // HttpClient keep-alive threads
                        if (clearReferencesHttpClientKeepAliveThread &&
                                thread.getName().equals("Keep-Alive-Timer")) {
                            thread.setContextClassLoader(parent);
                            log.debug(sm.getString(
                                    "webappClassLoader.checkThreadsHttpClient"));
                        }

                        // Don't warn about remaining JVM controlled threads
                        continue;
                    }

                    // Skip threads that have already died
                    // 看看线程是否还存活
                    if (!thread.isAlive()) {                          // 4. 若线程已经不存活, 则直接 continue
                        continue;
                    }

                    // TimerThread can be stopped safely so treat separately
                    // "java.util.TimerThread" in Sun/Oracle JDK
                    // "java.util.Timer$TimerImpl" in Apache Harmony and in IBM JDK
                    if (thread.getClass().getName().startsWith("java.util.Timer") &&
                            clearReferencesStopTimerThreads) {
                        clearReferencesStopTimerThread(thread);       // 5. 定时线程 Timer 通过 反射清空其内部的 queue, 并且调用 cancel 来 stop 掉
                        continue;
                    }

                    if (isRequestThread(thread)) {                   // 6. 检测是请求线程的话保持不动 (如何判断出来呢, 呵呵 直接通过堆栈信息获取)
                        log.error(sm.getString("webappClassLoader.warnRequestThread",
                                getContextName(), thread.getName()));
                    } else {
                        log.error(sm.getString("webappClassLoader.warnThread",
                                getContextName(), thread.getName()));
                    }

                    // Don't try an stop the threads unless explicitly
                    // configured to do so
                    // 设置 clearReferencesStopThreads = false 直接 continue
                    if (!clearReferencesStopThreads) {
                        continue;
                    }

                    // If the thread has been started via an executor, try
                    // shutting down the executor
                    boolean usingExecutor = false;                  // 7. 若是通过线程池来启动的线程, 则直接调用线程池的 shutdownNow 来进行停止线程池
                    try {

                        // Runnable wrapped by Thread
                        // "target" in Sun/Oracle JDK
                        // "runnable" in IBM JDK
                        // "action" in Apache Harmony
                        Object target = null;
                        for (String fieldName : new String[] { "target",
                                "runnable", "action" }) {
                            try {
                                Field targetField = thread.getClass()
                                        .getDeclaredField(fieldName);
                                targetField.setAccessible(true);
                                target = targetField.get(thread);
                                break;
                            } catch (NoSuchFieldException nfe) {
                                continue;
                            }
                        }

                        // "java.util.concurrent" code is in public domain,
                        // so all implementations are similar
                        if (target != null &&                                       // 8. 若是线程池里面的线程, 则直接调用 ThreadPoolExecutor.shutdownNow()
                                target.getClass().getCanonicalName() != null
                                && target.getClass().getCanonicalName().equals(
                                "java.util.concurrent.ThreadPoolExecutor.Worker")) {
                            Field executorField =
                                target.getClass().getDeclaredField("this$0");     // 9. 获取线程池
                            executorField.setAccessible(true);
                            Object executor = executorField.get(target);
                            if (executor instanceof ThreadPoolExecutor) {
                                ((ThreadPoolExecutor) executor).shutdownNow();      // 10. 停止线程池
                                usingExecutor = true;
                            }
                        }
                    } catch (SecurityException e) {
                        log.warn(sm.getString(
                                "webappClassLoader.stopThreadFail",
                                thread.getName(), getContextName()), e);
                    } catch (NoSuchFieldException e) {
                        log.warn(sm.getString(
                                "webappClassLoader.stopThreadFail",
                                thread.getName(), getContextName()), e);
                    } catch (IllegalArgumentException e) {
                        log.warn(sm.getString(
                                "webappClassLoader.stopThreadFail",
                                thread.getName(), getContextName()), e);
                    } catch (IllegalAccessException e) {
                        log.warn(sm.getString(
                                "webappClassLoader.stopThreadFail",
                                thread.getName(), getContextName()), e);
                    }

                    if (usingExecutor) {
                        // Executor may take a short time to stop all the
                        // threads. Make a note of threads that should be
                        // stopped and check them at the end of the method.
                                                                                     // 11. 如果是 ThreadPoolExecutor.shutdownNow 需要一段时间才能停止下来, 将线程加入到 executorThreadsToStop, 接下来一个一个遍历线程, 若线程还存活, 则直接调用线程的 stop 方法
                        executorThreadsToStop.add(thread);
                    } else {
                        // This method is deprecated and for good reason. This
                        // is very risky code but is the only option at this
                        // point. A *very* good reason for apps to do this
                        // clean-up themselves.
                        thread.stop();
                    }
                }
            }
        }

        // If thread stopping is enabled, executor threads should have been
        // stopped above when the executor was shut down but that depends on the
        // thread correctly handling the interrupt. Give all the executor
        // threads a few seconds shutdown and if they are still running
        // Give threads up to 2 seconds to shutdown
        int count = 0;
        for (Thread t : executorThreadsToStop) {                                    // 12. 确保线程是否全部都 stop 掉了
            while (t.isAlive() && count < 100) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    // Quit the while loop
                    break;
                }
                count++;
            }
            if (t.isAlive()) {
                // This method is deprecated and for good reason. This is
                // very risky code but is the only option at this point.
                // A *very* good reason for apps to do this clean-up
                // themselves.
                t.stop();                                                           // 13. 若线程还存活, 则最后执行 stop
            }
        }
    }


    /*
     * Look at a threads stack trace to see if it is a request thread or not. It
     * isn't perfect, but it should be good-enough for most cases.
     */
    private boolean isRequestThread(Thread thread) {

        StackTraceElement[] elements = thread.getStackTrace();

        if (elements == null || elements.length == 0) {
            // Must have stopped already. Too late to ignore it. Assume not a
            // request processing thread.
            return false;
        }

        // Step through the methods in reverse order looking for calls to any
        // CoyoteAdapter method. All request threads will have this unless
        // Tomcat has been heavily modified - in which case there isn't much we
        // can do.
        for (int i = 0; i < elements.length; i++) {
            StackTraceElement element = elements[elements.length - (i+1)];      // 通过 堆栈来 判断是否是 CoyoteAdapter 的工作线程, 也就是Tomcat 的工作线程
            if ("org.apache.catalina.connector.CoyoteAdapter".equals(
                    element.getClassName())) {
                return true;
            }
        }
        return false;
    }


    private void clearReferencesStopTimerThread(Thread thread) {

        // Need to get references to:
        // in Sun/Oracle JDK:
        // - newTasksMayBeScheduled field (in java.util.TimerThread)
        // - queue field
        // - queue.clear()
        // in IBM JDK, Apache Harmony:
        // - cancel() method (in java.util.Timer$TimerImpl)
        /**
         * TimerThread 线程里面有个 queue , 我们只要 调用这个 queue 的clear方法才能清空线程
         */
        try {

            try {
                Field newTasksMayBeScheduledField =
                    thread.getClass().getDeclaredField("newTasksMayBeScheduled");
                newTasksMayBeScheduledField.setAccessible(true);
                Field queueField = thread.getClass().getDeclaredField("queue");
                queueField.setAccessible(true);

                Object queue = queueField.get(thread);

                Method clearMethod = queue.getClass().getDeclaredMethod("clear");
                clearMethod.setAccessible(true);

                synchronized(queue) {
                    newTasksMayBeScheduledField.setBoolean(thread, false);
                    clearMethod.invoke(queue);
                    queue.notify();  // In case queue was already empty.
                }

            }catch (NoSuchFieldException nfe){
                Method cancelMethod = thread.getClass().getDeclaredMethod("cancel");
                synchronized(thread) {
                    cancelMethod.setAccessible(true);
                    cancelMethod.invoke(thread);
                }
            }

            log.error(sm.getString("webappClassLoader.warnTimerThread",
                    getContextName(), thread.getName()));

        } catch (Exception e) {
            // So many things to go wrong above...
            Throwable t = ExceptionUtils.unwrapInvocationTargetException(e);
            ExceptionUtils.handleThrowable(t);
            log.warn(sm.getString(
                    "webappClassLoader.stopTimerThreadFail",
                    thread.getName(), getContextName()), t);
        }
    }

    /**
     * 参考资料
     * https://mp.weixin.qq.com/s?__biz=MzA4MTc3Nzk4NQ==&mid=2650076392&idx=1&sn=871d952a75b80eef073127698faf3536&chksm=878f90c6b0f819d0324748e3087f2de0e54fb6823ecbb138479f9aa38c5826585e62cf674784&mpshare=1&scene=23&srcid=0615MemRBS2YodaqKAZaXcjY#rd
     *
     * AppClassLoader -> 工作线程 Thread A -> Thread A.ThreadLocalMap -> Thread A.ThreadLocalMap.value (若这个 value 是 WebappClassLoader 加载的话), 那么 WebappClassLoader也就被强引用, WepappClassLoader 也就不能被卸载
     *
     */
    private void checkThreadLocalsForLeaks() {
        Thread[] threads = getThreads();

        try {
            // Make the fields in the Thread class that store ThreadLocals
            // accessible
            Field threadLocalsField =
                Thread.class.getDeclaredField("threadLocals");                 // 1. 当前线程缓存的数据
            threadLocalsField.setAccessible(true);
            Field inheritableThreadLocalsField =
                Thread.class.getDeclaredField("inheritableThreadLocals");    // 2. 当前线程创建时, 继承父线程下来的 ThreadLocalMap 里面的数据
            inheritableThreadLocalsField.setAccessible(true);
            // Make the underlying array of ThreadLoad.ThreadLocalMap.Entry objects
            // accessible
            Class<?> tlmClass = Class.forName("java.lang.ThreadLocal$ThreadLocalMap");
            Field tableField = tlmClass.getDeclaredField("table");
            tableField.setAccessible(true);
            Method expungeStaleEntriesMethod = tlmClass.getDeclaredMethod("expungeStaleEntries");   // 3. expungeStaleEntries 这个方法只能删除 key 是 null 的 Entry
            expungeStaleEntriesMethod.setAccessible(true);

            for (int i = 0; i < threads.length; i++) {
                Object threadLocalMap;
                if (threads[i] != null) {

                    // Clear the first map
                    threadLocalMap = threadLocalsField.get(threads[i]);
                    if (null != threadLocalMap){
                        expungeStaleEntriesMethod.invoke(threadLocalMap);                       // 4. expunge (擦去), stale (陈腐的) 其实就是删除 threadLocalMap 里面 key 是 null 的 Entry
                        checkThreadLocalMapForLeaks(threadLocalMap, tableField);                // 5. 这里只是判断是否 有可能引起内存泄露, 是的话, 就打印一下日志 (这里 我们其实可以参考 ThreadLocalLeakPreventionListener, 将线程池里的所有线程 renew/stop )
                    }

                    // Clear the second map
                    threadLocalMap =inheritableThreadLocalsField.get(threads[i]);
                    if (null != threadLocalMap){
                        expungeStaleEntriesMethod.invoke(threadLocalMap);                       // 6. 删除 inheritableThreadLocals 里面 key 是 null 的 Entry
                        checkThreadLocalMapForLeaks(threadLocalMap, tableField);                // 7. 这里只是判断是否 有可能引起内存泄露, 是的话, 就打印一下日志 (这里 我们其实可以参考 ThreadLocalLeakPreventionListener, 将线程池里的所有线程 renew/stop )
                    }
                }
            }
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.warn(sm.getString(
                    "webappClassLoader.checkThreadLocalsForLeaksFail",
                    getContextName()), t);
        }
    }


    /**
     * Analyzes the given thread local map object. Also pass in the field that
     * points to the internal table to save re-calculating it on every
     * call to this method.
     */
    private void checkThreadLocalMapForLeaks(Object map,                        // 检测 ThreadLocal 里面的数据是否有可能造成 WebappClassloader 内存泄露, 若有可能的话, 就直接打印日志
            Field internalTableField) throws IllegalAccessException,
            NoSuchFieldException {
        if (map != null) {
            Object[] table = (Object[]) internalTableField.get(map);
            if (table != null) {
                for (int j =0; j < table.length; j++) {
                    Object obj = table[j];
                    if (obj != null) {
                        boolean potentialLeak = false;
                        // Check the key
                        Object key = ((Reference<?>) obj).get();
                        if (this.equals(key) || loadedByThisOrChild(key)) {
                            potentialLeak = true;
                        }
                        // Check the value
                        Field valueField =
                                obj.getClass().getDeclaredField("value");
                        valueField.setAccessible(true);
                        Object value = valueField.get(obj);
                        if (this.equals(value) || loadedByThisOrChild(value)) {
                            potentialLeak = true;
                        }
                        if (potentialLeak) {
                            Object[] args = new Object[5];
                            args[0] = getContextName();
                            if (key != null) {
                                args[1] = getPrettyClassName(key.getClass());
                                try {
                                    args[2] = key.toString();
                                } catch (Exception e) {
                                    log.error(sm.getString(
                                            "webappClassLoader.checkThreadLocalsForLeaks.badKey",
                                            args[1]), e);
                                    args[2] = sm.getString(
                                            "webappClassLoader.checkThreadLocalsForLeaks.unknown");
                                }
                            }
                            if (value != null) {
                                args[3] = getPrettyClassName(value.getClass());
                                try {
                                    args[4] = value.toString();
                                } catch (Exception e) {
                                    log.error(sm.getString(
                                            "webappClassLoader.checkThreadLocalsForLeaks.badValue",
                                            args[3]), e);
                                    args[4] = sm.getString(
                                    "webappClassLoader.checkThreadLocalsForLeaks.unknown");
                                }
                            }
                            if (value == null) {
                                if (log.isDebugEnabled()) {
                                    log.debug(sm.getString(
                                            "webappClassLoader.checkThreadLocalsForLeaksDebug",
                                            args));
                                }
                            } else {
                                log.error(sm.getString(
                                        "webappClassLoader.checkThreadLocalsForLeaks",
                                        args));
                            }
                        }
                    }
                }
            }
        }
    }

    private String getPrettyClassName(Class<?> clazz) {
        String name = clazz.getCanonicalName();
        if (name==null){
            name = clazz.getName();
        }
        return name;
    }

    /**
     * @param o object to test, may be null
     * @return <code>true</code> if o has been loaded by the current classloader
     * or one of its descendants.
     */
    private boolean loadedByThisOrChild(Object o) {
        if (o == null) {
            return false;
        }

        Class<?> clazz;
        if (o instanceof Class) {
            clazz = (Class<?>) o;
        } else {
            clazz = o.getClass();
        }

        ClassLoader cl = clazz.getClassLoader();
        while (cl != null) {
            if (cl == this) {
                return true;
            }
            cl = cl.getParent();
        }

        if (o instanceof Collection<?>) {
            Iterator<?> iter = ((Collection<?>) o).iterator();
            try {
                while (iter.hasNext()) {
                    Object entry = iter.next();
                    if (loadedByThisOrChild(entry)) {
                        return true;
                    }
                }
            } catch (ConcurrentModificationException e) {
                log.warn(sm.getString(
                        "webappClassLoader", clazz.getName(), getContextName()),
                        e);
            }
        }
        return false;
    }

    /*
     * Get the set of current threads as an array.
     */
    private Thread[] getThreads() {
        // Get the current thread group
        ThreadGroup tg = Thread.currentThread().getThreadGroup();
        // Find the root thread group
        try {
            /**
             * 首先找出当前的 ThreadGroup, 然后
             * 不断的向上递归, 知道找到最父的线程组
             * 这个就是 JVM 的根线程组
             */
            while (tg.getParent() != null) {
                tg = tg.getParent();
            }
        } catch (SecurityException se) {
            String msg = sm.getString(
                    "webappClassLoader.getThreadGroupError", tg.getName());
            if (log.isDebugEnabled()) {
                log.debug(msg, se);
            } else {
                log.warn(msg);
            }
        }

        // 然后 搞一个Thread集合返回
        int threadCountGuess = tg.activeCount() + 50;
        Thread[] threads = new Thread[threadCountGuess];
        int threadCountActual = tg.enumerate(threads); // 将 线程组里面的 active 的线程 copy 到 threads 里面 (见原方法注解)
        // Make sure we don't miss any threads  直接将 threadCountGuess 扩大两倍, 再次 enumerate 出线程 (因为此时可能有 active 的线程 新增)
        while (threadCountActual == threadCountGuess) {
            threadCountGuess *=2;
            threads = new Thread[threadCountGuess];
            // Note tg.enumerate(Thread[]) silently ignores any threads that
            // can't fit into the array
            threadCountActual = tg.enumerate(threads);
        }

        return threads;
    }


    /**
     * This depends on the internals of the Sun JVM so it does everything by
     * reflection.
     */
    private void clearReferencesRmiTargets() {                      // 清空 RMI 相关的线程引用
        try {
            // Need access to the ccl field of sun.rmi.transport.Target
            Class<?> objectTargetClass =
                Class.forName("sun.rmi.transport.Target");
            Field cclField = objectTargetClass.getDeclaredField("ccl");
            cclField.setAccessible(true);

            // Clear the objTable map
            Class<?> objectTableClass =
                Class.forName("sun.rmi.transport.ObjectTable");
            Field objTableField = objectTableClass.getDeclaredField("objTable");
            objTableField.setAccessible(true);
            Object objTable = objTableField.get(null);
            if (objTable == null) {
                return;
            }

            // Iterate over the values in the table
            if (objTable instanceof Map<?,?>) {
                Iterator<?> iter = ((Map<?,?>) objTable).values().iterator();
                while (iter.hasNext()) {
                    Object obj = iter.next();
                    Object cclObject = cclField.get(obj);
                    if (this == cclObject) {
                        iter.remove();
                    }
                }
            }

            // Clear the implTable map
            Field implTableField = objectTableClass.getDeclaredField("implTable");
            implTableField.setAccessible(true);
            Object implTable = implTableField.get(null);
            if (implTable == null) {
                return;
            }

            // Iterate over the values in the table
            if (implTable instanceof Map<?,?>) {
                Iterator<?> iter = ((Map<?,?>) implTable).values().iterator();
                while (iter.hasNext()) {
                    Object obj = iter.next();
                    Object cclObject = cclField.get(obj);
                    if (this == cclObject) {
                        iter.remove();
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            log.info(sm.getString("webappClassLoader.clearRmiInfo",
                    getContextName()), e);
        } catch (SecurityException e) {
            log.warn(sm.getString("webappClassLoader.clearRmiFail",
                    getContextName()), e);
        } catch (NoSuchFieldException e) {
            log.warn(sm.getString("webappClassLoader.clearRmiFail",
                    getContextName()), e);
        } catch (IllegalArgumentException e) {
            log.warn(sm.getString("webappClassLoader.clearRmiFail",
                    getContextName()), e);
        } catch (IllegalAccessException e) {
            log.warn(sm.getString("webappClassLoader.clearRmiFail",
                    getContextName()), e);
        }
    }


    /**
     * Clear the {@link ResourceBundle} cache of any bundles loaded by this
     * class loader or any class loader where this loader is a parent class
     * loader. Whilst {@link ResourceBundle#clearCache()} could be used there
     * are complications around the
     * {@link org.apache.jasper.servlet.JasperLoader} that mean a reflection
     * based approach is more likely to be complete.
     *
     * The ResourceBundle is using WeakReferences so it shouldn't be pinning the
     * class loader in memory. However, it is. Therefore clear ou the
     * references.
     */
    private void clearReferencesResourceBundles() {     // 清除掉 ResourceBundle 里面的缓存集合 cacheList
        // Get a reference to the cache
        try {
            Field cacheListField =
                ResourceBundle.class.getDeclaredField("cacheList");
            cacheListField.setAccessible(true);

            // Java 6 uses ConcurrentMap
            // Java 5 uses SoftCache extends Abstract Map
            // So use Map and it *should* work with both
            Map<?,?> cacheList = (Map<?,?>) cacheListField.get(null);

            // Get the keys (loader references are in the key)
            Set<?> keys = cacheList.keySet();

            Field loaderRefField = null;

            // Iterate over the keys looking at the loader instances
            Iterator<?> keysIter = keys.iterator();

            int countRemoved = 0;

            while (keysIter.hasNext()) {
                Object key = keysIter.next();

                if (loaderRefField == null) {
                    loaderRefField =
                        key.getClass().getDeclaredField("loaderRef");
                    loaderRefField.setAccessible(true);
                }
                WeakReference<?> loaderRef =
                    (WeakReference<?>) loaderRefField.get(key);

                ClassLoader loader = (ClassLoader) loaderRef.get();

                while (loader != null && loader != this) {
                    loader = loader.getParent();
                }

                if (loader != null) {
                    keysIter.remove();
                    countRemoved++;
                }
            }

            if (countRemoved > 0 && log.isDebugEnabled()) {
                log.debug(sm.getString(
                        "webappClassLoader.clearReferencesResourceBundlesCount",
                        Integer.valueOf(countRemoved), getContextName()));
            }
        } catch (SecurityException e) {
            log.error(sm.getString(
                    "webappClassLoader.clearReferencesResourceBundlesFail",
                    getContextName()), e);
        } catch (NoSuchFieldException e) {
            if (Globals.IS_ORACLE_JVM) {
                log.error(sm.getString(
                        "webappClassLoader.clearReferencesResourceBundlesFail",
                        getContextName()), e);
            } else {
                log.debug(sm.getString(
                        "webappClassLoader.clearReferencesResourceBundlesFail",
                        getContextName()), e);
            }
        } catch (IllegalArgumentException e) {
            log.error(sm.getString(
                    "webappClassLoader.clearReferencesResourceBundlesFail",
                    getContextName()), e);
        } catch (IllegalAccessException e) {
            log.error(sm.getString(
                    "webappClassLoader.clearReferencesResourceBundlesFail",
                    getContextName()), e);
        }
    }


    /**
     * Find specified class in local repositories.
     *
     * @param name The binary name of the class to be loaded
     *
     * @return the loaded class, or null if the class isn't found
     */
    protected Class<?> findClassInternal(String name)
        throws ClassNotFoundException {

        if (!validate(name))                                    // 1. 对于 J2SE 下面的 Class, 不能通过这个 WebappClassloader 来进行加载
            throw new ClassNotFoundException(name);

        String path = binaryNameToPath(name, true);            // 2. 将类名转化成路径名称

        ResourceEntry entry = null;

        if (securityManager != null) {
            PrivilegedAction<ResourceEntry> dp =
                new PrivilegedFindResourceByName(name, path);
            entry = AccessController.doPrivileged(dp);
        } else {
            entry = findResourceInternal(name, path);          // 3. 调用 findResourceInternal  返回 class 的包装类 entry
        }

        if (entry == null)
            throw new ClassNotFoundException(name);

        Class<?> clazz = entry.loadedClass;                 // 4. 若程序已经生成了 class, 则直接返回
        if (clazz != null)
            return clazz;

        synchronized (this) {
            clazz = entry.loadedClass;
            if (clazz != null)
                return clazz;

            if (entry.binaryContent == null)
                throw new ClassNotFoundException(name);

            // Looking up the package
            String packageName = null;
            int pos = name.lastIndexOf('.');
            if (pos != -1)
                packageName = name.substring(0, pos);         // 5. 获取包名

            Package pkg = null;

            if (packageName != null) {
                pkg = getPackage(packageName);                // 6. 通过 包名 获取对应的 Package 对象
                // Define the package (if null)
                if (pkg == null) {                           // 7. 若还不存在, 则definePackage
                    try {
                        if (entry.manifest == null) {
                            definePackage(packageName, null, null, null, null,
                                    null, null, null);
                        } else {
                            definePackage(packageName, entry.manifest,
                                    entry.codeBase);
                        }
                    } catch (IllegalArgumentException e) {
                        // Ignore: normal error due to dual definition of package
                    }
                    pkg = getPackage(packageName);              // 8. 获取 Package
                }
            }

            if (securityManager != null) {                  // 9. 若程序运行配置了 securityManager, 则进行一些权限方面的检查

                // Checking sealing
                if (pkg != null) {
                    boolean sealCheck = true;
                    if (pkg.isSealed()) {
                        sealCheck = pkg.isSealed(entry.codeBase);
                    } else {
                        sealCheck = (entry.manifest == null)
                            || !isPackageSealed(packageName, entry.manifest);
                    }
                    if (!sealCheck)
                        throw new SecurityException
                            ("Sealing violation loading " + name + " : Package "
                             + packageName + " is sealed.");
                }

            }

            try {
                clazz = defineClass(name, entry.binaryContent, 0,                       // 10 最终调用 ClassLoader.defineClass 来将 class 对应的 二进制数据加载进来, 进行 "加载, 连接(解析, 验证, 准备), 初始化" 操作, 最终返回 class 对象
                        entry.binaryContent.length,
                        new CodeSource(entry.codeBase, entry.certificates));
            } catch (UnsupportedClassVersionError ucve) {
                throw new UnsupportedClassVersionError(
                        ucve.getLocalizedMessage() + " " +
                        sm.getString("webappClassLoader.wrongVersion",
                                name));
            }
            // Now the class has been defined, clear the elements of the local
            // resource cache that are no longer required.
            entry.loadedClass = clazz;
            entry.binaryContent = null;
            entry.codeBase = null;
            entry.manifest = null;
            entry.certificates = null;
            // Retain entry.source in case of a getResourceAsStream() call on
            // the class file after the class has been defined.
        }

        return clazz;                                                                   // 11. return 加载了的 clazz
    }


    private String binaryNameToPath(String binaryName, boolean withLeadingSlash) {
        StringBuilder path = new StringBuilder(
                1 + binaryName.length() + CLASS_FILE_SUFFIX.length());
        if (withLeadingSlash) {
            path.append('/');
        }
        path.append(binaryName.replace('.', '/'));              // 将 . 替换成 slash
        path.append(CLASS_FILE_SUFFIX);                       // 加上文件的尾缀
        return path.toString();
    }


    private String nameToPath(String name) {
        if (name.startsWith("/")) {
            return name;
        }
        StringBuilder path = new StringBuilder(
                1 + name.length());
        path.append('/');
        path.append(name);
        return path.toString();
    }


    /**
     * Find specified resource in local repositories.
     *
     * 从 WebappClassLoader 的 classpath 中加载类或资源文件
     *
     * @return the loaded resource, or null if the resource isn't found
     */
    protected ResourceEntry findResourceInternal(final String name, final String path) {

        if (!started) {
            log.info(sm.getString("webappClassLoader.stopped", name));
            return null;
        }

        if ((name == null) || (path == null))
            return null;

        ResourceEntry entry = resourceEntries.get(path);        // 1. resourceEntries 里面会存储所有已经加载了的 文件的信息
        if (entry != null)
            return entry;

        boolean isClassResource = path.endsWith(CLASS_FILE_SUFFIX);

        WebResource resource = null;

        boolean fileNeedConvert = false;

        resource = resources.getClassLoaderResource(path);      // 2. 通过 JNDI 来进行查找 资源 (想知道 resources 里面到底是哪些资源, 可以看 StandardRoot 类)

        if (!resource.exists()) {                                // 3. 若资源不存在, 则进行返回
            return null;
        }

        entry = new ResourceEntry();                             // 4. 若所查找的 class 对应的 ResourceEntry 不存在, 则进行构建一个
        entry.source = resource.getURL();
        entry.codeBase = entry.source;
        entry.lastModified = resource.getLastModified();

        if (needConvert) {
            if (path.endsWith(".properties")) {
                fileNeedConvert = true;
            }
        }

        /* Only cache the binary content if there is some content
         * available and either:
         * a) It is a class file since the binary content is only cached
         *    until the class has been loaded
         *    or
         * b) The file needs conversion to address encoding issues (see
         *    below)
         *
         * In all other cases do not cache the content to prevent
         * excessive memory usage if large resources are present (see
         * https://issues.apache.org/bugzilla/show_bug.cgi?id=53081).
         */
        if (isClassResource || fileNeedConvert) {                               // 5. 获取对应资源的二进制字节流, 当需要进行转码时, 进行相应的转码操作
            byte[] binaryContent = resource.getContent();
            if (binaryContent != null) {
                 if (fileNeedConvert) {
                    // Workaround for certain files on platforms that use
                    // EBCDIC encoding, when they are read through FileInputStream.
                    // See commit message of rev.303915 for details
                    // http://svn.apache.org/viewvc?view=revision&revision=303915
                    String str = new String(binaryContent);
                    try {
                        binaryContent = str.getBytes(StandardCharsets.UTF_8); // 6. 进行资源转码为 UTF-8
                    } catch (Exception e) {
                        return null;
                    }
                }
                entry.binaryContent = binaryContent;                        // 7. 获取资源对应的 二进制数据信息
                // The certificates and manifest are made available as a side
                // effect of reading the binary content
                entry.certificates = resource.getCertificates();            // 8. 获取资源的证书
            }
        }
        entry.manifest = resource.getManifest();

        if (isClassResource && entry.binaryContent != null &&
                this.transformers.size() > 0) {
            // If the resource is a class just being loaded, decorate it
            // with any attached transformers
            String className = name.endsWith(CLASS_FILE_SUFFIX) ?
                    name.substring(0, name.length() - CLASS_FILE_SUFFIX.length()) : name;
            String internalName = className.replace(".", "/");

            for (ClassFileTransformer transformer : this.transformers) {
                try {
                    byte[] transformed = transformer.transform(
                            this, internalName, null, null, entry.binaryContent
                    );
                    if (transformed != null) {
                        // 设置 二进制设置到 ResourceEntry
                        entry.binaryContent = transformed;
                    }
                } catch (IllegalClassFormatException e) {
                    log.error(sm.getString("webappClassLoader.transformError", name), e);
                    return null;
                }
            }
        }

        // Add the entry in the local resource repository
        synchronized (resourceEntries) {                                        // 9. 将生成的 entry 放入 resourceEntries 中
            // Ensures that all the threads which may be in a race to load
            // a particular class all end up with the same ResourceEntry
            // instance
            ResourceEntry entry2 = resourceEntries.get(path);
            if (entry2 == null) {
                // 向本地资源缓存注册 ResourceEntry
                resourceEntries.put(path, entry);
            } else {
                entry = entry2;
            }
        }

        return entry;
    }


    /**
     * Returns true if the specified package name is sealed according to the
     * given manifest.
     */
    protected boolean isPackageSealed(String name, Manifest man) {

        String path = name.replace('.', '/') + '/';
        Attributes attr = man.getAttributes(path);
        String sealed = null;
        if (attr != null) {
            sealed = attr.getValue(Name.SEALED);
        }
        if (sealed == null) {
            if ((attr = man.getMainAttributes()) != null) {
                sealed = attr.getValue(Name.SEALED);
            }
        }
        return "true".equalsIgnoreCase(sealed);

    }


    /**
     * Finds the resource with the given name if it has previously been
     * loaded and cached by this class loader, and return an input stream
     * to the resource data.  If this resource has not been cached, return
     * <code>null</code>.
     *
     * @param name Name of the resource to return
     */
    protected InputStream findLoadedResource(String name) {                         // 查找资源时, 从本地资源库里面进行查找

        String path = nameToPath(name);

        ResourceEntry entry = resourceEntries.get(path);
        if (entry != null) {
            if (entry.binaryContent != null)
                return new ByteArrayInputStream(entry.binaryContent);
            else {
                try {
                    return entry.source.openStream();
                } catch (IOException ioe) {
                    // Ignore
                }
            }
        }
        return null;
    }


    /**
     * Finds the class with the given name if it has previously been
     * loaded and cached by this class loader, and return the Class object.
     * If this class has not been cached, return <code>null</code>.
     *
     * @param name The binary name of the resource to return
     */
    protected Class<?> findLoadedClass0(String name) {                  // 1. 根据加载的 className 来加载 类

        String path = binaryNameToPath(name, true);                     // 2. 将 类名转化成 类的全名称

        ResourceEntry entry = resourceEntries.get(path);              // 3. resourceEntries 是 WebappClassLoader 加载好的 class 存放的地址
        if (entry != null) {
            return entry.loadedClass;                                 // 4. 将 加载好的 class 直接返回
        }
        return null;
    }


    /**
     * Refresh the system policy file, to pick up eventual changes.
     */
    protected void refreshPolicy() {

        try {
            // The policy file may have been modified to adjust
            // permissions, so we're reloading it when loading or
            // reloading a Context
            Policy policy = Policy.getPolicy();
            policy.refresh();
        } catch (AccessControlException e) {
            // Some policy files may restrict this, even for the core,
            // so this exception is ignored
        }

    }


    /**
     * Filter classes.
     *
     * @param name class name
     * @return true if the class should be filtered
     *
     * 这个函数主要是 对加载的包进行过滤 具体看 packageTriggersDeny 与 packageTriggersPermit
     * 返回 false 表示 能进行加载
     * 返回 true 表示 不能被加载
     */
    protected synchronized boolean filter(String name) {

        if (name == null)
            return false;

        // Looking up the package
        String packageName = null;
        int pos = name.lastIndexOf('.');
        if (pos != -1)
            packageName = name.substring(0, pos);
        else
            return false;

        packageTriggersPermit.reset(packageName);
        if (packageTriggersPermit.lookingAt()) {
            return false;
        }

        packageTriggersDeny.reset(packageName);
        if (packageTriggersDeny.lookingAt()) {
            return true;
        }

        return false;
    }


    /**
     * Validate a classname. As per SRV.9.7.2, we must restrict loading of
     * classes from J2SE (java.*) and most classes of the servlet API
     * (javax.servlet.*). That should enhance robustness and prevent a number
     * of user error (where an older version of servlet.jar would be present
     * in /WEB-INF/lib).
     *
     * @param name class name
     * @return true if the name is valid
     */
    protected boolean validate(String name) {

        // Need to be careful with order here
        if (name == null) {
            // Can't load a class without a name
            return false;
        }
        if (name.startsWith("java.")) {
            // Must never load java.* classes
            return false;
        }
        if (name.startsWith("javax.servlet.jsp.jstl")) {
            // OK for web apps to package JSTL
            return true;
        }
        if (name.startsWith("javax.servlet.")) {
            // Web apps should never package any other Servlet or JSP classes
            return false;
        }
        if (name.startsWith("javax.el")) {
            // Must never load javax.el.* classes
            return false;
        }

        // Assume everything else is OK
        return true;

    }
}

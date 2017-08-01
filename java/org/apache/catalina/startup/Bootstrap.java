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
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.catalina.Globals;
import org.apache.catalina.security.SecurityClassLoad;
import org.apache.catalina.startup.ClassLoaderFactory.Repository;
import org.apache.catalina.startup.ClassLoaderFactory.RepositoryType;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;


/**
 * Bootstrap loader for Catalina.  This application constructs a class loader
 * for use in loading the Catalina internal classes (by accumulating all of the
 * JAR files found in the "server" directory under "catalina.home"), and
 * starts the regular execution of the container.  The purpose of this
 * roundabout approach is to keep the Catalina internal classes (and any
 * other classes they depend on, such as an XML parser) out of the system
 * class path and therefore not visible to application level classes.
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 *
 * 参考资料
 * http://blog.csdn.net/fjslovejhl/article/details/19995383
 *
 */
public final class Bootstrap {

    private static final Log log = LogFactory.getLog(Bootstrap.class);

    /**
     * Daemon object used by main.
     */
    private static Bootstrap daemon = null;                 // 当前类型的一个引用
    // 这两个其实一般被赋值为 tomcta 的根路径 (可以通过 catalina.sh 里面获取得到)
    private static final File catalinaBaseFile;
    private static final File catalinaHomeFile;

    private static final Pattern PATH_PATTERN = Pattern.compile("(\".*?\")|(([^,])*)");

    static {    // 进行一些类型的 初始化
        // Will always be non-null
        String userDir = System.getProperty("user.dir");

        // Home first
        String home = System.getProperty(Globals.CATALINA_HOME_PROP);           // 在 Java 启动命令行里面会配置对应的 catalina_home (其实就是通过 pwd 命令来获取, 见 CATALINA_HOME=`cd "$PRGDIR/.." >/dev/null; pwd` 来获取)
        File homeFile = null;

        if (home != null) {
            File f = new File(home);
            try {
                homeFile = f.getCanonicalFile();
            } catch (IOException ioe) {
                homeFile = f.getAbsoluteFile();
            }
        }

        if (homeFile == null) {
            // First fall-back. See if current directory is a bin directory
            // in a normal Tomcat install
            File bootstrapJar = new File(userDir, "bootstrap.jar");             // Tomcat 的启动 jar 包

            if (bootstrapJar.exists()) {
                File f = new File(userDir, "..");
                try {
                    homeFile = f.getCanonicalFile();
                } catch (IOException ioe) {
                    homeFile = f.getAbsoluteFile();
                }
            }
        }

        if (homeFile == null) {
            // Second fall-back. Use current directory
            File f = new File(userDir);                                             // 当前陈旭的根路径
            try {
                homeFile = f.getCanonicalFile();
            } catch (IOException ioe) {
                homeFile = f.getAbsoluteFile();
            }
        }

        catalinaHomeFile = homeFile;
        System.setProperty(
                Globals.CATALINA_HOME_PROP, catalinaHomeFile.getPath());

        // Then base
        String base = System.getProperty(Globals.CATALINA_BASE_PROP);
        if (base == null) {
            catalinaBaseFile = catalinaHomeFile;                            // 从这里可以看出 catalinaBase = catalinaHome
        } else {
            File baseFile = new File(base);
            try {
                baseFile = baseFile.getCanonicalFile();
            } catch (IOException ioe) {
                baseFile = baseFile.getAbsoluteFile();
            }
            catalinaBaseFile = baseFile;
        }
        System.setProperty(
                Globals.CATALINA_BASE_PROP, catalinaBaseFile.getPath());
    }

    // -------------------------------------------------------------- Variables


    /**
     * Daemon reference.
     * 当前 tomcat 的后台,  org.apache.catalina.startup.Catalina 对象, 它才是用于负者具体的 server 的启动
     */
    private Object catalinaDaemon = null;

    // 3 个层次的 classLoader, commonLoader 是下面两个 loader 的父 classLoader,
    protected ClassLoader commonLoader = null;          // tomcat 与 app 都可见
    protected ClassLoader catalinaLoader = null;       // 只能 tomcat 可见
    protected ClassLoader sharedLoader = null;          // 只有当前的 webApp 运用可见


    // -------------------------------------------------------- Private Methods

    /**
     * 1. BootstrapClassLoader	: 系统类加载器
     * 2. ExtClassLoader 		: 扩展类加载器
     * 3. AppClassLoader 		: 普通类加载器
     #下面是 这几个 Classloader 是 Tomcat 对老版本的兼容
     * 4. commonLoader     	: Tomcat 通用类加载器, 加载的资源可被 Tomcat 和 所有的 Web 应用程序共同获取
     * 5. catalinaLoader   	: Tomcat 类加载器, 加载的资源只能被 Tomcat 获取(但 所有 WebappClassLoader 不能获取到 catalinaLoader 加载的类)
     * 6. sharedLoader     	: Tomcat 各个Context的父加载器, 这个类是所有 WebappClassLoader 的父类, sharedLoader 所加载的类将被所有的 WebappClassLoader 共享获取
     *
     * 这个版本 (Tomcat 8.x.x) 中, 默认情况下 commonLoader = catalinaLoader = sharedLoader
     * (PS: 为什么这样设计, 主要这样这样设计 ClassLoader 的层级后, WebAppClassLoader 就能直接访问 tomcat 的公共资源, 若需要tomcat 有些资源不让 WebappClassLoader 加载, 则直接在 ${catalina.base}/conf/catalina.properties 中的 server.loader 配置一下 加载路径就可以了)
     */
    private void initClassLoaders() {
        ClassLoader classLoader = ClassLoader.getSystemClassLoader();
        try {                                                                   // 1. 补充: createClassLoader 中代码最后调用 new URLClassLoader(array) 来生成 commonLoader, 此时 commonLoader.parent = null,  则采用的是默认的策略 Launcher.AppClassLoader
            commonLoader = createClassLoader("common", null);               // 2. 根据 catalina.properties 指定的 加载jar包的目录, 生成对应的 URLClassLoader( 加载 Tomcat 中公共jar包的 classLoader, 这里的 parent 参数是 null, 最终 commonLoader.parent 是 URLClassLoader)
            if( commonLoader == null ) {                                     // 3. 若 commonLoader = null, 则说明在 catalina.properties 里面 common.loader 是空的
                // no config file, default to this loader - we might be in a 'single' env.
                commonLoader=this.getClass().getClassLoader();
            }
            catalinaLoader = createClassLoader("server", commonLoader);   // 4. 将 commonClassLoader 作为父 ClassLoader, 生成 catalinaLoader，这个类就是加载 Tomcat bootstrap.jar, tomcat-juli.jar 包的 classLoader (PS; 在 catalina.properties 里面 server.loader 是空的， 则代码中将直接将 commonLoader 赋值给 catalinaLoader)
            sharedLoader = createClassLoader("shared", commonLoader);     // 5. 将 commonClassLoader 作为父 ClassLoader, 生成 sharedLoader, 这个类最后会作为所有 WebappClassLoader 的父类 ( PS: 因为 catalina.properties 里面 shared.loader 是空的, 所以代码中直接将 commonLoader 赋值给 sharedLoader)
        } catch (Throwable t) {
            handleThrowable(t);
            log.error("Class loader creation threw exception", t);
            System.exit(1);
        }
    }


    private ClassLoader createClassLoader(String name, ClassLoader parent)
        throws Exception {

        String value = CatalinaProperties.getProperty(name + ".loader");        // 获取 catalina.properties 里面 common.loader 对应的数据 (PS: 这里加载 properties 信息的位置是 ${catalina.base}/conf/catalina.properties)
        if ((value == null) || (value.equals("")))                              // 注意 前方高能(在 ${catalina.base}/conf/catalina.properties 中 server.loader 与 shared.loader 是空的, 那意味着什么呢? 意味着 这里将直接返回 parent, 也就是 catalinaLoadeer = commonLoader = sharedLoader, Tomcat 下面的 WebAppClassLoader 能获取 Tomcat 自身 ${catalina.base}/lib 下面的 class )
            return parent;

        value = replace(value);                                                  // 替换 ${catalina.base}, 获取绝对的路径

        List<Repository> repositories = new ArrayList<>();

        String[] repositoryPaths = getPaths(value);

        for (String repository : repositoryPaths) {                             // 生成 ClassLoader 加载资源的 repositories
            // Check for a JAR URL repository
            try {
                @SuppressWarnings("unused")
                URL url = new URL(repository);
                repositories.add(
                        new Repository(repository, RepositoryType.URL));
                continue;
            } catch (MalformedURLException e) {
                // Ignore
            }

            // Local repository
            if (repository.endsWith("*.jar")) {
                repository = repository.substring
                    (0, repository.length() - "*.jar".length());
                repositories.add(
                        new Repository(repository, RepositoryType.GLOB));
            } else if (repository.endsWith(".jar")) {
                repositories.add(
                        new Repository(repository, RepositoryType.JAR));
            } else {
                repositories.add(
                        new Repository(repository, RepositoryType.DIR));
            }
        }

        return ClassLoaderFactory.createClassLoader(repositories, parent);
    }


    /**
     * System property replacement in the given string.
     *
     * @param str The original string
     * @return the modified string
     */
    protected String replace(String str) {
        // Implementation is copied from ClassLoaderLogManager.replace(),
        // but added special processing for catalina.home and catalina.base.
        String result = str;
        int pos_start = str.indexOf("${");
        if (pos_start >= 0) {
            StringBuilder builder = new StringBuilder();
            int pos_end = -1;
            while (pos_start >= 0) {
                builder.append(str, pos_end + 1, pos_start);
                pos_end = str.indexOf('}', pos_start + 2);
                if (pos_end < 0) {
                    pos_end = pos_start - 1;
                    break;
                }
                String propName = str.substring(pos_start + 2, pos_end);
                String replacement;
                if (propName.length() == 0) {
                    replacement = null;
                } else if (Globals.CATALINA_HOME_PROP.equals(propName)) {
                    replacement = getCatalinaHome();
                } else if (Globals.CATALINA_BASE_PROP.equals(propName)) {
                    replacement = getCatalinaBase();
                } else {
                    replacement = System.getProperty(propName);
                }
                if (replacement != null) {
                    builder.append(replacement);
                } else {
                    builder.append(str, pos_start, pos_end + 1);
                }
                pos_start = str.indexOf("${", pos_end + 1);
            }
            builder.append(str, pos_end + 1, str.length());
            result = builder.toString();
        }
        return result;
    }


    /**
     * Initialize daemon.
     * 初始化当前的 tomcat 中的 ClassLoader, 主要是创建 org.apache.catalina.start.Catalina 对象, 并且设置它的 classLoader 为catalinaLoader
     */
    public void init() throws Exception {

        initClassLoaders();                                                 // 1.初始化 commonClassLoader, catalinaClassLoader, sharedClassLoader (其中commonClassLoader作为另外两个 classLoader 的 parent, 并且其加载了 ${catalina.base}/bin 下面的公共 jar 包) (PS: catalina.base 其实就是 Tomcat 的安装目录, catalina.home 与 catalina.base 其实是一样的)

        Thread.currentThread().setContextClassLoader(catalinaLoader);    // 2. 设置当前线程的 classLoader 为 catalinaClassLoader(这是对 Tomcat 运用程序来说的); 对应的 StandardContext 来说, 其对应的  WebappClassLoader

        SecurityClassLoad.securityClassLoad(catalinaLoader);             // 3. 让 catalinaLoader 来加载 Tomcat 下面几个核心的 类 (PS: 这里用 catalinaClassLoader 来加载的, 意味着 sharedClassLoader 是获取不到)

        // Load our startup class and call its process() method
        if (log.isDebugEnabled())
            log.debug("Loading startup class");
        Class<?> startupClass =
            catalinaLoader.loadClass
            ("org.apache.catalina.startup.Catalina");               // 4. 加载 org.apache.catalina.startup.Catalina 类型 (PS: 这里的 catalina 其实没有在 Bootstrap.jar 里面)
        Object startupInstance = startupClass.newInstance();              // 5. 创建 org.apache.catalina.startup.Catalina 对象 (PS: 这里为什么要用 反射的方式来生成 Tomcat 启动实例, 主要是为以后, 出现一个 catalina2 时, 只需要这里一个配置, 就开启另外一种 Tomcat 启动模式)

        // Set the shared extensions class loader                         // 6. 设置 CataLina 类的 parentClassLoader (PS: 这是啥用??? 我们瞧瞧发现, Catalina.parentClassLoader 会 StandardContext 启动时设置 WebappLoader 的parentClassLoader 时用到; 这里用的就是 sharedClassLoader, 意味着 每个 WebAppClassLoader 的 parentClassLoader 都是 sharedClassLoader, 将代码 #new WebappLoader(getParentClassLoader())#)
        if (log.isDebugEnabled())
            log.debug("Setting startup class properties");
        String methodName = "setParentClassLoader";
        Class<?> paramTypes[] = new Class[1];
        paramTypes[0] = Class.forName("java.lang.ClassLoader");
        Object paramValues[] = new Object[1];
        paramValues[0] = sharedLoader;
        Method method =
            startupInstance.getClass().getMethod(methodName, paramTypes);
        method.invoke(startupInstance, paramValues);                     // 7. 调用刚刚创建的 org.apache.catalina.startup.Catalina 对象的 setParentClassLoader 设置 classLoader

        catalinaDaemon = startupInstance;                              // 8. 将这个启动的实例保存下来
    }


    /**
     * Load daemon.
     */
    private void load(String[] arguments)
        throws Exception {

        // Call the load() method
        String methodName = "load";
        Object param[];
        Class<?> paramTypes[];
        if (arguments==null || arguments.length==0) {
            paramTypes = null;
            param = null;
        } else {
            paramTypes = new Class[1];
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            param[0] = arguments;
        }
        Method method =
            catalinaDaemon.getClass().getMethod(methodName, paramTypes);
        if (log.isDebugEnabled())
            log.debug("Calling startup class " + method);
        method.invoke(catalinaDaemon, param);

    }


    /**
     * getServer() for configtest
     */
    private Object getServer() throws Exception {

        String methodName = "getServer";
        Method method =
            catalinaDaemon.getClass().getMethod(methodName);
        return method.invoke(catalinaDaemon);

    }


    // ----------------------------------------------------------- Main Program


    /**
     * Load the Catalina daemon.
     */
    public void init(String[] arguments)
        throws Exception {

        init();
        load(arguments);

    }


    /**
     * Start the Catalina daemon.
     */
    public void start()
        throws Exception {
        if( catalinaDaemon==null ) init();                                              // 初始化 catalinaDaemon, 其实主要初始化 org.apache.startup.Catalina 对象

        Method method = catalinaDaemon.getClass().getMethod("start", (Class [] )null);
        method.invoke(catalinaDaemon, (Object [])null);                                 // 调用 org.apache.catalina.startup.Catalina 对象的start方法

    }


    /**
     * Stop the Catalina Daemon.
     */
    public void stop()
        throws Exception {

        Method method = catalinaDaemon.getClass().getMethod("stop", (Class [] ) null);
        method.invoke(catalinaDaemon, (Object [] ) null);

    }


    /**
     * Stop the standalone server.
     */
    public void stopServer()
        throws Exception {

        Method method =
            catalinaDaemon.getClass().getMethod("stopServer", (Class []) null);
        method.invoke(catalinaDaemon, (Object []) null);

    }


   /**
     * Stop the standalone server.
     */
    public void stopServer(String[] arguments)
        throws Exception {

        Object param[];
        Class<?> paramTypes[];
        if (arguments==null || arguments.length==0) {
            paramTypes = null;
            param = null;
        } else {
            paramTypes = new Class[1];
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            param[0] = arguments;
        }
        Method method =
            catalinaDaemon.getClass().getMethod("stopServer", paramTypes);
        method.invoke(catalinaDaemon, param);

    }


    /**
     * Set flag.
     */
    public void setAwait(boolean await)
        throws Exception {

        Class<?> paramTypes[] = new Class[1];
        paramTypes[0] = Boolean.TYPE;
        Object paramValues[] = new Object[1];
        paramValues[0] = Boolean.valueOf(await);
        Method method =
            catalinaDaemon.getClass().getMethod("setAwait", paramTypes);
        method.invoke(catalinaDaemon, paramValues);

    }

    public boolean getAwait()
        throws Exception
    {
        Class<?> paramTypes[] = new Class[0];
        Object paramValues[] = new Object[0];
        Method method =
            catalinaDaemon.getClass().getMethod("getAwait", paramTypes);
        Boolean b=(Boolean)method.invoke(catalinaDaemon, paramValues);
        return b.booleanValue();
    }


    /**
     * Destroy the Catalina Daemon.
     */
    public void destroy() {

        // FIXME

    }


    /**
     * Main method and entry point when starting Tomcat via the provided
     * scripts.
     *
     * @param args Command line arguments to be processed
     */
    /**操作步骤
     * 1. 创建 Bootstrap 对象
     * 2. 调用bootstrap做一些初始化的操作(主要是 classloader 的创建)
     * 3. 更具命令行传来的参数start -> 先后通过反射来调用 Catalina 类的 load, 与 start方法
     */
    public static void main(String args[]) {

        if (daemon == null) {
            // Don't set daemon until init() has completed
            Bootstrap bootstrap = new Bootstrap();                      // 1.创建当前的 Bootstrap 类型的对象
            try {
                bootstrap.init();                                       // 2. 初始化整个系统中用到的几个 classLoader, 并设置父子关系, 创建 org.apache.catalina.startup.Catalina 对象, 设置其 parentClassLoader 为shareClassLoader, 为创建 WebappClassLoader 做准备
            } catch (Throwable t) {
                handleThrowable(t);
                t.printStackTrace();
                return;
            }
            daemon = bootstrap;                                         // 3. 保存当前引用到静态变量
        } else {
            // When running as a service the call to stop will be on a new
            // thread so make sure the correct class loader is used to prevent
            // a range of class not found exceptions.
            Thread.currentThread().setContextClassLoader(daemon.catalinaLoader);
        }
                                                                       // 4. 程序运行到这边时 Thread.currentThread().contextClassLoader 就是 catalinaClassLoader 了(在 bootstrap.init() 里面进行了设置), 下面的所有加载 class操作, 都是由这个 classloader 来进行加载 (PS: 则在 Tomcat 8.0.x 里面, 这里的 catalinaClassLoader 其实就是 commonClassLoader)
        try {
            String command = "start";                                 // 5. 默认命令参数
            if (args.length > 0) {                                    // 6. 这里可能是其他的参数, 但默认命令就是 start
                command = args[args.length - 1];
            }

            if (command.equals("startd")) {                          // 7. 启动 Tomcat (PS: 这种方式, Tomcat 启动过后会立刻 关闭, 主要是 Catalina 里面的 await = false )
                args[args.length - 1] = "start";
                daemon.load(args);
                daemon.start();
            } else if (command.equals("stopd")) {                   // 8. 停止 Tomcat
                args[args.length - 1] = "stop";
                daemon.stop();
            } else if (command.equals("start")) {
                daemon.setAwait(true);                                // 9. 通过更改 Catalina 里面的 await 值, 在 Tomcat start 执行时, 程序会 hold 住, 直到有向 Tomcat 发送 stop 命令, 程序才会停止 (详情见 Catalina.start() 方法最后那部分)
                daemon.load(args);                                    // 10. 直接调用 Catalina.load 方法, 进行初始化 各个文件, 命名服务, 用 Digester 来解析 XML 文件, 并且  init Tomcat 容器里面的各个组件
                daemon.start();                                       // 11. 启动当前 bootstrap 对象, 其实主要是通过反射调用前面生成的 org.apache.catalina.startup.Catalina 的 start 方法
            } else if (command.equals("stop")) {                    // 12. 停止 Tomcat (最终还是调用 StandardServer.stop())
                daemon.stopServer(args);
            } else if (command.equals("configtest")) {             // 13. 这个只是 将 Tomcat 的配置文件加载进来, 通过反射调用 Catalina.init, 从而检测 程序对应的配置文件是否正确
                daemon.load(args);
                if (null==daemon.getServer()) {
                    System.exit(1);
                }
                System.exit(0);
            } else {
                log.warn("Bootstrap: command \"" + command + "\" does not exist.");
            }
        } catch (Throwable t) {                                     // 抛出异常一直退出
            // Unwrap the Exception for clearer error reporting
            if (t instanceof InvocationTargetException &&
                    t.getCause() != null) {
                t = t.getCause();
            }
            handleThrowable(t);
            t.printStackTrace();
            System.exit(1);
        }
    }


    /**
     * Obtain the name of configured home (binary) directory. Note that home and
     * base may be the same (and are by default).
     */
    public static String getCatalinaHome() {
        return catalinaHomeFile.getPath();
    }


    /**
     * Obtain the name of the configured base (instance) directory. Note that
     * home and base may be the same (and are by default). If this is not set
     * the value returned by {@link #getCatalinaHome()} will be used.
     */
    public static String getCatalinaBase() {
        return catalinaBaseFile.getPath();
    }


    /**
     * Obtain the configured home (binary) directory. Note that home and
     * base may be the same (and are by default).
     */
    public static File getCatalinaHomeFile() {
        return catalinaHomeFile;
    }


    /**
     * Obtain the configured base (instance) directory. Note that
     * home and base may be the same (and are by default). If this is not set
     * the value returned by {@link #getCatalinaHomeFile()} will be used.
     */
    public static File getCatalinaBaseFile() {
        return catalinaBaseFile;
    }


    // Copied from ExceptionUtils since that class is not visible during start
    private static void handleThrowable(Throwable t) {
        if (t instanceof ThreadDeath) {
            throw (ThreadDeath) t;
        }
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        }
        // All other instances of Throwable will be silently swallowed
    }


    // Protected for unit testing
    protected static String[] getPaths(String value) {

        List<String> result = new ArrayList<>();
        Matcher matcher = PATH_PATTERN.matcher(value);

        while (matcher.find()) {
            String path = value.substring(matcher.start(), matcher.end());

            path = path.trim();

            if (path.startsWith("\"") && path.length() > 1) {
                path = path.substring(1, path.length() - 1);
                path = path.trim();
            }

            if (path.length() == 0) {
                continue;
            }

            result.add(path);
        }
        return result.toArray(new String[result.size()]);
    }
}

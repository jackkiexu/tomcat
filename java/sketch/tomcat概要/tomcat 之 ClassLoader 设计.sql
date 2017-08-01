
"Why, Why, Why 好好的一个ClassLoader, 为啥Tomcat要搞这么多事情, 直接用默认的不久好了"
这是自己当时接触 Tomcat 中的ClassLoader时心中冒出的疑惑;

1. 热部署功能或项目(PS: 热部署JSP, Context)
2. 隔离资源的访问
	(1) 不同的 Context 之间不能相互访问对方加载的资源, 举例: 可能Context1用Spring3.1, 而 Context2用Spring4.1 若用同一个Classloader 则遇到 spring 的class只能加载一份, 就会出现想用 spring4.1里面的 AnnotationUtils, 但是 classLoader 其实加载的是 spring 3.1里面的类, 这样很有可能出现 NoSuchMethodError 异常
	(2) 不让 Context 加载类不能访问到 Tomcat 容器自身的类


1. BootstrapClassLoader
	加载路径: System.getProperty("java.class.path") 或直接通过 -Xbootclasspath 指定
	用C语言写的
	sun.misc.Launcher.getBootstrapClassPath().getURLs()

2. ExtClassLoader
	加载路径: System.getProperty("java.ext.dirs") 或直接通过 -Djava.ext.dirs 指定
	继承 URLClassLoader
	((URLClassLoader)App.class.getClassLoader().getParent()).getURLs()


3. AppClassLoader
	加载路径: System.getProperty("sun.boot.class.path") 或直接通过 -cp, -classpath 指定
	继承 URLClassLoader
	((URLClassLoader)App.class.getClassLoader()).getURLs()
	通过 ClassLoader.getSystemClassLoader() 就可以获取 AppClassLoader, 自己写的程序中写的 ClassLoader(继承 URLClassLoader), 若不指定 parent, 默认的parent就是 AppClassLoader

AppClassLoader.getparent() = ExtClassLoader
ExtClassLoader.getParent() == null, 则直接通过 BootstrapClassLoader 来进行加载

ClassLoader 主要方法:

1. loadClass    方法 实现双亲委派模型
2. findClass    方法 根据Class名称获取Class路径, 然后调用 defineClass 进行加载到JVM 内存中
3. defineClass  方法 加Class文件的二进制字节码加载到JVM内存生成Class对象
4. resolveClass 方法 JVM规范里面指连接操作中的第三步操作, 实际上我们的平时使用的JDK并没有按照JVM的这个规范进行设计, 你在进行debug时, 发现这个 resolveClass 永远是 false

Class加载核心思想策略: lazy 加载

synchronized (getClassLoadingLock(name)) {				// 1. 通过一个ClassName对应一个 Object, 放到 ConcurrentHashMap 中， 最终通过 synchronized 实现并发加载
    Class<?> c = findLoadedClass(name);					// 2. 查看本 ClassLoader 是否加载过
    if (c == null) {
        try {
            if (parent != null) {						// 4. parent != null, 则通过父ClassLoader来进行加载 (加载的原则是: class 一定要在 URLClassPath 中)
                c = parent.loadClass(name, false);
            } else {
                c = findBootstrapClassOrNull(name);		// 5. parent == null, 则说明当前ClassLoader是ExtClassLoader, 直接通过 BootstrapClassLoader 来进行加载 (加载的原则是: class 一定要在 URLClassPath 中)
            }
        } catch (ClassNotFoundException e) {}
        if (c == null) {								// 6. delegate 父 ClassLoader 还没加载成功, 则用当前ClassLoader 来进行加载
            c = findClass(name);						// 7. 通过 findClass 在本 ClassLoader 的path 上进行查找 class, 转化成 byte[], 通过 defineClass 加载到内存中 (加载的原则是: class 一定要在 URLClassPath 中)
        }
    }
    if (resolve) {										// 8. 永远的 resolve = false, JVM规范指定是通过 resolveClass 方法实现 链接 操作的第三步, 实际我们的JVM上并没有实现这个操作
        resolveClass(c);
    }
    return c;
}


Class A {
	public void doSomething(){
		B b = new B();
		b.doSomething();
	}

	public static void main(String[] args){
		A a = new A();
		a.doSomething()
	}
}


执行命令 java -classpath: test.jar A

操作步骤
1. AClass = AppClassLoader.loadClass(A)									# 通过 AppClassLoader 加载类A
2. BClass = AClass.getClassLoader().loadClass(B) 						# 其中通过 AClass.getClassLoader.getResource("/" + B.class.getName().replace(".", "/") + ".class") 查找 B 的Resource
3. BClass.getDeclaredMethod("doSomething").invoke(BClass.newInstance())	# 直接激活方法 doSomething


1. BootstrapClassLoader	: 系统类加载器
2. ExtClassLoader 		: 扩展类加载器
3. AppClassLoader 		: 普通类加载器
#下面是 这几个 Classloader 是 Tomcat 对老版本的兼容
4. commonLoader     	: Tomcat 通用类加载器, 加载的资源可被 Tomcat 和 所有的 Web 应用程序共同获取
5. catalinaLoader   	: Tomcat 类加载器, 加载的资源只能被 Tomcat 获取(但 所有 WebappClassLoader 不能获取到 catalinaLoader 加载的类)
6. sharedLoader     	: Tomcat 各个Context的父加载器, 这个类是所有 WebappClassLoader 的父类, sharedLoader 所加载的类将被所有的 WebappClassLoader 共享获取

这个版本 (Tomcat 8.x.x) 中, 默认情况下 commonLoader = catalinaLoader = sharedLoader
(PS: 为什么这样设计, 主要这样这样设计 ClassLoader 的层级后, WebAppClassLoader 就能直接访问 tomcat 的公共资源, 若需要tomcat 有些资源不让 WebappClassLoader 加载, 则直接在 ${catalina.base}/conf/catalina.properties 中的 server.loader 配置一下 加载路径就可以了)



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


 当 Compiler 将 JSP 编译为 servlet 之后, 通过这个 JasperLoader 进行加载, 这个 JasperLoader 其实没有什么太多的功能, 主要就是继承
 URLClassLoader, 因为 jsp 编译之后的 servlet 是有位置的, JasperLoader 就会将 对应位置下的 class 进行加载


1. WebAppClassLoader 常见属性

protected final Matcher packageTriggersDeny = Pattern.compile(              			// 在 delegating = false 的情况下, 被这个正则匹配到的 class 不会被 WebappClassLoader 进行加载 (其实就是 Tomcat 中的代码不能被 WebappClassLoader 来加载)
        "^javax\\.el\\.|" +
        "^javax\\.servlet\\.|" +
        "^org\\.apache\\.(catalina|coyote|el|jasper|juli|naming|tomcat)\\."
        ).matcher("");

protected final Matcher packageTriggersPermit =                            				// 在 delegating = false 的情况下, 下面正则匹配到的类会被 WebappClassLoader 进行加载
        Pattern.compile("^javax\\.servlet\\.jsp\\.jstl\\.").matcher("");

protected final ClassLoader parent;                      				   				// WebappClassLoader 的父 parent(在这里 Tomcat 8.x.x, parent  其实就是 commonClassloader)
protected final ClassLoader j2seClassLoader;            				   				// 这个 classLoader 其实就是 ExtClassLoader (PS: 所有的 WebappClassLoader 出发到加载 J2SE 的类时, 直接通过 ExtClassLoader / BootstrapClassLoader 来进行加载 )
                                                                                
protected final Map<String, ResourceEntry> resourceEntries = new ConcurrentHashMap<>(); // 加载资源的时候会将 文件缓存在这个 Map 里面, 下次就可以根据 ResourceEntry.lastModified 来判断是否需要热部署

protected WebResourceRoot resources = null;                             				// 这个 WebappClassLoader 加载的资源(PS: 其实就是 StandardRoot, 在WebappClassLoader 启动时, 会载入 WEB-INF/lib 与 WEB-INF/classes 下的资源de URL加入 WebAppClassLoader的 URLClassPath 里面)

private final HashMap<String,Long> jarModificationTimes = new HashMap<>();				// 保存每个加载的资源, 上次修改的时间 (后台定时任务检查这个修改时间, 决定是否需要 reload)



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
    }                                                                       // 2. 加入 /WEB_INF/lib 下面的 jar 的URL 加入 URLClassPath
    WebResource[] jars = resources.listResources("/WEB-INF/lib");
    for (WebResource jar : jars) {
        if (jar.getName().endsWith(".jar") && jar.isFile() && jar.canRead()) {
            addURL(jar.getURL());                                           // 3. 这一步就是将 ClassLoader需要加载的 classPath 路径 加入到 URLClassLoader.URLClassPath 里面
            jarModificationTimes.put(                                       // 4. 放一下 jar 文件的 lastModified
                    jar.getName(), Long.valueOf(jar.getLastModified()));
        }
    }
}

当WebappClassloader在加载Class时, 通过这个URLs来决定是否加载 class 


public WebappClassLoader(ClassLoader parent) {              // 1. 在 Tomcat 8.x.x 中运行时, 会发现 parent 就是 commonClassLoader

    super(new URL[0], parent);

    ClassLoader p = getParent();                            // 2. 这里做个检查, 若构造函数传来的 parent 是 null, 则 将 AppClassLoader 赋值给 WebAppClassLoader 的 parent
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




WebappClassLoader loadClass class 流程
 1. 判断当前运用是否已经启动, 未启动, 则直接抛异常
 2. 调用 findLocaledClass0 从 resourceEntries 中判断 class 是否已经加载 OK
 3. 调用 findLoadedClass(内部调用一个 native 方法) 直接查看对应的 WebappClassLoader 是否已经加载过
 4. 调用 binaryNameToPath 判断是否 当前 class 是属于 J2SE 范围中的, 若是的则直接通过 ExtClassLoader, BootstrapClassLoader 进行加载 (这里是双亲委派)
 5. 在设置 JVM 权限校验的情况下, 调用 securityManager 来进行权限的校验(当前类是否有权限加载这个类, 默认的权限配置文件是 ${catalina.base}/conf/catalina.policy)
 6. 判断是否设置了双亲委派机制 或 当前 WebappClassLoader 是否能加载这个 class (通过 filter(name) 来决定), 将最终的值赋值给 delegateLoad
 7. 根据上一步中的 delegateLoad 来决定是否用 WebappClassloader.parent(也就是 sharedClassLoader) 来进行加载, 若加载成功, 则直接返回
 8. 上一步若未加载成功, 则调用 WebappClassloader.findClass(name) 来进行加载
 9. 若上一还是没有加载成功, 则通过 parent 调用 Class.forName 来进行加载
 10. 若还没加载成功的话, 那就直接抛异常






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
    if (j2seClassLoader.getResource(resourceName) != null) {                // 5. 这里的 j2seClassLoader 其实就是 ExtClassLoader, 这里就是 查找 BootstrapClassloader 与 ExtClassLoader 是否有权限加载这个 class (通过 URLClassPath 来确认)
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

    // (0.5) Permission to access this class when using a SecurityManager   // 6. 这里的 securityManager 与 Java 安全策略是否有关, 默认 (securityManager == null), 所以一开始看代码就不要关注这里
    if (securityManager != null) {
        int i = name.lastIndexOf('.');
        if (i >= 0) {
            try {
                securityManager.checkPackageAccess(name.substring(0,i));   // 7. 通过 securityManager 对 是否能加载 name 的权限进行检查 (对应的策略都在 ${catalina.base}/conf/catalina.policy 里面进行定义)
            } catch (SecurityException se) {
                String error = "Security Violation, attempt to use " +
                    "Restricted Class: " + name;
                log.info(error, se);
                throw new ClassNotFoundException(error, se);
            }
        }
    }

    boolean delegateLoad = delegate || filter(name);                      // 8. 读取 delegate 的配置信息, filter 主要判断这个 class 是否能由这个 WebappClassLoader 进行加载 (false: 能进行加载, true: 不能被加载)

    // (1) Delegate to our parent if requested
    // 如果配置了 parent-first 模式, 那么委托给父加载器                   // 9. 当进行加载 javax 下面的包 就直接交给 parent(sharedClassLoader) 来进行加载 (为什么? 主要是 这些公共加载的资源统一由 sharedClassLoader 来进行加载, 能减少 Perm 区域的大小)
    if (delegateLoad) {                                                   // 10. 若 delegate 开启, 优先使用 parent classloader( delegate 默认是 false); 这里还有一种可能, 就是 经过 filter(name) 后, 还是返回 true, 那说明 WebappClassLoader 不应该进行加载, 应该交给其 parent 进行加载
        if (log.isDebugEnabled())
            log.debug("  Delegating to parent classloader1 " + parent);
        try {
            clazz = Class.forName(name, false, parent);                   // 11. 通过 parent ClassLoader 来进行加载 (这里构造函数中第二个参数 false 表示: 使用 parent 加载 classs 时不进行初始化操作, 也就是 不会执行这个 class 中 static 里面的初始操作 以及 一些成员变量ed赋值操作, 这一动作也符合 JVM 一贯的 lazy-init 策略)
            if (clazz != null) {
                if (log.isDebugEnabled())
                    log.debug("  Loading class from parent");
                if (resolve)
                    resolveClass(clazz);
                return (clazz);                                           // 12. 通过 parent ClassLoader 加载成功, 则直接返回
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
        clazz = findClass(name);                                         // 13. 使用当前的 WebappClassLoader 加载
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
    if (!delegateLoad) {                                                 // 14. 这是在 delegate = false 时, 在本 classLoader 上进行加载后, 再进行操作这里
        if (log.isDebugEnabled())
            log.debug("  Delegating to parent classloader at end: " + parent);
        try {
            clazz = Class.forName(name, false, parent);                 // 15. 用 WebappClassLoader 的 parent(ExtClassLoader) 来进行加载
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

    throw new ClassNotFoundException(name);                            // 16. 若还是加载不到, 那就抛出异常吧
}


protected Class<?> findLoadedClass0(String name) {                  // 1. 根据加载的 className 来加载 类

    String path = binaryNameToPath(name, true);                     // 2. 将 类名转化成 类的全名称

    ResourceEntry entry = resourceEntries.get(path);                // 3. resourceEntries 是 WebappClassLoader 加载好的 class 存放的地址
    if (entry != null) {
        return entry.loadedClass;                                   // 4. 将 加载好的 class 直接返回
    }
    return null;
}


protected Class<?> findClassInternal(String name)
    throws ClassNotFoundException {

    if (!validate(name))                                    // 1. 对于 J2SE 下面的 Class, 不能通过这个 WebappClassloader 来进行加载
        throw new ClassNotFoundException(name);

    String path = binaryNameToPath(name, true);             // 2. 将类名转化成路径名称

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

    Class<?> clazz = entry.loadedClass;                    // 4. 若程序已经生成了 class, 则直接返回
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
            if (pkg == null) {                            // 7. 若还不存在, 则definePackage
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
                pkg = getPackage(packageName);            // 8. 获取 Package
            }
        }

        if (securityManager != null) {                    // 9. 若程序运行配置了 securityManager, 则进行一些权限方面的检查

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

        try {											 // 10 最终调用 ClassLoader.defineClass 来将 class 对应的 二进制数据加载进来, 进行 "加载, 连接(解析, 验证, 准备), 初始化" 操作, 最终返回 class 对象
            clazz = defineClass(name, entry.binaryContent, 0,                       
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

    return clazz;                                         // 11. return 加载了的 clazz
}



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

    if (!resource.exists()) {                               // 3. 若资源不存在, 则进行返回
        return null;
    }

    entry = new ResourceEntry();                            // 4. 若所查找的 class 对应的 ResourceEntry 不存在, 则进行构建一个
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
                    binaryContent = str.getBytes(StandardCharsets.UTF_8);   // 6. 进行资源转码为 UTF-8
                } catch (Exception e) {
                    return null;
                }
            }
            entry.binaryContent = binaryContent;                           // 7. 获取资源对应的 二进制数据信息
            // The certificates and manifest are made available as a side
            // effect of reading the binary content
            entry.certificates = resource.getCertificates();               // 8. 获取资源的证书
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

1. 加速 StandardContext 所对应的资源GC
2. 防止WebappClassLoader leaking, 从而导致 WebAppClassLoader所加载的所有资源都泄露, 最终导致内存泄露

知识点:
Object <---引用---> Class <---引用---> ClassLoader
类加载器加载出来的类或对象, 对类加载器有引用, 既然有引用, 则就有能出现不被GC的情况

1. DriverManager(由 BootstrapClassloader加载) 引用 jdbc (由 WebAppClassLoader加载)
	DriverManager 是由 BootstrapClassloader加载, 所以其永远不会被GC, 当是它有引用了由 WebAppClassLoader 加载的 JDBC, 所以导致 JDBC 与 WebAppClassLoader 都被引用住, 
	而 WebAppClassLoader 又对由其加载的类有引用, 所以由 WebAppClassLoader 加载的类都不会被GC, 最终在多次 StandardContext.reload 后就出现内存泄露
2. ThreadLocal
	Tomcat 的工作线程池里面线程可能很长时间才会死掉, 而ThreadLocalMap的生命周期由和 Thread的一样, 这样导致 ThreadLocalMap 里面的 Value 也被引用住, 
	而这个 Valve很有可能是 StandardContext.WebappClassloader 加载, 所以就又导致 WebappClassloader 被引用, 而 WebAppClassLoader 又对由其加载的类有引用, 
	所以由 WebAppClassLoader 加载的类都不会被GC, 最终在多次 StandardContext.reload 后就出现内存泄露
	这时我们就将线程里面的 ThreadLocalMap 里面的值清掉就 OK 了
3. 由WebappClassloader加载出来的线程一直运行
	这个简单, 通过 threadGroup 获取所有Thread, 判断 contextClassLoader 是否是 WebAppClassLoader, 若是的话直接杀掉
4. IntrospectionUtils
	IntrospectionUtils 是 Tomcat 的反射工具类, 这里也清空一下缓存的数据, 防止又出现 WebappClassLoader 又被引导, 从而导致 内存泄露 


HashSet<Driver> originalDrivers = new HashSet<>();
Enumeration<Driver> drivers = DriverManager.getDrivers();
while (drivers.hasMoreElements()) {
    originalDrivers.add(drivers.nextElement());
}
drivers = DriverManager.getDrivers();
while (drivers.hasMoreElements()) {
    Driver driver = drivers.nextElement();
    // Only unload the drivers this web app loaded
    if (driver.getClass().getClassLoader() !=
        this.getClass().getClassLoader()) {
        continue;
    }
    // Only report drivers that were originally registered. Skip any
    // that were registered as a side-effect of this code.
    /**
     * 实际就是 DriverManager能拿到所有的 Driver的一个集合, 然后判断 Driver该类是否是由当前应用
     * 类加载器进行加载的, 如果是的话, 直接调用 DriverManager.deregisterDriver() 对其进行卸载
     */
    if (originalDrivers.contains(driver)) {
        driverNames.add(driver.getClass().getCanonicalName());
    }
    DriverManager.deregisterDriver(driver);
}






 /* 通过 StandardContext 的几个属性来控制是否 clear掉当前应用创建出来的线程
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
                if (tg != null &&                               // 3. 对应 RMI 或 system 的
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
                            target.getClass().getDeclaredField("this$0");       // 9. 获取线程池
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



 /* AppClassLoader -> 工作线程 Thread A -> Thread A.ThreadLocalMap -> Thread A.ThreadLocalMap.value (若这个 value 是 WebappClassLoader 加载的话), 那么 WebappClassLoader也就被强引用, WepappClassLoader 也就不能被卸载
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
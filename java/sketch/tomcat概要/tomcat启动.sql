猜想:
1. bash 脚本引导 java 类
2. 解析 server.xml
3. Server, Service, Engine 的启动
3. 解析 ${catalina.base}/webapps 下面的项目(以文件夹/war包的形式), 包括解析 web.xml 文件, 将 servlet, filter, listener, session 对应的信息加载到 Context 里面
4. 请求连接处理器 connector 启动
5. 加载 servlet, ServletContainerInitializer


/usr/bin/java 																			# Java 命令														
-Djava.util.logging.config.file=${catalina.base}/conf/logging.properties 			    # Tomcat 的日志配置文件
-Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager 						# Tomcat 日志管理器	主要是读取日志的配置信息(能实现针对不同的 Context 配置不同的输出策略)	
-Djava.endorsed.dirs=/usr/local/tomcat7.0.73/endorsed 									# 这个参数是 JVM 提供给我们替换 jdk 原有类的一个功能(我的理解就是 BootstrapClassLoader 在进行加载class时, 会先加载这个目录下的类, 而忽视原先在 ${JDK}/lib 下的相同类名的类), 这里我们还想到 "java.ext.dirs" 通过这个参数我们可以给原有的 javaapi 增加一些新的功能
-classpath ${catalina.base}/bin/bootstrap.jar:${catalina.base}/bin/tomcat-juli.jar 		# classpath 路径, 一个是Tomcat 的启动引导包, 一个是Tomcat 自己的日志jar
-Dcatalina.base=/usr/local/tomcat8.0.5 										
-Dcatalina.home=/usr/local/tomcat7.0.5												    # Tomcat 的安装目录
-Djava.io.tmpdir=/usr/local/tomcat8.0.5/temp 											# tomcat 的临时文件目录
org.apache.catalina.startup.Bootstrap startup                                           # Java 程序 main 类




1. 调用 super.initInternal 将自己注册到 JMX 中
2. 调用 container.init() 来初始化 对应的一层一层所有的容器 (Tomcat 的容器有 Engine, Host, Context, Wrapper)
3. 初始化 MapperListener(MapperListener 没有自己的  initInternal, 只是在父类里面注册一下 JMX 服务), 这里的 mapperListener 非常重要, 具体看其startInternal 方法, 里面有监听 各层容器的方法
4. 初始化 所有 connector (每一个 connector 代表一种通信协议， 现有协议 http, arp, 而每种协议又对应 3种IO模型 BIO, NIO, AIO)


1. 触发一下容器生命周期的事件, 设置现在容器的状态
2. 调用 NamingResourcesImpl.start (这里面的start, 只是设置一下状态, 触发一下事件 CONFIGURE_START_EVENT)
3. 调用 StandardService.start 来触发下面的各个组件/容器 (Engine, Host, Context, Wrapper)

1. 设置容器的状态
2. 启动 对应的子组件/容器 (Engine/Host/Context/Wrapper 这里启动的 wrapper 只有在是配置了 loadOnStartUp 的 wrapper)
3. 启动 MapperListener 这里将 容器的信息加到 路由解析器 Mapper 上
4. 启动 IO 处理器 connector

我们看到, Tomcat 是先 start 所有的容器, 然后将容器里面的信息映射到路由器里面Mapper, 最后在启动 Tomcat 的连接处理器



1. 初始化 容器对应的 Logger
2. 若配置了 Tomcat cluster, 则start
3. 开启 Tomcat 验证服务 Realm
4. 通过线程池 start 其对应的 子容器
5. 调用 StandardPipeline.start 初始化 currentValve, 其中也会 调用 Valve.start 来启动对应的所有 Valve
6. 开启 容器的后台定时任务(其会递归的执行其子容器)

Container.backgroundProcess() 任务列表
1. ContainerBase 集群后台任务, Realm 后台任务, 获取容器所对应的Piepline, 执行其里面Valve的 backgroundProcess
2. StandardEngine 调用 ContainerBase 的后台任务
3. StandardHost 调用 ContainerBase 的后台任务
4. StandardContext 
    (1) 调用 WebappClassLoader 来一直监控着其所加载资源, 一有变动, 就触发热部署(将 WebappClassLoader.backgroundProcess() 方法)
    (2) 调用 ManagerBase 的backgroundProcess 来 loop 检测所有 Session 是否超时
    (3) 调用 Cache.backgroundProcess 将超过cache 大小的元素 evict 掉 (这里的 Cache 存储的是 通过StandardRoot来拿到的资源)
    (4) 调用 ContainerBase.backgroundProcess 来处理一些公共的定时任务
5. StandardWrapper
    这里主要针对JSP的 JspServlet, 它调用了 periodicEvent 方法, 主要做下面两件事
    (1) 若设置JSP 闲置时间 jspIdleTimeout, 则将超过闲置时间的 JspServletWrapper 从 jsps(ConcurrentHashMap) 及 jspQueue(FastRemovalDequeue) 里面进行删除
    (2) 若设置了 lastCompileCheck (间隔编译), 则达到时间间隔, 就进行重编译(这一块没有再深入了解了, 毕竟现在写JSP的页面的程序不多了)

Valve.backgroundProcess() 任务列表
1. AccessLogValve: 定时调用flush方法, 刷一下数据
2. StuckThreadDetectionValve: 定时的扫描工作线程, 查看其是否执行工作超时(这个用的比较少, 也比较简单, 主要是捕获 程序中处理时间长的 Thread, 并且在满足条件的情况下 进行 打一下警告日志 (进行请求的时候, 加入到监控线程的定时任务里面, 监控处理所花费的时间, 若超时, 则打印线程的堆栈日志信息))

1. 将 ErrorReportValve 加入其对应的 pipeline (ErrorReportValve 主要是对服务器的一次啊错误进行处理, 错误的信息加载 Response 里面)
2. 调用父类 ContainerBase 的 startInternal 方法 进行启动一些公共属性

创建${catalina.base}/webapps与${catalina.base}/conf/Catalina/host


用 digester 将 
${catalina.base}/config/context.xml 与 
${catalina.base}\conf\Catalina\
localhost\context.xml.default 
的内容解析到 StandardContext 里面 

StandardRoot将Context要加载的资源分别加载到 
PreResources, mainResources, 
classResources, jarResources, postResources
开启 DirResourceSet或JarResourceSet
设置内部缓存 Cache 的设置(是否有maxLimit)

初始化临时目录
默认 为 $CATALINA_BASE/work/<Engine名称>/<Host名称>/<Context名称>

初始化 ServletContext 的实现类 ApplicationContext
SessionCookieConfig 的实现类 ApplicationSessionCookieConfig
初始化 Tomcat 支持的 Session 追踪模式 (cookie, URL, SSL)


这时候 切换 Classloader 没成功
主要是 WebappClassLoader 来没有创立起来

这里是构建WebappClassLoader 的父级
classLoader 的URLClassPath都查出来
设置到 Context 里面

这里面有两次ClassLoader切换
其中只有第二次有效
这段代码有可能是因为历史原因遗留下来

fireLifecycleEvent(Lifecycle_CONFIGURE_START_EVENT)


1. 扫描  global, host, context, web_Fragment 类型的 web.xml(会扫描每个 jar 下的资源), 合并成 1 个
2. 根据web.xml 里面的配置信息映射成实体类(Servlet, Filter, Listener, Session)
3. 通过 SPI 机制获取 JAR 下面的 所有 ServletContainerInitializer 的实现类, 并且加载到 Context 里面

这一步非常重要, 就是将 web.xml 里面的配置
(servlet, filter, listener, session) 映射成对应
对象, 加入到 Context 里面

解析应用程序注解配置 主要是 
(listener, Filter, Servlet类的, 
最后会将这些资源信息加入到 
StandardContext.NamingResourcesImpl 
里面, 在实例化 Servlet或Filter或Listener 时会用到)
是不是像IOC

经过 ContextConfig, StandardContext里面已经含有
web.xml里面配置的Wrapper

在通过InstanceManager生成实例时,
就会从injectionMap拿取依赖的资源



创建实例管理器 InstanceManager 用于创建对象实例, 如 Servlet, filter
创建每个应用 StandardContext 自己的 DefaultInstanceManager实例化管理器对象
这里的 "this.getClass().getClassLoader()" 其实就是 WebappClassLoader


将 Context.xml, server.xml 里面的信息合并
到ApplicationContext里面

在Spring里面就有SpringServletContainerInitializer
来处理Spring的一些操作

主要是 ApplicationListener
ServletContextListener 等的配置加启动方法执行
很多书籍都介绍 Tomcat 是 "一个免费开源的Servlet容器", 的确 Tomcat 是参考 Servlet 规范实现出来, 它是一个Servlet容器, 而现在在我看来 Servlet 容器是Tomcat 特性的一部分, 另外很大一部分是: Tomcat 是一个代码优美, 设计精巧,层次分明, 扩张性很好的 网络服务框架(PS: 我现在还没仔细看 Dubbo 框架的源码, 但依稀感觉 dubbo 的作者在设计时肯定参考了 Tomcat 的设计, 就连命名都如此相像); 



1. Bootstrap 是Tomcat 默认的引导启动了, 我们平时 执行 ${catalina.base}/bin/startup.sh 就是 将 Bootstrap 当做 main 类 进行启动(PS: Bootstrap 有个非常重要的功能, 就是定义整个程序的 classLoader 层级, 这个在 Classloader 中会详细叙述)
2. Catalina 在 Tomcat 中, Catalina其实就是代表了整个 服务控制中心, 其控制着整个 Tomcat 的 start, stop, await, 配置文件测试, 将配置文件 server.xml 解析成对应的 StandardServer, 触发 StandardServer 及其子组件的 init, start, stop, destory 等操作
3. Server 一个Tomcat程序, 一个 Server, 上面 Catalina 的 start, stop, await 其实都是代理 Server 操作的, 而Catalina 又比 Server 多了 解析配置文件 server.xml
4. Service  在代码层面看到, 理论上一个 Server可以包含多个 Service, 而Service 对应多个 请求连接处理器 connector 与一个子容器 Container, 另一个非常重要的属性就是 Mapper (路由模块)
5. Mapper  请求路由模块, 这个模块里面的数据就是整个容器的各个层级的容器的信息, 每次容器中有组件变化, 就会触发 MapperListener, 而MapperListener 又会触发Mapper 里面数据的变化(PS:Tomcat支持动态增加容器, 而当容器变化时, 对应的路由信息也要进行相应的变化, 这个工作就交由 MapperListener来完成)
	在进行路由请求时, 请求连接器 connector 将通过 Service 获取对应 路由模块Mapper 进行路由操作
6. Engine 连接器connector 接到请求, 通过路由模块就能找到对应的 Engine, 并且整个消息经过容器的第一层就是 Engine, 那Engine 是什么作用呢? 路由到请求的 host, 在整个 Tomcat 中可能有好几个 Host 
	(一个host其实就是代表一个域名, 而大家也知道, 现在的一塔物理机可以同时又好几个域名指向它), 所以需要 Engine 进行路由 (PS: 在产线上一般都是一台物理机器 一个Tomcat, 而 Engine 默认路由的也是其 defaultHost)
	见 ${catalina.base}/conf/server.xml 中的 "<Engine name="Catalina" defaultHost="localhost">" 现在是不是有点感觉了
7. Host 一个host其实就是代表一个域名, 这里的Host 主要完成 Context的部署(deployOnStartup, 默认目录 ${catalina.base}/webapps/)/自动部署(autoDeploy), host 层面的配置文件的解析, 在部署环节需要 HostConfig 进行相应操作 (HostConfig非常重要)	
8. Context 代表Tomcat容器里面的一个项目, 也就是我们平时在 ${catalina.base}/webapps/ 下面放的一个 war 包 (其中包括 WEB-INF/lib, WEB-INF/classes, web.xml 等)
9. Wrapper 一个Servlet 就代表一个 Wrapper(单例模式), 所有的请求都会被servlet来处理(JSP 由JSPServlet来处理, 静态文件由WebdavServlet来处理), 现在一般项目都由 Spring 的 Dispatcherservlet 来统一处理, 而Dispatcherservlet 又非常大
	当然 Tomcat 也支持 一个线程一个 Servlet这种线程安全的 Servlet 模式, 每个Servlet 最多对应 20个实例, 若出现第21个请求来(针对同一个 Servlet), 请求就会被阻塞掉 (啃爹哇, 若使用这种模式 + Spring的DispatchServlet 则Tomcat的并发请求就被限制在20了...)
10. ApplicationFilterChain 这是一条 Filter 的处理链, 程序会根据URI匹配对应的 Filter, 最后设置 Servlet, 程序会依次执行 ApplicationFilterChain里面的Filter(通过递归), 最后执行对应的 Servlet (到这里只是处理了 Servlet 里面的业务逻辑), 而正真的写数据是在 CoyoteAdapter 中的 response.finishResponse()

对了还有一个 PipeLine(管道) + Valve(阀门) 
11. PipeLine 每个容器都有其对应的 PipeLine, PipeLine 里面装载着 Valve(以链表的形式), 此时是不是想到了 netty 里面的 ChannelPipeline, 只是 netty 里面叫做 valve 叫做了 ChannelHandler, 并且由其 ChannelHandlerContext 控制了链表的结构

到这里, 大家可能会困惑, 干嘛定义这么多对象, 直接定义一个 请求连接处理模块 + 一个 封装Servlet的模块 不久行了
额  没事 我们来看一下这个  
	Example: 我自己的服务器, 假设 IP: 23.89.15.9, 在这个服务器上有两个域名指向它, 一个是 www.tuomatuo.com, 一个是 localhost

	场景一: 请求 URL = http:www.tuomatuo.com:8080/manager/login.do
		(1). www.tuomatuo.com 	就代表组件中的 Host
		(2). manager 			代表我们在 ${catalina.base}/webapps/ 下面部署的运用, 也就是 Host 的子容器 Context
		(3). login.do 			就是 Servlet匹配的URI了, 你可以假想成代表 Wrapper
	场景二: 请求 URL = http:localhost:8080/taobao/index.do
		(1). localhost		 	就代表组件中的 Host
		(2). taobao 			代表我们在 ${catalina.base}/webapps/ 下面部署的运用, 也就是 Host 的子容器 Context
		(3). index.do 			就是 Servlet匹配的URI了, 你可以假想成代表 Wrapper
	从上面的两个场景中, 我们得知 Tomcat 支持多 Host, 并且每个 Host 下面可以部署多个 Context, 每个Context 下面也可以有多个 Servlet 



javax:					用Java语言实现的 Servlet API (基本上都是接口 也就是我们 Maven 里面的 javax.servlet-api 包)
org.apache.catalina:	这个包下面的代码就是整个 catalina, 里面包含一层一层的容器
org.apache.coyote:		这个包下面的代码就是Tomcat的网络处理框架, 主要处理的协议有 http1.1, ajp, http2.0 (每种协议对应 bio, nio, aio, 其实这里可以用 成熟的 IO框架, 比如 netty, mina, 吐槽一下 epoll 的 selector.select() 导致 CPU 100% 的bug在Tomcat中没有对应的修复)
org.apache.el:			el 表达式, 这个东西好像是个远古生物, 暂时还没使用过
org.apache.jasper:		jsp 解析成对应Servlet, 变成为 class, 通过 jasperClassLoader 加载进来, jsp 热部署 都是这个 package 下面的代码完成的 
org.apache.juli:		tomcat 的日志框架(默认日志的配置文件为 ${catalina.base}/conf/logging.properties)
org.apache.naming:		tomcat 中资源服务管理类包(主要是 jndi 的操作, 其中涉及对加载资源的寻找 + 监控是否修改 -> 热部署)
org.apache.tomcat:		tomcat 的工具包 主要是 threads, scan, net, digester, buf, codec


这里以 StandardServer 为例子进行说明:
class StandardServer extends LifecycleMBeanBase implements Server 
StandardServer 继承 LifecycleMBeanBase 实现 Server
从上面的 UML 图中, 我们知道 
	1. 接口 Server 继承 Lifecycle 
	2. LifecycleMBeanBase 继承 LifecycleBase, LifecycleBase 又实现了 Lifecycle 
结论: 接口 Server 中包含 Lifecycle 中未实现的方法,  类 LifecycleMBeanBase 中又有 Lifecycle 实现了的方法
	那其实 对于 StandardServer, 没有必要让 接口Server 继承Lifecycle, 从而获取 Lifecycle 的生命周期方法?
	代码完全可以变成: class StandardServer implements Server, Lifecycle  (PS: 其中 Server 不继承 Lifecycle)
	若真的这样的话, 等过了几版 Tomcat 后, 代码可能变成 class StandardServer implements Server, 我想这是 Catalina 作者不想看到的
	所以, 你看到在 Tomcat 中的基础接口都继承了 Lifecycle(如 Server, Service, Container, Executor, WebResourceRoot, WebResourceSet)


Valve 作用:
  1. 在 Pipeline 路由到下一级容器 
  2. 起到AOP的作用, 在调用前, 调用后 起到环绕监视的作用
  3. 还可以给 Request, Response 传输的对象起到分层次加工的作用

ValveBase:					主要完成子类的一些公共的方法
JDBCAccesslogValve: 		将 Tomcat 访问的日志记录信息记录到 数据库里面
PersistentValve:			在配置了 PersistentManager 的情况下, 会对每次请求后 持久化 Session (这个有点扯蛋, 若请求量一大, 则程序直接挂了)
SemaphoreValve:				可以附属于任何 Container 的,  用于控制并发请求 的 Valve (内部使用 Semaphore 来实现, 其实在 connector 内部也有控制请求数的处理类 LimitLatch)
ErrorReportValve:			检测 Http 请求过程中是否出现过什么异常, 有异常的话, 直接拼装 html 页面, 输出到客户端
RemoteIpValve:				通常请求到达 Tomcat 会经过多层的反向代理, 这个 Valve 的作用就是 根据 Header 里面的信息, 将真实的 IP 地址信息设置到 request 里面 (其中也涉及到将 IP 等信息加入到只有 Accesslog 才会使用的属性中)
SingleSignOn:				单点登录 Valve, Tomcat 集群中能使用到, 通过 cookie 机制
RequestFilterValve:			对请求进行过滤的 Valve, 主要是 IP, Host (见其子类 RemoteHostValve, RemoteAddrValve)
CrawlerSessionManagerValve:	通过解析 Http header 里面的 user-agent 来实现反爬虫的 Valve, 其实可以加上 refer(这个作用不大), 主要目的还是为了防止 大量爬虫请求, 而导致创建大量 Session

StandardEngineValve:   		请求路由 Valve, 通过 Request 里面的信息, 将请求路由到对应的 StandardHost
StandardHostValve:			根据请求的信息将其路由到对应的 StandardContext (PS: 其中比较重要的是, 在每次进行操作前后, 需要更改对应线程的 ContextClassloader 为 StandardContext 中的 WebappClassloader)
StandardContextValve:		根据 Request 里面的信息, 将请求路由到对应的 wrapper 中
StandardWrapperValve:
								(1). 根据 URI 获取 对应 Servlet, 若还没有生成, 则进行相应的创建 (通过 StandardContext 的 实例创建类 InstanceManager 进行创建, 其会对 Servlet 上的注解进行相应的处理, 最后会调用 servlet.init() 方法)
								(2). 根据请求的URI 获取相应的 Filter, servlet 组装成 ApplicationFilterChain 进行相应处理 (每一个 ApplicationFilterConfig 代表一个 Filter 封装类, 在第一次请求后会通过反射生成对应的 Filter 实例, 以后就用这个 Filter)
								(3). 释放 ApplicationFilterChain 里面的 Filter, Servlet 资源


Filter 存在于 ApplicationFilterChain 中主要是对请求的参数 进行一些过滤/修饰措施, 现对于  Valve, 其可以控制请求是否流向下层组件
并且在实际代码中 Filter 请求下个节点是通过递归的方法进行, 一开始程序中是没有 Filter 对象的, 在第一次请求过后, 通过 StandardContext 的
实例生成器 InstanceManager 来生成(InstanceManager 会处理 Filter 上注解修饰的一些东西), 后面直接缓存在 StandardContext 里面

FilterBase:					通过反射工具类 IntrospectionUtils 将 filter 里面的一些属性设置进去(这些参数的设置通过 web.xml 或  context.xml)
CometFilter: 				长连接的 filter (鉴于在 Tomcat 9.x.x 中移除了 comet 功能, 所以这里....)
RequestFilter:				定义请求过滤模板方法, 主要由子类 RemoteAddrValve, RemoteHostValve 来时现对应的逻辑
ExpiresFilter:				通过这个 Filter, 在请求处理后, 在 http header 中控制缓存时间的信息 
SetCharacterEncodingFilter: 在请求处理之前(即 Request 对 请求参数处理之前) 设置一个 Request 的编码格式
AddDefaultCharsetFilter:	负责统一设置 Response 处理的编码格式 (这个暂且还没有过)


LifecycleListener: Tomcat 容器的生命周期监听器
  1. 监听实时修改路由的规则
  2. 监控部署目录, 完成自动部署
  3. 监控容器, 在停止时查看是否存在内存泄露等问题

JreMemoryLeakPreventionListener:		这个监听器是在容器 init 之前, 将做一些公共类加入到 commonClassloader 中, 启动节省内存的作用
										何时出发这个 Listener ? BEFORE_INIT_EVENT 就是在 监听的容器组件 init 调用init之前, 将一些公共的数据先加载到 CommonClassLoader 里面 (看代码中 直接将 Thread.ContextClassLoader 设置为 ClassLoader.getSystemClassLoader())
										这里所做的 保护内存泄露 无非就是 将 本来在 每个 WebappClassLoader 中都进行加载的 class, 事先在 commonClassLoader 里面进行加载一遍, 比如说 数据库连接驱动 等(PS: 代码中其他的一些也不常用)				
MemoryLeakTrackingListener:				MemoryLeakTrackingListener 归属于 StandardContext
 										其用 WeakHashMap 装载 WebAppClassLoader, 等关闭 StandardContext 之后, 在看看 与之对应的 WebappClassLoader 是否存活, 若还存在, 则说明 WebappClassLoader 没有没 GC, 存在 Perm 区域内存泄露
 										见 StandardHost.findReloadedContextMemoryLeaks()
ThreadLocalLeakPreventionListener:		防止因 ThreadLocal 的存在, 而造成内存泄露
										在进行 Tomcat 热部署时, 工作线程是不会停止的, 而需要关闭 StandardContext 对应的 WebappClassLoader, 而 ThreadLocal.threadLocalMap 里面有存储了 由WebappClassLoader 加载出来的类, 所以有可能导致 WebappClassLoader 因被引用而不能被 GC, 最终导致内存泄露
										(PS:  ThreadLocalMap 的生命长度与 Thread 一样, Tomcat中的工作线程池不因 StandardContext 的stop, 而销毁)
										见官网 : https://wiki.apache.org/tomcat/MemoryLeakProtection
MapperListener:							MapperListener 归属于 StandardService, 在各个组件/容器进行init/start 时都会发出消息通知(这里的消息通知在 LifecycleBase 里面进行操作), MapperListener 会根据Tomcat里面各个组件的组成映射到 Mapper 里面(Mappper 主要是完成请求路由作用, 而路由的信息最终会存储在 org.apache.catalina.connector.Request 里面(PS: Tomcat里面有两个 Request))
EngineConfig:							这里 EngineConfig 是 StandardEngine 容器生命周期的监听器, 主要做些日志记录
HostConfig:								HostConfig 是 StandardHost 的监听器, 主要是下面两张用途
											(1) StandardHost 后台周期性检测是否需要重新部署 (三种方式 xml, war包, 文件夹), 我们平时在 ${catalina.base}/webapps/ 下面部署 war/文件夹, 而对应的解析加载工作就是这里做的 (见 HostConfig.deployDirectory())
										    (2) 通过 MBeanFactory 或 HostManagerServlet 触发部署操作
ContextConfig:							主要功能的 :
											(1): 组装 web.xml 的解析器 WebXmlParser
											(2): 根据 Host的 appBase 以及 Context的 docBase 计算 docBase 的绝对路径
											(3). 扫描 web.xml 文件, 在遇到全局 web.xml 或 host 层面的 web.xml, 则应用层面的 web.xml 的属性能覆盖上面两个, 这里面的知识点非常多, 通过 SPI 机制加载 ServletContainerInitializer, 并且将它们 set 到对应的 StandardContext
											(4). 解析应用程序注解配置 主要是 (listener, Filter, Servlet类的, 最后会将这些资源信息加入到 StandardContext.NamingResourcesImpl 里面, 在实例化 Servlet/Filter/Listener 时会用到)


/**
 *
 *
 * ━━━━南无阿弥陀佛━━━━━━
 * 　　　┏┓　　　┏┓
 * 　　┏┛┻━━━┛┻┓
 * 　　┃　　　　　　　┃
 * 　　┃　　　━　　　┃
 * 　　┃　┳┛　┗┳　┃
 * 　　┃　　　　　　　┃
 * 　　┃　　　┻　　　┃
 * 　　┃　　　　　　　┃
 * 　　┗━┓　　　┏━┛
 * 　　　　┃　　　┃  stay hungry stay foolish
 * 　　　　┃　　　┃  Code is far away from bug with the animal protecting
 * 　　　　┃　　　┗━━━┓
 * 　　　　┃　　　　　　　┣┓
 * 　　　　┃　　　　　　　┏┛
 * 　　　　┗┓┓┏━┳┓┏┛
 * 　　　　　┃┫┫　┃┫┫
 * 　　　　　┗┻┛　┗┻┛
 * ━━━━━━--萌萌哒━━━━━━
 *
 * http://blog.csdn.net/beliefer/article/details/51894747
 *
 * http://blog.csdn.net/c929833623lvcha/article/details/44677569
 * http://blog.csdn.net/column/details/tomcat7-internal.html
 *
 * tomcat 系列文章
 * http://blog.csdn.net/yangzl2008/article/category/5791147
 *
 * 讲 classLoader 的
 * http://blog.csdn.net/irelandken/article/details/7048817
 * https://liuzhengyang.github.io/2016/09/28/classloader/
 *
 * 将 EndPoint 的资料
 * http://zddava.iteye.com/category/53603
 * https://my.oschina.net/u/862897/blog/129434
 *
 * Tomcat 内存泄漏
 * https://wiki.apache.org/tomcat/MemoryLeakProtection
 * http://blog.xiaohansong.com/2016/08/09/ThreadLocal-leak-analyze/
 *
 * Tomcat catalina.sh 注解
 * http://vekergu.blog.51cto.com/9966832/1621396
 * http://vekergu.blog.51cto.com/9966832/1622442
 * http://www.chongchonggou.com/g_28629056.html
 * http://www.10tiao.com/html/308/201603/402124388/1.html
 *
 * 学习计划
 *
 * 0. Tomcat 中的各个组件
 *
 * 1. Tomcat 启动过程
 *
 * 2. 一次 Tomcat Http 请求
 *
 * 3. Tomcat classLoader 设计及热部署
 *
 * 4. Tomcat connector (bio, nio 线程模型)
 *
 * 5. Tomcat 中使用的设计模式
 *      (1) AccessLogAdapter 组装 AccessLog (适配器)
 *
 * 6. Tomcat Session 生成,追踪及管理(持久化)策略(涉及 cookie, request.getAttribute 触发什么动作)
 *      分布式Session设计 https://github.com/jcoleman/tomcat-redis-session-manager
 *
 * 7. Tomcat Servlet 生命周期 (new, init, service, destory)
 *
 * 8. Tomcat JSP 创建, 解析过程, 动态编译过程, 动态加载
 *
 * 9. Tomcta 里面的主要 Valve, Filter, Listener (JreMemoryLeakPreventionListener, ThreadLocalLeakPreventionListener ...)
 *      ApplicationFilterChain 的递归调用 和 用 Loop 与 ThreadLocal
 *
 * 10. tomcat 默认 bash 处理
 *      http://vekergu.blog.51cto.com/9966832/1622442
 *
 * Created by xjk on 5/31/17.
 *
 *
 * Tomcat 各个容器的生命周期监控
 *  ContextConfig, NamingContextListener, MapperListener, ThreadLocalLeakPreventionListener, MemoryLeakTrackingListener, EngineConfig, HostConfig, JreMemoryLeakPreventionListener
 *
 * Tomcat 里面的 ContainerListener
 *  最典型的的就是 MapperListener, 它既是 LifecycleListner, 又是 ContainerListener, 在其start方法中就收集所有的 engine,host,context,wrapper, 并且将其设置为 ContainerListener 与 LifecycleListner
 *  在 ContainerBase 里面有对触发容器 listener 的抽象方法
 *
 * 问题
 *  1. Engine 继承 Container, ContainerBase 实现 Container, 现在 StandardEngine 继承ContainerBase 实现Engine,, 既然ContainerBase 已经实现了 Container 接口, 为什么还需要 Engine 继承 Container 接口
 *      一切为了扩展, 想象一个, 这时到了 Tomcat119 版本, 需要增加一个 StandardEngine119(这是别名), 而且这时 StandardEngine119 不需要 ContainerBase 里面的功能, 所以  StandardEngine119 不继承 ContainerBase, 但是 StandardEngine119 又需要实现 容器的规范, 这时 Engine 继承 Container 的作用就体现出来了
 *      回头看看 StandardServer StandardService 中 Server, Service 继承 Lifecycle,  都是一样的道理
 *
 * tomcat 扩张点
 * 1. 增加对应的协议
 *      现在只支持 http, arp 并且每一种都是(bio, nio, aio), 可以增加 redis, memcached, mysql, tuomatuo 协议
 * 2. 获取 Tomcat 中所有代码行数  find . -type f -name *.java | xargs cat | wc -l
 *
 *
 *
 */

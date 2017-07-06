/**
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
 * 学习计划
 * 1. tomcat classLoader种类, 动态加载, 内存泄漏检测 (ClassLoaderLogManager, JreMemoryLeakPreventionListener, ThreadLocalLeakPreventionListener)
 *
 * 2. tomcat servlet jsp 原理(从启动加载, 路由映射, jsp动态编译成 servlet的class并且动态加载)
 *
 * 3. tomcat jasper 原理, 及处理过程
 *
 * 4. tomcat session 原理, 持久化
 *
 * 5. tomcat cookie 原理
 *
 * 6. tomcat 通信框架 coyote
 *
 * 7. tomcat 里面各种 valve
 *
 * 8. tomcat 里面各种 filter, 装饰器, 过滤器， 适配器(以及其他 设计模式)
 *
 * 9. tomcat JMX 使用
 *
 * 10. tomcat 默认 bash 处理
 *
 * 11. tomcat 里面的各种线程模型(线程池)
 *
 * 12. tomcat 里面所使用的设计模式
 *
 * 13. Tomcat 里面的 StandardServer, StandardService, StandardEngine, StandardHost, StandardContext 为什么需要 listener 监听其生命周期
 *      tomcat 是一个热部署的web容器, 需要通过监听的机制, 知道tomcat以前及现在的运行状态
 *
 * 14. Tomcat 的 keepalive 原理
 *
 * 15. request.getAttribute 触发什么动作
 *
 * 16. Servlet, Session, Server, Service, Engine, Host, Context 的生命周期
 *
 * Created by xjk on 5/31/17.
 *
 * tomcat 扩张点
 * 1. 增加对应的协议
 *      现在只支持 http, arp 并且每一种都是(bio, nio, aio), 可以增加 redis, memcached, mysql, tuomatuo 协议
 */

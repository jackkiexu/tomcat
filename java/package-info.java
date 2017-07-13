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
 * Tomcat 内存泄漏
 * https://wiki.apache.org/tomcat/MemoryLeakProtection
 * http://blog.xiaohansong.com/2016/08/09/ThreadLocal-leak-analyze/
 *
 * 学习计划
 *
 * 1. Tomcat 启动过程
 *
 * 2. 一次 Tomcat Http 请求
 *
 * 3. Tomcat classLoader 设计及热部署
 *
 * 4. Tomcat线程模型 (bio, nio)
 *
 * 5. Tomcat 中使用的设计模式
 *
 * 6. Tomcat Session 生成,追踪及管理(持久化)策略(涉及 cookie, request.getAttribute 触发什么动作)
 *
 * 7. Tomcat Servlet 生命周期 (new, init, service, destory)
 *
 * 8. Tomcat JSP 创建, 解析过程, 动态编译过程, 动态加载
 *
 * 9. Tomcta 里面的主要 Valve, Filter, Listener (JreMemoryLeakPreventionListener, ThreadLocalLeakPreventionListener ...)
 *
 * 10. tomcat 默认 bash 处理
 *
 * Created by xjk on 5/31/17.
 *
 * tomcat 扩张点
 * 1. 增加对应的协议
 *      现在只支持 http, arp 并且每一种都是(bio, nio, aio), 可以增加 redis, memcached, mysql, tuomatuo 协议
 * 2. 获取 Tomcat 中所有代码行数  find . -type f -name *.java | xargs cat | wc -l
 */

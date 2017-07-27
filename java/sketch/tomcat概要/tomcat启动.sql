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
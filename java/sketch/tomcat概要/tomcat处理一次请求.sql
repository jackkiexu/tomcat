1. 请求猜想
(1) Acceptor 接受请求
(2) 交给工作线程池来处理请求
(3) 封装请求成 Request, Response 对象, 向后端容器进行传递
(4) 经由后端容器的一个一个 Valve 处理
(5) 在 StandardWrapperValve 根据URi, 组装 ApplicationFilterChain 来处理请求
(6) 请求经过 Servlet 里面的逻辑处理
(7) 请求处理的结果经过 Response 对象刷到 浏览器上

因为KeepAlive + comet + http 协议升级 导致代码很复杂, 这里我们只分析普通的执行逻辑
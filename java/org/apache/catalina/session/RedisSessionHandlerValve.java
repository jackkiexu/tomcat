package org.apache.catalina.session;

import org.apache.catalina.Session;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

import javax.servlet.ServletException;
import java.io.IOException;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * 这个是自己基于 redis 实现的将Session 的信息在处理好请求后, 放置于 redis 中
 */
public class RedisSessionHandlerValve extends ValveBase {
  private final Log log = LogFactory.getLog(RedisSessionManager.class);
  private RedisSessionManager manager;

  public void setRedisSessionManager(RedisSessionManager manager) {
    this.manager = manager;
  }

  @Override
  public void invoke(Request request, Response response) throws IOException, ServletException {
    try {
      getNext().invoke(request, response);
    } finally {
      manager.afterRequest();
    }
  }
}

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
package org.apache.catalina.valves;

import java.io.IOException;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * Web crawlers can trigger the creation of many thousands of sessions as they
 * crawl a site which may result in significant memory consumption. This Valve
 * ensures that crawlers are associated with a single session - just like normal
 * users - regardless of whether or not they provide a session token with their
 * requests.
 *
 * 通过解析 Http header 里面的 user-agent 来实现反爬虫的 Valve, 其实可以加上 refer(这个作用不大), 主要目的还是为了防止 大量爬虫请求, 而导致创建大量 Session
 */
public class CrawlerSessionManagerValve extends ValveBase
        implements HttpSessionBindingListener {

    private static final Log log =
        LogFactory.getLog(CrawlerSessionManagerValve.class);

    private final Map<String,String> clientIpSessionId =
            new ConcurrentHashMap<>();
    private final Map<String,String> sessionIdClientIp =
            new ConcurrentHashMap<>();

    private String crawlerUserAgents =
        ".*[bB]ot.*|.*Yahoo! Slurp.*|.*Feedfetcher-Google.*";
    private Pattern uaPattern = null;
    private int sessionInactiveInterval = 60;


    /**
     * Specifies a default constructor so async support can be configured.
     */
    public CrawlerSessionManagerValve() {
        super(true);
    }


    /**
     * Specify the regular expression (using {@link Pattern}) that will be used
     * to identify crawlers based in the User-Agent header provided. The default
     * is ".*GoogleBot.*|.*bingbot.*|.*Yahoo! Slurp.*"
     *
     * @param crawlerUserAgents The regular expression using {@link Pattern}
     */
    public void setCrawlerUserAgents(String crawlerUserAgents) {
        this.crawlerUserAgents = crawlerUserAgents;
        if (crawlerUserAgents == null || crawlerUserAgents.length() == 0) {
            uaPattern = null;
        } else {
            uaPattern = Pattern.compile(crawlerUserAgents);
        }
    }

    /**
     * @see #setCrawlerUserAgents(String)
     * @return  The current regular expression being used to match user agents.
     */
    public String getCrawlerUserAgents() {
        return crawlerUserAgents;
    }


    /**
     * Specify the session timeout (in seconds) for a crawler's session. This is
     * typically lower than that for a user session. The default is 60 seconds.
     *
     * @param sessionInactiveInterval   The new timeout for crawler sessions
     */
    public void setSessionInactiveInterval(int sessionInactiveInterval) {
        this.sessionInactiveInterval = sessionInactiveInterval;
    }

    /**
     * @see #setSessionInactiveInterval(int)
     * @return  The current timeout in seconds
     */
    public int getSessionInactiveInterval() {
        return sessionInactiveInterval;
    }


    public Map<String,String> getClientIpSessionId() {
        return clientIpSessionId;
    }


    @Override
    protected void initInternal() throws LifecycleException {
        super.initInternal();

        uaPattern = Pattern.compile(crawlerUserAgents);
    }


    @Override
    public void invoke(Request request, Response response) throws IOException,
            ServletException {

        boolean isBot = false;                                                              // 识别这个请求是否是爬虫
        String sessionId = null;
        String clientIp = null;

        if (log.isDebugEnabled()) {
            log.debug(request.hashCode() + ": ClientIp=" +
                    request.getRemoteAddr() + ", RequestedSessionId=" +
                    request.getRequestedSessionId());
        }

        // If the incoming request has a valid session ID, no action is required
        if (request.getSession(false) == null) {

            // Is this a crawler - check the UA headers
            Enumeration<String> uaHeaders = request.getHeaders("user-agent");           // 通过 user-agent 来识别是否是爬虫 ( 没有 user-agent 肯定是 爬虫)
            String uaHeader = null;
            if (uaHeaders.hasMoreElements()) {
                uaHeader = uaHeaders.nextElement();
            }

            // If more than one UA header - assume not a bot
            if (uaHeader != null && !uaHeaders.hasMoreElements()) {

                if (log.isDebugEnabled()) {
                    log.debug(request.hashCode() + ": UserAgent=" + uaHeader);
                }

                if (uaPattern.matcher(uaHeader).matches()) {                           // 有 user-agent, 但 uaPattern 匹配了, 那其实也是爬虫
                    isBot = true;

                    if (log.isDebugEnabled()) {
                        log.debug(request.hashCode() +
                                ": Bot found. UserAgent=" + uaHeader);
                    }
                }
            }

            // If this is a bot, is the session ID known?
            if (isBot) {
                clientIp = request.getRemoteAddr();
                sessionId = clientIpSessionId.get(clientIp);                        // 若是爬虫, 则直接设置 sessionId (省得程序再创建了)
                if (sessionId != null) {
                    request.setRequestedSessionId(sessionId);
                    if (log.isDebugEnabled()) {
                        log.debug(request.hashCode() + ": SessionID=" +
                                sessionId);
                    }
                }
            }
        }

        getNext().invoke(request, response);

        if (isBot) {
            if (sessionId == null) {
                // Has bot just created a session, if so make a note of it
                HttpSession s = request.getSession(false);
                if (s != null) {
                    clientIpSessionId.put(clientIp, s.getId());                         // 是爬虫的话, 记录对应的 IP 与 sessionId
                    sessionIdClientIp.put(s.getId(), clientIp);
                    // #valueUnbound() will be called on session expiration
                    s.setAttribute(this.getClass().getName(), this);
                    s.setMaxInactiveInterval(sessionInactiveInterval);

                    if (log.isDebugEnabled()) {
                        log.debug(request.hashCode() +
                                ": New bot session. SessionID=" + s.getId());
                    }
                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug(request.hashCode() +
                            ": Bot session accessed. SessionID=" + sessionId);
                }
            }
        }
    }


    @Override
    public void valueBound(HttpSessionBindingEvent event) {
        // NOOP
    }


    @Override
    public void valueUnbound(HttpSessionBindingEvent event) {
        String clientIp = sessionIdClientIp.remove(event.getSession().getId());
        if (clientIp != null) {
            clientIpSessionId.remove(clientIp);
        }
    }
}

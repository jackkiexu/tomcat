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

package org.apache.tomcat.util.descriptor.web;

import java.util.EnumSet;

import javax.servlet.SessionTrackingMode;

/**
 * Representation of a session configuration element for a web application,
 * as represented in a <code>&lt;session-config&gt;</code> element in the
 * deployment descriptor.
 */
public class SessionConfig {
    // Session 超时时间
    private Integer sessionTimeout;
    // session 通过 cookie 存放的名字, 默认 JSessionId
    private String cookieName;
    /**
     * session 关联的 cookie 所在保存的域, 例如网站为 "www.job51.net/test/test.aspx", 那么 domain 默认为 www.jb51.net, 如果没有
     * 定义这个属性, 默认和当前 Context 配置的一样
     */
    private String cookieDomain;
    /**
     * cookieDomain 域名为前缀, cookiePath为后缀, 也是存储 Session 关联的 cookie所在保存的路径, 如果没有这个配置
     * 默认和当前 Context 配置的一样
      */

    private String cookiePath;
    // cookie 的注释
    private String cookieComment;
    // 就是不让 JS 读出 Cookie 的数据
    private Boolean cookieHttpOnly;
    // 只有在 https 协议中才会传递到 服务端
    private Boolean cookieSecure;
    // Session 以 cookie 形式存储的最大的时间
    private Integer cookieMaxAge;
    // Session 跟踪的策略, 可以是 cookie 跟踪, 或 URL 重写, 甚至让该应用的 cookie 只能在 https 中传递
    private final EnumSet<SessionTrackingMode> sessionTrackingModes =
        EnumSet.noneOf(SessionTrackingMode.class);

    public Integer getSessionTimeout() {
        return sessionTimeout;
    }
    public void setSessionTimeout(String sessionTimeout) {
        this.sessionTimeout = Integer.valueOf(sessionTimeout);
    }

    public String getCookieName() {
        return cookieName;
    }
    public void setCookieName(String cookieName) {
        this.cookieName = cookieName;
    }

    public String getCookieDomain() {
        return cookieDomain;
    }
    public void setCookieDomain(String cookieDomain) {
        this.cookieDomain = cookieDomain;
    }

    public String getCookiePath() {
        return cookiePath;
    }
    public void setCookiePath(String cookiePath) {
        this.cookiePath = cookiePath;
    }

    public String getCookieComment() {
        return cookieComment;
    }
    public void setCookieComment(String cookieComment) {
        this.cookieComment = cookieComment;
    }

    public Boolean getCookieHttpOnly() {
        return cookieHttpOnly;
    }
    public void setCookieHttpOnly(String cookieHttpOnly) {
        this.cookieHttpOnly = Boolean.valueOf(cookieHttpOnly);
    }

    public Boolean getCookieSecure() {
        return cookieSecure;
    }
    public void setCookieSecure(String cookieSecure) {
        this.cookieSecure = Boolean.valueOf(cookieSecure);
    }

    public Integer getCookieMaxAge() {
        return cookieMaxAge;
    }
    public void setCookieMaxAge(String cookieMaxAge) {
        this.cookieMaxAge = Integer.valueOf(cookieMaxAge);
    }

    public EnumSet<SessionTrackingMode> getSessionTrackingModes() {
        return sessionTrackingModes;
    }
    public void addSessionTrackingMode(String sessionTrackingMode) {
        sessionTrackingModes.add(
                SessionTrackingMode.valueOf(sessionTrackingMode));
    }

}

/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.catalina.mapper;

import org.apache.catalina.Context;
import org.apache.catalina.Host;
import org.apache.catalina.Wrapper;
import org.apache.tomcat.util.buf.MessageBytes;

/**
 * Mapping data.
 *
 * @author Remy Maucherat
 */
public class MappingData {
    // Mapper 路由表填充
    public Host host = null;                                                       // 匹配的 Host
    public Context context = null;                                                // 匹配的 Context
    public Context[] contexts = null;                                             // 匹配的 Context 列表, 用于匹配过程,  并非最终使用的结果
    public Wrapper wrapper = null;                                                // 匹配的 Wrapper
    public boolean jspWildCard = false;                                         // 对于 JspServlet 其对应的匹配 pattern 是否包含通配符
    // Tomcat 的 http 解析
    public final MessageBytes contextPath = MessageBytes.newInstance();          // Context 的路径
    public final MessageBytes requestPath = MessageBytes.newInstance();          // 相对于 Context 的请求路径
    public final MessageBytes wrapperPath = MessageBytes.newInstance();          // Servlet 的路径
    public final MessageBytes pathInfo = MessageBytes.newInstance();             // 相对于 Servlet 的请求路径

    public final MessageBytes redirectPath = MessageBytes.newInstance();        // 重定向 路径
    // 将 Mapping 信息填充到 Request 中, 这样就可以到后端 invoke 了
    public void recycle() {
        host = null;
        context = null;
        contexts = null;
        wrapper = null;
        jspWildCard = false;
        contextPath.recycle();
        requestPath.recycle();
        wrapperPath.recycle();
        pathInfo.recycle();
        redirectPath.recycle();
    }

}

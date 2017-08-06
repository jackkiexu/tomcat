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
package org.apache.catalina.startup;

import org.apache.catalina.core.ApplicationContextFacade;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLClassLoader;

import javax.servlet.ServletException;
import javax.servlet.http.*;

public class TesterServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("text/plain");            // 1. 请求返回的数据的格式
        PrintWriter out = resp.getWriter();           // 2. 获取 new CoyoteWriter(outputBuffer) (PS: outputBuffer是Response里面的内部类, 存储着要写回浏览器的数据)
        Object object = req.getParameter("name");     // 3. 在第一次获取参数时, http body 里面的数据
        HttpSession httpSession = req.getSession();   // 4. 在第一次获取 Session 时会初始化构建Session(PS: 也就是说, 只有在程序里面getSession时才会创建Session)
        httpSession.setAttribute("name", "xjk");      // 5. 在Session里面设置对应 KV 数据
        out.print("OK");                              // 6. 将数据写回 Response 的OutputBuffer里面(PS: 只有在Response commit 时才正真的写数据到浏览器里)

        Cookie[] cookies =  req.getCookies();
        resp.addCookie(new Cookie("name", "xjk"));



        URLClassLoader urlClassLoader = new URLClassLoader(((URLClassLoader)Thread.currentThread().getContextClassLoader()).getURLs());
        Thread.currentThread().setContextClassLoader(urlClassLoader);
        Thread thread = new Thread(){
            @Override
            public void run() {
                super.run();
                while (true){
                    try {
                        Thread.sleep(2 * 1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        };


        ApplicationContextFacade applicationContextFacade = (ApplicationContextFacade)req.getServletContext();
        System.out.println(applicationContextFacade.getClassLoader());
        System.out.println(thread.getContextClassLoader());
        System.out.println(Thread.currentThread().getContextClassLoader());
        System.out.println(req.getClass().getClassLoader());

    }
}

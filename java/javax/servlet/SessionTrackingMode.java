/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package javax.servlet;

/**
 * @since Servlet 3.0
 * Session 的跟踪方式
 *
 * 1. 将 sessionId 写入 cookie 中, 客户端和服务端每一次交互的时候, 都从 cookie 中取出 JsessionId, 查看 JsessionId
 */
public enum SessionTrackingMode {
    COOKIE,
    URL,
    SSL
}

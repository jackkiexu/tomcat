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


package org.apache.catalina.session;

/**
 * Implementation of the <b>Manager</b> interface that makes use of
 * a Store to swap active Sessions to disk. It can be configured to
 * achieve several different goals:
 *
 * <li>Persist sessions across restarts of the Container</li>
 * <li>Fault tolerance, keep sessions backed up on disk to allow
 *     recovery in the event of unplanned restarts.</li>
 * <li>Limit the number of active sessions kept in memory by
 *     swapping less active sessions out to disk.</li>
 *
 * @author Kief Morris (kief@kief.com)
 *
 * 将 超过 maxIdleTime 的 Session 放到持久化文件中
 * 持久化了 的Session, 在对应的 sessions(ConcurrentHashMap)里面就不存在了, 但在 Manager.findSession() 时, 若找不到, 则会到 持久化的文件/数据库 里面恢复出来
 */
public final class PersistentManager extends PersistentManagerBase {

    // ----------------------------------------------------- Instance Variables

    /**
     * The descriptive name of this Manager implementation (for logging).
     */
    protected static final String name = "PersistentManager";


    // ------------------------------------------------------------- Properties

    /**
     * Return the descriptive short name of this Manager implementation.
     */
    @Override
    public String getName() {
        return (name);
    }
 }


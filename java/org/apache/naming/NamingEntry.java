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
package org.apache.naming;


/**
 * Represents a binding in a NamingContext.
 *
 * 叶子抽象节点
 *
 * @author Remy Maucherat
 */
public class NamingEntry {

    // 绑定的对象直接就能拿到值, 类型通常是 Java 基础类型或者 Java Collection类型, 总之该对象直接占这个 NamingEntry 的存储空间
    public static final int ENTRY = 0;
    /**
     * 引用类型, 这些数据类型的实例很大, 只能通过引用指向这个实例
     */
    public static final int LINK_REF = 1;
    /**
     * 指的是一个映射, 还得 resolve 一下, 就像  resource-link-ref
     */
    public static final int REFERENCE = 2;
    /**
     * Context 也是一种类型, 当 type 为 Context 的时候, 说明该节点为非叶子节点, 还得继续向下级查找
     */
    public static final int CONTEXT = 10;


    public NamingEntry(String name, Object value, int type) {
        this.name = name;
        this.value = value;
        this.type = type;
    }


    /**
     * The type instance variable is used to avoid using RTTI when doing
     * lookups.
     */
    public int type;
    public final String name;
    public Object value;


    @Override
    public boolean equals(Object obj) {
        if (obj instanceof NamingEntry) {
            return name.equals(((NamingEntry) obj).name);
        } else {
            return false;
        }
    }


    @Override
    public int hashCode() {
        return name.hashCode();
    }
}

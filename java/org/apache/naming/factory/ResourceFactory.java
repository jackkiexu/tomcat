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


package org.apache.naming.factory;

import java.util.Hashtable;

import javax.naming.Context;
import javax.naming.Name;
import javax.naming.NamingException;
import javax.naming.RefAddr;
import javax.naming.Reference;
import javax.naming.spi.ObjectFactory;

import org.apache.naming.ResourceRef;

/**
 * Object factory for Resources.
 *
 * 参考
 * https://mp.weixin.qq.com/s?__biz=MzA4MTc3Nzk4NQ==&mid=2650076379&idx=1&sn=7be4c54a4a136c6742c39b67a00e2671&chksm=878f90f5b0f819e3a67bd6c8c1e5ca9591ca99ac8c42063362948a9f66eb3f66750dbf6039a8&mpshare=1&scene=23&srcid=0616vRNPPLPPiTlDhjsv3bmf#rd
 *
 * @author Remy Maucherat
 */
public class ResourceFactory
    implements ObjectFactory {


    // ----------------------------------------------------------- Constructors


    // -------------------------------------------------------------- Constants


    // ----------------------------------------------------- Instance Variables


    // --------------------------------------------------------- Public Methods


    // -------------------------------------------------- ObjectFactory Methods


    /**
     * Crete a new DataSource instance.
     *
     * @param obj The reference object describing the DataSource
     */
    @Override
    public Object getObjectInstance(Object obj, Name name, Context nameCtx,
                                    Hashtable<?,?> environment)
        throws Exception {

        if (obj instanceof ResourceRef) {
            Reference ref = (Reference) obj;
            ObjectFactory factory = null;
            RefAddr factoryRefAddr = ref.get(Constants.FACTORY);
            if (factoryRefAddr != null) {
                // Using the specified factory
                String factoryClassName =
                    factoryRefAddr.getContent().toString();
                // Loading factory
                ClassLoader tcl =
                    Thread.currentThread().getContextClassLoader();
                Class<?> factoryClass = null;
                if (tcl != null) {
                    try {
                        factoryClass = tcl.loadClass(factoryClassName);
                    } catch(ClassNotFoundException e) {
                        NamingException ex = new NamingException
                            ("Could not load resource factory class");
                        ex.initCause(e);
                        throw ex;
                    }
                } else {
                    try {
                        factoryClass = Class.forName(factoryClassName);
                    } catch(ClassNotFoundException e) {
                        NamingException ex = new NamingException
                            ("Could not load resource factory class");
                        ex.initCause(e);
                        throw ex;
                    }
                }
                if (factoryClass != null) {
                    try {
                        factory = (ObjectFactory) factoryClass.newInstance();
                    } catch (Exception e) {
                        if (e instanceof NamingException)
                            throw (NamingException) e;
                        NamingException ex = new NamingException
                            ("Could not create resource factory instance");
                        ex.initCause(e);
                        throw ex;
                    }
                }
            } else {
                if (ref.getClassName().equals("javax.sql.DataSource")) {
                    String javaxSqlDataSourceFactoryClassName =
                        System.getProperty("javax.sql.DataSource.Factory",
                                           Constants.DBCP_DATASOURCE_FACTORY);
                    try {
                        factory = (ObjectFactory)
                            Class.forName(javaxSqlDataSourceFactoryClassName)
                            .newInstance();
                    } catch (Exception e) {
                        NamingException ex = new NamingException
                            ("Could not create resource factory instance");
                        ex.initCause(e);
                        throw ex;
                    }
                } else if (ref.getClassName().equals("javax.mail.Session")) {
                    String javaxMailSessionFactoryClassName =
                        System.getProperty("javax.mail.Session.Factory",
                                           "org.apache.naming.factory.MailSessionFactory");
                    try {
                        factory = (ObjectFactory)
                            Class.forName(javaxMailSessionFactoryClassName)
                            .newInstance();
                    } catch(Throwable t) {
                        NamingException ex = new NamingException
                            ("Could not create resource factory instance");
                        ex.initCause(t);
                        throw ex;
                    }
                }
            }
            if (factory != null) {
                return factory.getObjectInstance
                    (obj, name, nameCtx, environment);
            } else {
                throw new NamingException
                    ("Cannot create resource instance");
            }
        }

        return null;

    }


}


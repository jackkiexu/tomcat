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
package org.apache.catalina.core;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.WeakHashMap;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import javax.ejb.EJB;
import javax.naming.Context;
import javax.naming.NamingException;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceUnit;
import javax.servlet.Filter;
import javax.servlet.Servlet;
import javax.xml.ws.WebServiceRef;

import org.apache.catalina.ContainerServlet;
import org.apache.catalina.Globals;
import org.apache.catalina.security.SecurityUtil;
import org.apache.catalina.util.Introspection;
import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.res.StringManager;

/**
 * 参考资料
 * http://blog.csdn.net/zhouzhiande/article/details/52136700
 */
public class DefaultInstanceManager implements InstanceManager {

    // Used when there are no annotations in a class
    private static final AnnotationCacheEntry[] ANNOTATIONS_EMPTY
        = new AnnotationCacheEntry[0];

    /**
     * The string manager for this package.
     */
    protected static final StringManager sm =
        StringManager.getManager(Constants.Package);

    private final Context context;
    private final Map<String, Map<String, String>> injectionMap;
    protected final ClassLoader classLoader;
    protected final ClassLoader containerClassLoader;
    protected final boolean privileged;
    protected final boolean ignoreAnnotations;
    private final Properties restrictedFilters = new Properties();
    private final Properties restrictedListeners = new Properties();
    private final Properties restrictedServlets = new Properties();

    // 这里的 annotationCache 是 servlet 的注解上引用的资源的缓存
    private final Map<Class<?>, AnnotationCacheEntry[]> annotationCache =
        new WeakHashMap<>();
    private final Map<String, String> postConstructMethods;
    private final Map<String, String> preDestroyMethods;

    public DefaultInstanceManager(Context context,
            Map<String, Map<String, String>> injectionMap,
            org.apache.catalina.Context catalinaContext,
            ClassLoader containerClassLoader) {
        classLoader = catalinaContext.getLoader().getClassLoader();
        privileged = catalinaContext.getPrivileged();
        this.containerClassLoader = containerClassLoader;
        ignoreAnnotations = catalinaContext.getIgnoreAnnotations();
        StringManager sm = StringManager.getManager(Constants.Package);

        /**
         * 在初始化 DefaultInstanceManager 时加载的下面几个文件其实就是
         * 对 servlet, Listener, filter 的访问起到限制的作用
         */
        try {
            InputStream is =
                this.getClass().getClassLoader().getResourceAsStream
                    ("org/apache/catalina/core/RestrictedServlets.properties");
            if (is != null) {
                restrictedServlets.load(is);
            } else {
                catalinaContext.getLogger().error(sm.getString(
                        "defaultInstanceManager.restrictedServletsResource"));
            }
        } catch (IOException e) {
            catalinaContext.getLogger().error(sm.getString(
                    "defaultInstanceManager.restrictedServletsResource"), e);
        }

        try {
            InputStream is =
                    this.getClass().getClassLoader().getResourceAsStream
                            ("org/apache/catalina/core/RestrictedListeners.properties");
            if (is != null) {
                restrictedListeners.load(is);
            } else {
                catalinaContext.getLogger().error(sm.getString(
                        "defaultInstanceManager.restrictedListenersResources"));
            }
        } catch (IOException e) {
            catalinaContext.getLogger().error(sm.getString(
                    "defaultInstanceManager.restrictedListenersResources"), e);
        }
        try {
            InputStream is =
                    this.getClass().getClassLoader().getResourceAsStream
                            ("org/apache/catalina/core/RestrictedFilters.properties");
            if (is != null) {
                restrictedFilters.load(is);
            } else {
                catalinaContext.getLogger().error(sm.getString(
                        "defaultInstanceManager.restrictedFiltersResource"));
            }
        } catch (IOException e) {
            catalinaContext.getLogger().error(sm.getString(
                    "defaultInstanceManager.restrictedServletsResources"), e);
        }
        this.context = context;
        this.injectionMap = injectionMap;
        this.postConstructMethods = catalinaContext.findPostConstructMethods();
        this.preDestroyMethods = catalinaContext.findPreDestroyMethods();
    }

    @Override
    public Object newInstance(Class<?> clazz) throws IllegalAccessException,
            InvocationTargetException, NamingException, InstantiationException {
        return newInstance(clazz.newInstance(), clazz);
    }

    @Override
    public Object newInstance(String className) throws IllegalAccessException,
            InvocationTargetException, NamingException, InstantiationException,
            ClassNotFoundException {
        Class<?> clazz = loadClassMaybePrivileged(className, classLoader);
        return newInstance(clazz.newInstance(), clazz);
    }

    @Override
    public Object newInstance(final String className, final ClassLoader classLoader)
            throws IllegalAccessException, NamingException,
            InvocationTargetException, InstantiationException,
            ClassNotFoundException {
        Class<?> clazz = classLoader.loadClass(className);
        return newInstance(clazz.newInstance(), clazz); // 直接通过 Class 反射获取
    }

    @Override
    public void newInstance(Object o)
            throws IllegalAccessException, InvocationTargetException, NamingException {
        newInstance(o, o.getClass());
    }

    // 自己的模板方法

    /**
     * 参考资料
     * http://mp.weixin.qq.com/s?__biz=MzA4MTc3Nzk4NQ==&mid=2650076096&idx=1&sn=d6ab83b803d0c68c1299c1acfc916c17&mpshare=1&scene=23&srcid=07020bRDG57JR2VR6ZkE9mlQ#rd
     */
    private Object newInstance(Object instance, Class<?> clazz)
            throws IllegalAccessException, InvocationTargetException, NamingException {
        if (!ignoreAnnotations) { // 找到当前的 inject 点, 从 injectionMap 中查找出当前 Servlet 的 Inject集合
            /**
             * 主要有
             * @Resource
             * @WebServiceRef
             * @PersistenceContext
             * @PersistenceUnit
             * @PostConstruct
             * @PreDestory
             */
            /**
             * 前面定义的应用拳击的注入集合 injectionMap, 是基于所有应用的, 而这里是基于特定的 Servlet 的
             * ， 所以需要从 injectMap 中 get到自己的 Servlet 的inject集合
             */
            Map<String, String> injections = assembleInjectionsFromClassHierarchy(clazz);                       // 从 Servlet 类的继承树中收集 Servlet的注解
            /**
             * 在前面我们得到 injectionMap 集合, 这个集合的 value 不是引用的本身, 而是
             *  jndiName, 之所以没有将这些引用直接实例化是因为 对于这些引用非常占用内存, 并且初始化的时间非常长
             *  我们是否想过一个好的办法, 假设每一次都引用, 是否将这些都缓存起来, 第一次虽然费点劲, 初始化时间长, 而
             *  下一次就直接可以跳过初始化这一步, 具体操作在 populateAnnotationsCache
             *  Tomcat 将每一个 Annotation 条目都做成了 AnnotationCacheEntry, 这一步主要是将这些
             *  映射关系建立起来, 并没有直接把引用创建出来, 直接赋值到 AnnotationCacheEntry 中,
             *  操作已经在 processAnnotations 完成
             */
            populateAnnotationsCache(clazz, injections); // 将jndi的引用实例化为 annotationCache引用集合, 并进行缓存起来

            /**
             * 将引用存入 AnnotationCacheEntry 中去
             * 通过 tomcat 自身的 JNDI 系统进行查询, 如果是方法的化, 再进行
             *  method.invoke, 如果是 field 的话, 直接返回 filed 即可,
             *  当这一步操作完以后, AnnotationCacheEntry 就缓存完毕, 下一次再请求 Servlet的话
             *  实例化就不需要这些步骤的
             *  目前 Servlet 都是单实例多线程的
             */
            processAnnotations(instance, injections);    // 根据注解说明, 调用 Servlet 的方法, 进行设置名称上下文的资源

            /**
             * 对于 PostConstruct 的方法的回调,这个是为了 JAVA EE 规范的 Common Annotation 规范
             * 整体的思路也是查询方法, 然后进行回调注入的方法
             */
            postConstruct(instance, clazz); // 实例化 Object // 设置 @PostConstruct/@PreDestory 类型的资源依赖
        }
        return instance;
    }

    private Map<String, String> assembleInjectionsFromClassHierarchy(Class<?> clazz) {
        Map<String, String> injections = new HashMap<>();
        Map<String, String> currentInjections = null;
        while (clazz != null) {     // 递归的进行获取 servlet 的注入资源
            currentInjections = this.injectionMap.get(clazz.getName());
            if (currentInjections != null) {
                injections.putAll(currentInjections);
            }
            clazz = clazz.getSuperclass();
        }
        return injections;
    }

    @Override
    public void destroyInstance(Object instance) throws IllegalAccessException,
            InvocationTargetException {
        if (!ignoreAnnotations) {
            preDestroy(instance, instance.getClass());
        }
    }

    /**
     * Call postConstruct method on the specified instance recursively from
     * deepest superclass to actual class.
     *
     * @param instance object to call postconstruct methods on
     * @param clazz    (super) class to examine for postConstruct annotation.
     * @throws IllegalAccessException if postConstruct method is inaccessible.
     * @throws java.lang.reflect.InvocationTargetException
     *                                if call fails
     */
    protected void postConstruct(Object instance, final Class<?> clazz)
            throws IllegalAccessException, InvocationTargetException {
        if (context == null) {
            // No resource injection
            return;
        }

        Class<?> superClass = clazz.getSuperclass();
        if (superClass != Object.class) {
            postConstruct(instance, superClass);
        }

        // At the end the postconstruct annotated
        // method is invoked
        AnnotationCacheEntry[] annotations;
        synchronized (annotationCache) {
            annotations = annotationCache.get(clazz);
        }
        for (AnnotationCacheEntry entry : annotations) {
            if (entry.getType() == AnnotationCacheEntryType.POST_CONSTRUCT) {
                Method postConstruct = getMethod(clazz, entry);
                synchronized (postConstruct) {
                    boolean accessibility = postConstruct.isAccessible();
                    postConstruct.setAccessible(true);
                    postConstruct.invoke(instance);
                    postConstruct.setAccessible(accessibility);
                }
            }
        }
    }


    /**
     * Call preDestroy method on the specified instance recursively from deepest
     * superclass to actual class.
     *
     * @param instance object to call preDestroy methods on
     * @param clazz    (super) class to examine for preDestroy annotation.
     * @throws IllegalAccessException if preDestroy method is inaccessible.
     * @throws java.lang.reflect.InvocationTargetException
     *                                if call fails
     */
    protected void preDestroy(Object instance, final Class<?> clazz)
            throws IllegalAccessException, InvocationTargetException {
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != Object.class) {
            preDestroy(instance, superClass);
        }

        // At the end the postconstruct annotated
        // method is invoked
        AnnotationCacheEntry[] annotations = null;
        synchronized (annotationCache) {
            annotations = annotationCache.get(clazz);
        }
        if (annotations == null) {
            // instance not created through the instance manager
            return;
        }
        for (AnnotationCacheEntry entry : annotations) {
            if (entry.getType() == AnnotationCacheEntryType.PRE_DESTROY) {
                Method preDestroy = getMethod(clazz, entry);
                synchronized (preDestroy) {
                    boolean accessibility = preDestroy.isAccessible();
                    preDestroy.setAccessible(true);
                    preDestroy.invoke(instance);
                    preDestroy.setAccessible(accessibility);
                }
            }
        }
    }


    /**
     * Make sure that the annotations cache has been populated for the provided
     * class.
     *
     * @param clazz         clazz to populate annotations for
     * @param injections    map of injections for this class from xml deployment
     *                      descriptor
     * @throws IllegalAccessException       if injection target is inaccessible
     * @throws javax.naming.NamingException if value cannot be looked up in jndi
     * @throws java.lang.reflect.InvocationTargetException
     *                                      if injection fails
     */
    protected void populateAnnotationsCache(Class<?> clazz,
            Map<String, String> injections) throws IllegalAccessException,
            InvocationTargetException, NamingException {

        List<AnnotationCacheEntry> annotations = null;

        while (clazz != null) {
            AnnotationCacheEntry[] annotationsArray = null;
            synchronized (annotationCache) {
                annotationsArray = annotationCache.get(clazz);
            }
            if (annotationsArray == null) {
                if (annotations == null) {
                    annotations = new ArrayList<>();
                } else {
                    annotations.clear();
                }

                if (context != null) {
                    // Initialize fields annotations for resource injection if
                    // JNDI is enabled
                    Field[] fields = Introspection.getDeclaredFields(clazz);
                    for (Field field : fields) {
                        if (injections != null && injections.containsKey(field.getName())) {
                            annotations.add(new AnnotationCacheEntry(
                                    field.getName(), null,
                                    injections.get(field.getName()),
                                    AnnotationCacheEntryType.FIELD));
                        } else if (field.isAnnotationPresent(Resource.class)) {
                            Resource annotation = field.getAnnotation(Resource.class);
                            annotations.add(new AnnotationCacheEntry(
                                    field.getName(), null, annotation.name(),
                                    AnnotationCacheEntryType.FIELD));
                        } else if (field.isAnnotationPresent(EJB.class)) {
                            EJB annotation = field.getAnnotation(EJB.class);
                            annotations.add(new AnnotationCacheEntry(
                                    field.getName(), null, annotation.name(),
                                    AnnotationCacheEntryType.FIELD));
                        } else if (field.isAnnotationPresent(WebServiceRef.class)) {
                            WebServiceRef annotation =
                                    field.getAnnotation(WebServiceRef.class);
                            annotations.add(new AnnotationCacheEntry(
                                    field.getName(), null, annotation.name(),
                                    AnnotationCacheEntryType.FIELD));
                        } else if (field.isAnnotationPresent(PersistenceContext.class)) {
                            PersistenceContext annotation =
                                    field.getAnnotation(PersistenceContext.class);
                            annotations.add(new AnnotationCacheEntry(
                                    field.getName(), null, annotation.name(),
                                    AnnotationCacheEntryType.FIELD));
                        } else if (field.isAnnotationPresent(PersistenceUnit.class)) {
                            PersistenceUnit annotation =
                                    field.getAnnotation(PersistenceUnit.class);
                            annotations.add(new AnnotationCacheEntry(
                                    field.getName(), null, annotation.name(),
                                    AnnotationCacheEntryType.FIELD));
                        }
                    }
                }

                // Initialize methods annotations
                Method[] methods = Introspection.getDeclaredMethods(clazz);
                Method postConstruct = null;
                String postConstructFromXml = postConstructMethods.get(clazz.getName());
                Method preDestroy = null;
                String preDestroyFromXml = preDestroyMethods.get(clazz.getName());
                for (Method method : methods) {
                    if (context != null) {
                        // Resource injection only if JNDI is enabled
                        if (injections != null &&
                                Introspection.isValidSetter(method)) {
                            String fieldName = Introspection.getPropertyName(method);
                            if (injections.containsKey(fieldName)) {
                                annotations.add(new AnnotationCacheEntry(
                                        method.getName(),
                                        method.getParameterTypes(),
                                        injections.get(fieldName),
                                        AnnotationCacheEntryType.SETTER));
                                continue;
                            }
                        }
                        if (method.isAnnotationPresent(Resource.class)) {
                            Resource annotation = method.getAnnotation(Resource.class);
                            annotations.add(new AnnotationCacheEntry(
                                    method.getName(),
                                    method.getParameterTypes(),
                                    annotation.name(),
                                    AnnotationCacheEntryType.SETTER));
                        } else if (method.isAnnotationPresent(EJB.class)) {
                            EJB annotation = method.getAnnotation(EJB.class);
                            annotations.add(new AnnotationCacheEntry(
                                    method.getName(),
                                    method.getParameterTypes(),
                                    annotation.name(),
                                    AnnotationCacheEntryType.SETTER));
                        } else if (method.isAnnotationPresent(WebServiceRef.class)) {
                            WebServiceRef annotation =
                                    method.getAnnotation(WebServiceRef.class);
                            annotations.add(new AnnotationCacheEntry(
                                    method.getName(),
                                    method.getParameterTypes(),
                                    annotation.name(),
                                    AnnotationCacheEntryType.SETTER));
                        } else if (method.isAnnotationPresent(PersistenceContext.class)) {
                            PersistenceContext annotation =
                                    method.getAnnotation(PersistenceContext.class);
                            annotations.add(new AnnotationCacheEntry(
                                    method.getName(),
                                    method.getParameterTypes(),
                                    annotation.name(),
                                    AnnotationCacheEntryType.SETTER));
                        } else if (method.isAnnotationPresent(PersistenceUnit.class)) {
                            PersistenceUnit annotation =
                                    method.getAnnotation(PersistenceUnit.class);
                            annotations.add(new AnnotationCacheEntry(
                                    method.getName(),
                                    method.getParameterTypes(),
                                    annotation.name(),
                                    AnnotationCacheEntryType.SETTER));
                        }
                    }

                    postConstruct = findPostConstruct(postConstruct, postConstructFromXml, method);

                    preDestroy = findPreDestroy(preDestroy, preDestroyFromXml, method);
                }

                if (postConstruct != null) {
                    annotations.add(new AnnotationCacheEntry(
                            postConstruct.getName(),
                            postConstruct.getParameterTypes(), null,
                            AnnotationCacheEntryType.POST_CONSTRUCT));
                } else if (postConstructFromXml != null) {
                    throw new IllegalArgumentException("Post construct method "
                        + postConstructFromXml + " for class " + clazz.getName()
                        + " is declared in deployment descriptor but cannot be found.");
                }
                if (preDestroy != null) {
                    annotations.add(new AnnotationCacheEntry(
                            preDestroy.getName(),
                            preDestroy.getParameterTypes(), null,
                            AnnotationCacheEntryType.PRE_DESTROY));
                } else if (preDestroyFromXml != null) {
                    throw new IllegalArgumentException("Pre destroy method "
                        + preDestroyFromXml + " for class " + clazz.getName()
                        + " is declared in deployment descriptor but cannot be found.");
                }
                if (annotations.isEmpty()) {
                    // Use common object to save memory
                    annotationsArray = ANNOTATIONS_EMPTY;
                } else {
                    annotationsArray = annotations.toArray(
                            new AnnotationCacheEntry[annotations.size()]);
                }
                synchronized (annotationCache) {
                    annotationCache.put(clazz, annotationsArray);
                }
            }
            clazz = clazz.getSuperclass();
        }
    }


    /**
     * Inject resources in specified instance.
     *
     * @param instance   instance to inject into
     * @param injections map of injections for this class from xml deployment descriptor
     * @throws IllegalAccessException       if injection target is inaccessible
     * @throws javax.naming.NamingException if value cannot be looked up in jndi
     * @throws java.lang.reflect.InvocationTargetException
     *                                      if injection fails
     */
    // 从名称上下文中取得需要注入的资源
    protected void processAnnotations(Object instance, Map<String, String> injections)
            throws IllegalAccessException, InvocationTargetException, NamingException {

        if (context == null) {
            // No resource injection
            return;
        }

        Class<?> clazz = instance.getClass();

        while (clazz != null) {
            AnnotationCacheEntry[] annotations;
            synchronized (annotationCache) {
                annotations = annotationCache.get(clazz);
            }
            for (AnnotationCacheEntry entry : annotations) {
                if (entry.getType() == AnnotationCacheEntryType.SETTER) {           // 通过 setter的反射出来的注解
                    lookupMethodResource(context, instance,                         // 调用 Servlet 的方法, 进行设置资源
                            getMethod(clazz, entry),
                            entry.getName(), clazz);
                } else if (entry.getType() == AnnotationCacheEntryType.FIELD) {
                    lookupFieldResource(context, instance,
                            getField(clazz, entry),
                            entry.getName(), clazz);
                }
            }
            clazz = clazz.getSuperclass();
        }
    }


    /**
     * Makes cache size available to unit tests.
     */
    protected int getAnnotationCacheSize() {
        synchronized (annotationCache) {
            return annotationCache.size();
        }
    }

    // 通过 ClassLoader  来进行加载 class
    protected Class<?> loadClassMaybePrivileged(final String className,
            final ClassLoader classLoader) throws ClassNotFoundException {
        Class<?> clazz;
        if (SecurityUtil.isPackageProtectionEnabled()) {
            try {
                clazz = AccessController.doPrivileged(new PrivilegedExceptionAction<Class<?>>() {

                    @Override
                    public Class<?> run() throws Exception {
                        return loadClass(className, classLoader);
                    }
                });
            } catch (PrivilegedActionException e) {
                Throwable t = e.getCause();
                if (t instanceof ClassNotFoundException) {
                    throw (ClassNotFoundException) t;
                }
                throw new RuntimeException(t);
            }
        } else {
            clazz = loadClass(className, classLoader);
        }
        checkAccess(clazz);
        return clazz;
    }
    // 通过 classLoader 来实例化 Class对象
    protected Class<?> loadClass(String className, ClassLoader classLoader)
            throws ClassNotFoundException {
        if (className.startsWith("org.apache.catalina")) {
            return containerClassLoader.loadClass(className);
        }
        try {
            Class<?> clazz = containerClassLoader.loadClass(className);
            if (ContainerServlet.class.isAssignableFrom(clazz)) {
                return clazz;
            }
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
        }
        return classLoader.loadClass(className);
    }

    private void checkAccess(Class<?> clazz) {
        if (privileged) {
            return;
        }
        if (Filter.class.isAssignableFrom(clazz)) {
            checkAccess(clazz, restrictedFilters);      // 检查clazz 是否在受限制的 Filter 里面
        } else if (Servlet.class.isAssignableFrom(clazz)) {
            if (ContainerServlet.class.isAssignableFrom(clazz)) {
                throw new SecurityException("Restricted (ContainerServlet) " +
                        clazz);
            }
            checkAccess(clazz, restrictedServlets);    // 检查clazz 是否在受限制的 Servlet 里面
        } else {
            checkAccess(clazz, restrictedListeners);   // 检查clazz 是否在受限制的 Listener 里面
        }
    }

    private void checkAccess(Class<?> clazz, Properties restricted) {
        while (clazz != null) {
            if ("restricted".equals(restricted.getProperty(clazz.getName()))) {
                throw new SecurityException("Restricted " + clazz);
            }
            clazz = clazz.getSuperclass();
        }

    }

    /**
     * Inject resources in specified field.
     *
     * @param context  jndi context to extract value from
     * @param instance object to inject into
     * @param field    field target for injection
     * @param name     jndi name value is bound under
     * @param clazz    class annotation is defined in
     * @throws IllegalAccessException       if field is inaccessible
     * @throws javax.naming.NamingException if value is not accessible in naming context
     */
    protected static void lookupFieldResource(Context context,
            Object instance, Field field, String name, Class<?> clazz)
            throws NamingException, IllegalAccessException {

        Object lookedupResource;
        boolean accessibility;

        String normalizedName = normalize(name);

        if ((normalizedName != null) && (normalizedName.length() > 0)) {
            lookedupResource = context.lookup(normalizedName);
        } else {        // 这里就是 通过 JNDI 来进行查找类
            lookedupResource =
                context.lookup(clazz.getName() + "/" + field.getName());
        }

        synchronized (field) {
            accessibility = field.isAccessible();
            field.setAccessible(true);
            field.set(instance, lookedupResource);
            field.setAccessible(accessibility);
        }
    }

    /**
     * Inject resources in specified method.
     *
     * @param context  jndi context to extract value from
     * @param instance object to inject into
     * @param method   field target for injection
     * @param name     jndi name value is bound under
     * @param clazz    class annotation is defined in
     * @throws IllegalAccessException       if method is inaccessible
     * @throws javax.naming.NamingException if value is not accessible in naming context
     * @throws java.lang.reflect.InvocationTargetException
     *                                      if setter call fails
     */
    // 在 名称上下文中查找资源
    protected static void lookupMethodResource(Context context,
            Object instance, Method method, String name, Class<?> clazz)
            throws NamingException, IllegalAccessException, InvocationTargetException {

        if (!Introspection.isValidSetter(method)) {
            throw new IllegalArgumentException(
                    sm.getString("defaultInstanceManager.invalidInjection"));
        }

        Object lookedupResource;
        boolean accessibility;

        String normalizedName = normalize(name);

        if ((normalizedName != null) && (normalizedName.length() > 0)) {
            lookedupResource = context.lookup(normalizedName);                  // 在名称上下文中查找资源
        } else {
            lookedupResource = context.lookup(
                    clazz.getName() + "/" + Introspection.getPropertyName(method));
        }

        synchronized (method) {
            accessibility = method.isAccessible();
            method.setAccessible(true);
            method.invoke(instance, lookedupResource);                         // 调用 Servlet 的方法, 进行资源的设置
            method.setAccessible(accessibility);
        }
    }

    private static String normalize(String jndiName){
        if(jndiName != null && jndiName.startsWith("java:comp/env/")){
            return jndiName.substring(14);
        }
        return jndiName;
    }

    private static Method getMethod(final Class<?> clazz,
            final AnnotationCacheEntry entry) {
        Method result = null;
        if (Globals.IS_SECURITY_ENABLED) {
            result = AccessController.doPrivileged(
                    new PrivilegedAction<Method>() {
                        @Override
                        public Method run() {
                            Method result = null;
                            try {
                                result = clazz.getDeclaredMethod(
                                        entry.getAccessibleObjectName(),
                                        entry.getParamTypes());
                            } catch (NoSuchMethodException e) {
                                // Should never happen. On that basis don't log
                                // it.
                            }
                            return result;
                        }
            });
        } else {
            try {
                result = clazz.getDeclaredMethod(
                        entry.getAccessibleObjectName(), entry.getParamTypes());
            } catch (NoSuchMethodException e) {
                // Should never happen. On that basis don't log it.
            }
        }
        return result;
    }

    private static Field getField(final Class<?> clazz,
            final AnnotationCacheEntry entry) {
        Field result = null;
        if (Globals.IS_SECURITY_ENABLED) {
            result = AccessController.doPrivileged(
                    new PrivilegedAction<Field>() {
                        @Override
                        public Field run() {
                            Field result = null;
                            try {
                                result = clazz.getDeclaredField(
                                        entry.getAccessibleObjectName());
                            } catch (NoSuchFieldException e) {
                                // Should never happen. On that basis don't log
                                // it.
                            }
                            return result;
                        }
            });
        } else {
            try {
                result = clazz.getDeclaredField(
                        entry.getAccessibleObjectName());
            } catch (NoSuchFieldException e) {
                // Should never happen. On that basis don't log it.
            }
        }
        return result;
    }


    private static Method findPostConstruct(Method currentPostConstruct,
            String postConstructFromXml, Method method) {
        return findLifecycleCallback(currentPostConstruct,
            postConstructFromXml, method, PostConstruct.class);
    }

    private static Method findPreDestroy(Method currentPreDestroy,
        String preDestroyFromXml, Method method) {
        return findLifecycleCallback(currentPreDestroy,
            preDestroyFromXml, method, PreDestroy.class);
    }

    private static Method findLifecycleCallback(Method currentMethod,
            String methodNameFromXml, Method method,
            Class<? extends Annotation> annotation) {
        Method result = currentMethod;
        if (methodNameFromXml != null) {
            if (method.getName().equals(methodNameFromXml)) {
                if (!Introspection.isValidLifecycleCallback(method)) {
                    throw new IllegalArgumentException(
                        "Invalid " + annotation.getName() + " annotation");
                }
                result = method;
            }
        } else {
            if (method.isAnnotationPresent(annotation)) {
                if (currentMethod != null ||
                    !Introspection.isValidLifecycleCallback(method)) {
                    throw new IllegalArgumentException(
                        "Invalid " + annotation.getName() + " annotation");
                }
                result = method;
            }
        }
        return result;
    }

    private static final class AnnotationCacheEntry {
        private final String accessibleObjectName;
        private final Class<?>[] paramTypes;
        private final String name;
        private final AnnotationCacheEntryType type;

        public AnnotationCacheEntry(String accessibleObjectName,
                Class<?>[] paramTypes, String name,
                AnnotationCacheEntryType type) {
            this.accessibleObjectName = accessibleObjectName;
            this.paramTypes = paramTypes;
            this.name = name;
            this.type = type;
        }

        public String getAccessibleObjectName() {
            return accessibleObjectName;
        }

        public Class<?>[] getParamTypes() {
            return paramTypes;
        }

        public String getName() {
            return name;
        }
        public AnnotationCacheEntryType getType() {
            return type;
        }
    }

    private static enum AnnotationCacheEntryType {
        FIELD, SETTER, POST_CONSTRUCT, PRE_DESTROY
    }
}

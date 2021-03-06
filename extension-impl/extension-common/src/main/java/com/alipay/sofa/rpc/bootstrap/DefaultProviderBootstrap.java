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
package com.alipay.sofa.rpc.bootstrap;

import com.alipay.sofa.rpc.common.utils.CommonUtils;
import com.alipay.sofa.rpc.common.utils.ExceptionUtils;
import com.alipay.sofa.rpc.common.utils.ReflectUtils;
import com.alipay.sofa.rpc.common.utils.StringUtils;
import com.alipay.sofa.rpc.config.ProviderConfig;
import com.alipay.sofa.rpc.config.RegistryConfig;
import com.alipay.sofa.rpc.config.ServerConfig;
import com.alipay.sofa.rpc.context.RpcRuntimeContext;
import com.alipay.sofa.rpc.core.exception.SofaRpcRuntimeException;
import com.alipay.sofa.rpc.invoke.Invoker;
import com.alipay.sofa.rpc.listener.ConfigListener;
import com.alipay.sofa.rpc.log.Logger;
import com.alipay.sofa.rpc.log.LoggerFactory;
import com.alipay.sofa.rpc.registry.Registry;
import com.alipay.sofa.rpc.registry.RegistryFactory;
import com.alipay.sofa.rpc.server.ProviderProxyInvoker;
import com.alipay.sofa.rpc.server.Server;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default provider bootstrap.
 *
 * @author <a href=mailto:zhanggeng.zg@antfin.com>GengZhang</a>
 */
public class DefaultProviderBootstrap<T> extends ProviderBootstrap<T> {

    /**
     * slf4j Logger for this class
     */
    private final static Logger LOGGER = LoggerFactory.getLogger(DefaultProviderBootstrap.class);

    /**
     * 构造函数
     *
     * @param providerConfig 服务发布者配置
     */
    protected DefaultProviderBootstrap(ProviderConfig<T> providerConfig) {
        super(providerConfig);
    }

    /**
     * 是否已发布
     */
    protected transient volatile boolean                     exported;

    /**
     * 服务端Invoker对象
     */
    protected transient Invoker                              providerProxyInvoker;

    /**
     * 发布的服务配置
     */
    protected final ConcurrentHashMap<String, AtomicInteger> EXPORTED_KEYS = new ConcurrentHashMap<String, AtomicInteger>();

    @Override   //暴露服务
    public void export() {
        if (providerConfig.getDelay() > 0) { // 延迟加载,单位毫秒
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(providerConfig.getDelay());
                    } catch (Throwable ignore) { // NOPMD
                    }
                    doExport();
                }
            });
            thread.setDaemon(true);
            thread.setName("DelayExportThread");
            thread.start();
        } else {
            doExport();
        }
    }
    //暴露服务主要是为了给我provider创建Server、Request handler、ProxyInvoker
    private void doExport() {
        if (exported) {
            return;
        }
        String key = providerConfig.buildKey();//interface+uniqueId
        String appName = providerConfig.getAppName();
        // 检查参数
        checkParameters();
        if (LOGGER.isInfoEnabled(appName)) {
            LOGGER.infoWithApp(appName, "Export provider config : {} with bean id {}", key, providerConfig.getId());
        }

        // 注意同一interface，同一uniqleId，不同server情况
        AtomicInteger cnt = EXPORTED_KEYS.get(key); // 计数器
        if (cnt == null) { // 没有发布过
            cnt = CommonUtils.putToConcurrentMap(EXPORTED_KEYS, key, new AtomicInteger(0));
        }
        int c = cnt.incrementAndGet();
        int maxProxyCount = providerConfig.getRepeatedExportLimit();
        if (maxProxyCount > 0) {
            if (c > maxProxyCount) {
                cnt.decrementAndGet();
                // 超过最大数量，直接抛出异常
                throw new SofaRpcRuntimeException("Duplicate provider config with key " + key
                    + " has been exported more than " + maxProxyCount + " times!"
                    + " Maybe it's wrong config, please check it."
                    + " Ignore this if you did that on purpose!");
            } else if (c > 1) {
                if (LOGGER.isInfoEnabled(appName)) {
                    LOGGER.infoWithApp(appName, "Duplicate provider config with key {} has been exported!"
                        + " Maybe it's wrong config, please check it."
                        + " Ignore this if you did that on purpose!", key);
                }
            }
        }

        try {
            // 构造请求调用器链
            providerProxyInvoker = new ProviderProxyInvoker(providerConfig);
            // 初始化注册中心
            if (providerConfig.isRegister()) {
                List<RegistryConfig> registryConfigs = providerConfig.getRegistry();//获取注册中心配置
                if (CommonUtils.isNotEmpty(registryConfigs)) {
                    for (RegistryConfig registryConfig : registryConfigs) {
                        RegistryFactory.getRegistry(registryConfig); // 根据注册配置获取一个Registry，没有则根据RegistryConfig初始化一个
                    }
                }
            }
            // 将处理器注册到server
            List<ServerConfig> serverConfigs = providerConfig.getServer();//获取server
            for (ServerConfig serverConfig : serverConfigs) {//遍历所有的server配置
                try {
                    Server server = serverConfig.buildIfAbsent();//创建一个Server
                    //注册提供者代理接口
                    server.registerProcessor(providerConfig, providerProxyInvoker);
                    if (serverConfig.isAutoStart()) {//server start
                        server.start();//启动BOLTServer
                    }
                } catch (SofaRpcRuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    LOGGER.errorWithApp(appName, "Catch exception when register processor to server: "
                        + serverConfig.getId(), e);
                }
            }

            // 注册到注册中心
            providerConfig.setConfigListener(new ProviderAttributeListener());//provider注册一个配置监听器
            register();
        } catch (Exception e) {
            cnt.decrementAndGet();
            if (e instanceof SofaRpcRuntimeException) {
                throw (SofaRpcRuntimeException) e;
            } else {
                throw new SofaRpcRuntimeException("Build provider proxy error!", e);
            }
        }

        // 记录一些缓存数据
        RpcRuntimeContext.cacheProviderConfig(this);
        exported = true;
    }

    /**
     * for check fields and parameters of consumer config 
     */
    protected void checkParameters() {
        // 检查注入的ref是否接口实现类
        Class proxyClass = providerConfig.getProxyClass();
        String key = providerConfig.buildKey();
        T ref = providerConfig.getRef();//接口的实现类
        if (!proxyClass.isInstance(ref)) {
            throw ExceptionUtils.buildRuntime("provider.ref",
                ref == null ? "null" : ref.getClass().getName(),
                "This is not an instance of " + providerConfig.getInterfaceId()
                    + " in provider config with key " + key + " !");
        }
        // server 不能为空
        if (CommonUtils.isEmpty(providerConfig.getServer())) {
            throw ExceptionUtils.buildRuntime("server", "NULL", "Value of \"server\" is not specified in provider" +
                " config with key " + key + " !");
        }
        checkMethods(proxyClass);
    }

    /**
     * 检查方法，例如方法名、多态（重载）方法
     * 
     * @param itfClass 接口类
     */
    protected void checkMethods(Class<?> itfClass) {
        ConcurrentHashMap<String, Boolean> methodsLimit = new ConcurrentHashMap<String, Boolean>();
        for (Method method : itfClass.getMethods()) {
            String methodName = method.getName();
            if (methodsLimit.containsKey(methodName)) {
                // 重名的方法
                if (LOGGER.isWarnEnabled(providerConfig.getAppName())) {
                    LOGGER.warnWithApp(providerConfig.getAppName(), "Method with same name \"" + itfClass.getName()
                        + "." + methodName + "\" exists ! The usage of overloading method in rpc is deprecated.");
                }
            }
            // 判断服务下方法的黑白名单
            Boolean include = methodsLimit.get(methodName);
            if (include == null) {
                include = inList(providerConfig.getInclude(), providerConfig.getExclude(), methodName); // 检查是否在黑白名单中
                methodsLimit.putIfAbsent(methodName, include);
            }
            ReflectUtils.cacheMethodArgsType(providerConfig.getInterfaceId(), methodName, method.getParameterTypes());
            providerConfig.setMethodsLimit(methodsLimit);
        }
    }

    @Override
    public void unExport() {
        if (!exported) {
            return;
        }
        synchronized (this) {
            if (!exported) {
                return;
            }

            String key = providerConfig.buildKey();
            String appName = providerConfig.getAppName();
            if (LOGGER.isInfoEnabled(appName)) {
                LOGGER.infoWithApp(appName, "Unexport provider config : {} {}", key, providerConfig.getId() != null
                    ? "with bean id " + providerConfig.getId() : "");
            }

            // 取消注册到注册中心
            unregister();

            providerProxyInvoker = null;

            // 取消将处理器注册到server
            List<ServerConfig> serverConfigs = providerConfig.getServer();
            if (serverConfigs != null) {
                for (ServerConfig serverConfig : serverConfigs) {
                    Server server = serverConfig.getServer();
                    if (server != null) {
                        try {
                            server.unRegisterProcessor(providerConfig, serverConfig.isAutoStart());
                        } catch (Exception e) {
                            if (LOGGER.isWarnEnabled(appName)) {
                                LOGGER.warnWithApp(appName, "Catch exception when unRegister processor to server: " +
                                    serverConfig.getId()
                                    + ", but you can ignore if it's called by JVM shutdown hook", e);
                            }
                        }
                    }
                }
            }

            providerConfig.setConfigListener(null);

            // 清除缓存状态
            AtomicInteger cnt = EXPORTED_KEYS.get(key);
            if (cnt != null && cnt.decrementAndGet() <= 0) {
                EXPORTED_KEYS.remove(key);
            }
            RpcRuntimeContext.invalidateProviderConfig(this);
            exported = false;
        }
    }

    /**
     * 订阅服务列表
     */
    protected void register() {
        if (providerConfig.isRegister()) {//是否有注册器
            List<RegistryConfig> registryConfigs = providerConfig.getRegistry();//获取所有的provider的配置
            if (registryConfigs != null) {
                for (RegistryConfig registryConfig : registryConfigs) {
                    Registry registry = RegistryFactory.getRegistry(registryConfig);//根据注册中心配置获取Registry
                    registry.init();//加载配置并初始化ZK Client
                    registry.start();
                    try {
                        registry.register(providerConfig);//注册服务，provider+config这两种配置
                    } catch (SofaRpcRuntimeException e) {
                        throw e;
                    } catch (Throwable e) {
                        String appName = providerConfig.getAppName();
                        if (LOGGER.isWarnEnabled(appName)) {
                            LOGGER.warnWithApp(appName, "Catch exception when register to registry: "
                                + registryConfig.getId(), e);
                        }
                    }
                }
            }
        }
    }

    /**
     * 取消订阅服务列表
     */
    protected void unregister() {
        if (providerConfig.isRegister()) {
            List<RegistryConfig> registryConfigs = providerConfig.getRegistry();
            if (registryConfigs != null) {
                for (RegistryConfig registryConfig : registryConfigs) {
                    Registry registry = RegistryFactory.getRegistry(registryConfig);
                    try {
                        registry.unRegister(providerConfig);
                    } catch (Exception e) {
                        String appName = providerConfig.getAppName();
                        if (LOGGER.isWarnEnabled(appName)) {
                            LOGGER.warnWithApp(appName, "Catch exception when unRegister from registry: " +
                                registryConfig.getId()
                                + ", but you can ignore if it's called by JVM shutdown hook", e);
                        }
                    }
                }
            }
        }
    }

    /**
     * Provider配置发生变化监听器
     */
    private class ProviderAttributeListener implements ConfigListener {

        @Override
        public void configChanged(Map newValue) {
        }

        @Override
        public synchronized void attrUpdated(Map newValueMap) {
            String appName = providerConfig.getAppName();
            // 可以改变的配置 例如tag concurrents等
            Map<String, String> newValues = (Map<String, String>) newValueMap;
            Map<String, String> oldValues = new HashMap<String, String>();
            boolean reexport = false;

            // TODO 可能需要处理ServerConfig的配置变化
            try { // 检查是否有变化
                  // 是否过滤map?
                for (Map.Entry<String, String> entry : newValues.entrySet()) {
                    String newValue = entry.getValue();
                    String oldValue = providerConfig.queryAttribute(entry.getKey());
                    boolean changed = oldValue == null ? newValue != null : !oldValue.equals(newValue);
                    if (changed) {
                        oldValues.put(entry.getKey(), oldValue);
                    }
                    reexport = reexport || changed;
                }
            } catch (Exception e) {
                LOGGER.errorWithApp(appName, "Catch exception when provider attribute compare", e);
                return;
            }

            // 需要重新发布
            if (reexport) {
                try {
                    if (LOGGER.isInfoEnabled(appName)) {
                        LOGGER.infoWithApp(appName, "Reexport service {}", providerConfig.buildKey());
                    }
                    unExport();
                    // change attrs
                    for (Map.Entry<String, String> entry : newValues.entrySet()) {
                        providerConfig.updateAttribute(entry.getKey(), entry.getValue(), true);
                    }
                    export();
                } catch (Exception e) {
                    LOGGER.errorWithApp(appName, "Catch exception when provider attribute changed", e);
                    //rollback old attrs
                    for (Map.Entry<String, String> entry : oldValues.entrySet()) {
                        providerConfig.updateAttribute(entry.getKey(), entry.getValue(), true);
                    }
                    export();
                }
            }

        }
    }

    /**
     * 接口可以按方法发布
     *
     * @param includeMethods 包含的方法列表
     * @param excludeMethods 不包含的方法列表
     * @param methodName     方法名
     * @return 方法
     */
    protected boolean inList(String includeMethods, String excludeMethods, String methodName) {
        //判断是否在白名单中
        if (includeMethods != null && !StringUtils.ALL.equals(includeMethods)) {
            includeMethods = includeMethods + ",";
            boolean inWhite = includeMethods.contains(methodName + ",");
            if (!inWhite) {
                return false;
            }
        }
        //判断是否在黑白单中
        if (StringUtils.isBlank(excludeMethods)) {
            return true;
        } else {
            excludeMethods = excludeMethods + ",";
            boolean inBlack = excludeMethods.contains(methodName + ",");
            return !inBlack;
        }
    }
}

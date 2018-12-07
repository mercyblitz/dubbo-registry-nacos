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
package com.alibaba.dubbo.registry.nacos;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.Registry;
import com.alibaba.dubbo.registry.support.FailbackRegistry;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Nacos {@link Registry}
 *
 * @since 2.6.5
 */
public class NacosRegistry extends FailbackRegistry {

    private static final String[] ALL_CATEGORIES = of(
            Constants.PROVIDERS_CATEGORY,
            Constants.CONSUMERS_CATEGORY,
            Constants.ROUTERS_CATEGORY,
            Constants.CONFIGURATORS_CATEGORY
    );

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final NamingService namingService;

    private final ConcurrentMap<String, EventListener> nacosListeners;

    public NacosRegistry(URL url, NamingService namingService) {
        super(url);
        this.namingService = namingService;
        this.nacosListeners = new ConcurrentHashMap<String, EventListener>();
    }

    @Override
    public boolean isAvailable() {
        return "UP".equals(namingService.getServerStatus());
    }

    @Override
    public List<URL> lookup(final URL url) {
        final List<URL> urls = new LinkedList<URL>();
        execute(new NamingServiceCallback() {
            @Override
            public void callback(NamingService namingService) throws NacosException {
                List<String> serviceNames = getServiceNames(url);
                for (String serviceName : serviceNames) {
                    List<Instance> instances = namingService.getAllInstances(serviceName);
                    urls.addAll(buildURLs(url, instances));
                }
            }
        });
        return urls;
    }

    @Override
    protected void doRegister(URL url) {
        final String serviceName = getServiceName(url);
        final Instance instance = createInstance(url);
        execute(new NamingServiceCallback() {
            @Override
            public void callback(NamingService namingService) throws NacosException {
                namingService.registerInstance(serviceName, instance);
            }
        });
    }

    @Override
    protected void doUnregister(final URL url) {
        execute(new NamingServiceCallback() {
            @Override
            public void callback(NamingService namingService) throws NacosException {
                String serviceName = getServiceName(url);
                Instance instance = createInstance(url);
                namingService.deregisterInstance(serviceName, instance.getIp(), instance.getPort());
            }
        });
    }

    @Override
    protected void doSubscribe(final URL url, final NotifyListener listener) {
        execute(new NamingServiceCallback() {
            @Override
            public void callback(NamingService namingService) throws NacosException {
                List<String> serviceNames = getServiceNames(url);
                for (String serviceName : serviceNames) {
                    List<Instance> instances = namingService.getAllInstances(serviceName);
                    List<URL> urls = buildURLs(url, instances);
                    NacosRegistry.this.notify(url, listener, urls);
                    subscribeEventListener(serviceName, url, listener);
                }
            }
        });
    }

    @Override
    protected void doUnsubscribe(URL url, NotifyListener listener) {
    }

    private List<String> getServiceNames(URL url) {
        String[] categories = getCategories(url);
        List<String> serviceNames = new ArrayList<String>(categories.length);
        for (String category : categories) {
            final String serviceName = getServiceName(url, category);
            serviceNames.add(serviceName);
        }
        return serviceNames;
    }

    private List<URL> buildURLs(URL consumerURL, Collection<Instance> instances) {
        List<URL> urls = new LinkedList<URL>();
        for (Instance instance : instances) {
            URL providerURL = buildURL(consumerURL, instance);
            if (UrlUtils.isMatch(consumerURL, providerURL)) {
                urls.add(providerURL);
            }
        }
        return urls;
    }

    private void subscribeEventListener(String serviceName, final URL url, final NotifyListener listener)
            throws NacosException {
        if (!nacosListeners.containsKey(serviceName)) {
            EventListener eventListener = new EventListener() {
                @Override
                public void onEvent(Event event) {
                    if (event instanceof NamingEvent) {
                        NamingEvent e = (NamingEvent) event;
                        List<Instance> instances = e.getInstances();
                        List<URL> providerURLs = buildURLs(url, instances);
                        NacosRegistry.this.notify(url, listener, providerURLs);
                    }
                }
            };
            namingService.subscribe(serviceName, eventListener);
            nacosListeners.put(serviceName, eventListener);
        }
    }

    /**
     * Get the categories from {@link URL}
     *
     * @param url {@link URL}
     * @return non-null array
     */
    private String[] getCategories(URL url) {
        return Constants.ANY_VALUE.equals(url.getServiceInterface()) ?
                ALL_CATEGORIES : of(Constants.DEFAULT_CATEGORY);
    }

    private URL buildURL(URL consumerURL, Instance instance) {
        URL url = new URL(instance.getMetadata().get(Constants.PROTOCOL_KEY),
                instance.getIp(),
                instance.getPort(),
                instance.getMetadata());
        return url;
    }

    private Instance createInstance(URL url) {
        // Append default category if absent
        String category = url.getParameter(Constants.CATEGORY_KEY, Constants.DEFAULT_CATEGORY);
        URL newURL = url.addParameter(Constants.CATEGORY_KEY, category);
        newURL = newURL.addParameter(Constants.PROTOCOL_KEY, url.getProtocol());
        String ip = NetUtils.getLocalHost();
        int port = newURL.getParameter(Constants.BIND_PORT_KEY, url.getPort());
        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setMetadata(new HashMap<String, String>(newURL.getParameters()));
        return instance;
    }

    private String getServiceName(URL url) {
        String category = url.getParameter(Constants.CATEGORY_KEY, Constants.DEFAULT_CATEGORY);
        return getServiceName(url, category);
    }

    private String getServiceName(URL url, String category) {
        StringBuilder serviceNameBuilder = new StringBuilder(category);
        appendIfPresent(serviceNameBuilder, url, Constants.INTERFACE_KEY);
        appendIfPresent(serviceNameBuilder, url, Constants.VERSION_KEY);
        appendIfPresent(serviceNameBuilder, url, Constants.GROUP_KEY);
        return serviceNameBuilder.toString();
    }

    private void appendIfPresent(StringBuilder target, URL url, String parameterName) {
        String parameterValue = url.getParameter(parameterName);
        if (!StringUtils.isBlank(parameterValue)) {
            target.append("-").append(parameterValue);
        }
    }

    private void execute(NamingServiceCallback callback) {
        try {
            callback.callback(namingService);
        } catch (NacosException e) {
            if (logger.isErrorEnabled()) {
                logger.error(e.getErrMsg(), e);
            }
        }
    }

    private interface NamingServiceCallback {

        void callback(NamingService namingService) throws NacosException;

    }

    private static <T> T[] of(T... values) {
        return values;
    }
}

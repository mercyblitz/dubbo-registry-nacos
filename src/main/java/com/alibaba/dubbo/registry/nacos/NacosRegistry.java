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
import com.alibaba.nacos.api.naming.pojo.ListView;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
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

    /**
     * The pagination size of query for Nacos service names
     */
    private static final int PAGINATION_SIZE = Integer.getInteger("nacos.service.names.pagination.size", 100);

    /**
     * The separator for service name
     */
    private static final String SERVICE_NAME_SEPARATOR = ":";

    private static final String[] ALL_CATEGORIES = of(
            Constants.PROVIDERS_CATEGORY,
            Constants.CONSUMERS_CATEGORY,
            Constants.ROUTERS_CATEGORY,
            Constants.CONFIGURATORS_CATEGORY
    );

    private static final int CATEGORY_INDEX = 0;

    private static final int SERVICE_INTERFACE_INDEX = 1;

    private static final int SERVICE_VERSION_INDEX = 2;

    private static final int SERVICE_GROUP_INDEX = 3;

    private static final String WILDCARD = "*";

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

    protected void doRegister(URL url) {
        final String serviceName = getServiceName(url);
        final Instance instance = createInstance(url);
        execute(new NamingServiceCallback() {
            public void callback(NamingService namingService) throws NacosException {
                namingService.registerInstance(serviceName, instance);
            }
        });
    }

    protected void doUnregister(final URL url) {
        execute(new NamingServiceCallback() {
            public void callback(NamingService namingService) throws NacosException {
                String serviceName = getServiceName(url);
                Instance instance = createInstance(url);
                namingService.deregisterInstance(serviceName, instance.getIp(), instance.getPort());
            }
        });
    }

    protected void doSubscribe(final URL url, final NotifyListener listener) {
        execute(new NamingServiceCallback() {
            @Override
            public void callback(NamingService namingService) throws NacosException {
                List<String> serviceNames = getServiceNames(url);
                for (String serviceName : serviceNames) {
                    List<Instance> instances = namingService.getAllInstances(serviceName);
                    notifySubscriber(url, listener, instances);
                    subscribeEventListener(serviceName, url, listener);
                }
            }
        });
    }

    @Override
    protected void doUnsubscribe(URL url, NotifyListener listener) {
    }

    /**
     * Get the service names from the specified {@link URL url}
     *
     * @param url {@link URL}
     * @return non-null
     * @throws NacosException
     */
    private List<String> getServiceNames(URL url) throws NacosException {

        String protocol = url.getProtocol();

        if (Constants.ADMIN_PROTOCOL.equals(protocol)) {
            return getServiceNamesForOps(url);
        } else {
            return doGetServiceNames(url);
        }
    }

    /**
     * Get the service names for Dubbo OPS
     *
     * @param url {@link URL}
     * @return non-null
     * @throws NacosException
     */
    private List<String> getServiceNamesForOps(URL url) throws NacosException {
        int pageIndex = 1;
        ListView<String> listView = namingService.getServicesOfServer(pageIndex, PAGINATION_SIZE);
        // Append first page into list
        List<String> serviceNames = new LinkedList<String>(listView.getData());
        // the total count
        int count = listView.getCount();
        // the number of pages
        int pageNumbers = count / PAGINATION_SIZE;
        // If more than 1 page
        for (int i = 0; i < pageNumbers; i++) {
            listView = namingService.getServicesOfServer(++pageIndex, PAGINATION_SIZE);
            serviceNames.addAll(listView.getData());
        }

        // filter matched service names
        filterMatchedServiceNames(serviceNames, url);

        return serviceNames;
    }

    private void filterMatchedServiceNames(List<String> serviceNames, URL url) {

        String[] categories = getCategories(url);

        String targetServiceInterface = url.getServiceInterface();

        String targetVersion = url.getParameter(Constants.VERSION_KEY);

        String targetGroup = url.getParameter(Constants.GROUP_KEY);

        Iterator<String> iterator = serviceNames.iterator();

        while (iterator.hasNext()) {
            // service name -> providers:*:*:*
            String serviceName = iterator.next();
            // split service name to segments
            // (required) segments[0] = category
            // (required) segments[1] = serviceInterface
            // (required) segments[2] = version
            // (optional) segments[3] = group
            String[] segments = StringUtils.split(serviceName, SERVICE_NAME_SEPARATOR);
            int length = segments.length;
            if (length < 3) { // must present 3 segments or more
                continue;
            }

            String category = segments[CATEGORY_INDEX];
            if (!ArrayUtils.contains(categories, category)) { // no match category
                iterator.remove();
            }

            String serviceInterface = segments[SERVICE_INTERFACE_INDEX];
            if (!WILDCARD.equals(targetServiceInterface) &&
                    !StringUtils.equals(targetServiceInterface, serviceInterface)) { // no match service interface
                iterator.remove();
            }

            String version = segments[SERVICE_VERSION_INDEX];
            if (!WILDCARD.equals(targetVersion) &&
                    !StringUtils.equals(targetVersion, version)) { // no match service version
                iterator.remove();
            }

            String group = length > 3 ? segments[SERVICE_GROUP_INDEX] : null;
            if (group != null && !WILDCARD.equals(targetGroup)
                    && !StringUtils.equals(targetGroup, group)) {  // no match service group
                iterator.remove();
            }
        }
    }

    private List<String> doGetServiceNames(URL url) {
        String[] categories = getCategories(url);
        List<String> serviceNames = new ArrayList<String>(categories.length);
        for (String category : categories) {
            final String serviceName = getServiceName(url, category);
            serviceNames.add(serviceName);
        }
        return serviceNames;
    }

    private List<URL> buildURLs(URL consumerURL, Collection<Instance> instances) {
        if (instances.isEmpty()) {
            return Collections.emptyList();
        }
        List<URL> urls = new LinkedList<URL>();
        for (Instance instance : instances) {
            URL url = buildURL(instance);
            if (UrlUtils.isMatch(consumerURL, url)) {
                urls.add(url);
            }
        }
        return urls;
    }

    private void subscribeEventListener(String serviceName, final URL url, final NotifyListener listener)
            throws NacosException {
        if (!nacosListeners.containsKey(serviceName)) {
            EventListener eventListener = new EventListener() {
                public void onEvent(Event event) {
                    if (event instanceof NamingEvent) {
                        NamingEvent e = (NamingEvent) event;
                        notifySubscriber(url, listener, e.getInstances());
                    }
                }
            };
            namingService.subscribe(serviceName, eventListener);
            nacosListeners.put(serviceName, eventListener);
        }
    }

    /**
     * Notify the Healthy {@link Instance instances} to subscriber.
     *
     * @param url       {@link URL}
     * @param listener  {@link NotifyListener}
     * @param instances all {@link Instance instances}
     */
    private void notifySubscriber(URL url, NotifyListener listener, Collection<Instance> instances) {
        List<Instance> healthyInstances = filterHealthyInstances(instances);
        if (!healthyInstances.isEmpty()) {
            List<URL> providerURLs = buildURLs(url, instances);
            NacosRegistry.this.notify(url, listener, providerURLs);
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

    private URL buildURL(Instance instance) {
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
            target.append(SERVICE_NAME_SEPARATOR).append(parameterValue);
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

    private List<Instance> filterHealthyInstances(Collection<Instance> instances) {
        if (instances.isEmpty()) {
            return Collections.emptyList();
        }
        // Healthy Instances
        List<Instance> healthyInstances = new LinkedList<Instance>();
        for (Instance instance : instances) {
            if (instance.isEnabled()) {
                healthyInstances.add(instance);
            }
        }
        return healthyInstances;
    }


    private interface NamingServiceCallback {

        void callback(NamingService namingService) throws NacosException;

    }

    private static <T> T[] of(T... values) {
        return values;
    }
}

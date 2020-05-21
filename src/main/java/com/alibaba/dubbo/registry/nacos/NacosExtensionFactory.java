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

import org.apache.dubbo.common.extension.ExtensionFactory;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.lang.Prioritized;
import org.apache.dubbo.registry.RegistryFactory;

import static org.apache.dubbo.common.extension.ExtensionLoader.getExtensionLoader;

/**
 * Like a dummy implementation of Dubbo's {@link ExtensionFactory}, which will replace the "nacos" registry protocol
 * from Apache Dubbo, instead of getting any instance of extension.
 */
@Deprecated
public class NacosExtensionFactory implements ExtensionFactory, Prioritized {

    static {
        ExtensionLoader<RegistryFactory> extensionLoader = getExtensionLoader(RegistryFactory.class);
        extensionLoader.replaceExtension("nacos", NacosRegistryFactory.class);
    }

    @Override
    public <T> T getExtension(Class<T> type, String name) {
        return null;
    }
}

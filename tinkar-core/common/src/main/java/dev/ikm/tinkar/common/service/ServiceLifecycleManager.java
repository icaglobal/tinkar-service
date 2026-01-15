/*
 * Copyright © 2015 Integrated Knowledge Management (support@ikm.dev)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ikm.tinkar.common.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Manages services based on their lifecycle phases.
 * Provides lookup and lifecycle management for services.
 */
public class ServiceLifecycleManager {
    private static final Logger LOG = LoggerFactory.getLogger(ServiceLifecycleManager.class);
    private static final ConcurrentHashMap<Class<?>, Object> serviceCache = new ConcurrentHashMap<>();
    private static final ServiceLifecycleManager INSTANCE = new ServiceLifecycleManager();
    
    private ServiceLifecycleManager() {
        // Private constructor for singleton
    }
    
    /**
     * Gets the singleton instance of ServiceLifecycleManager.
     * 
     * @return The ServiceLifecycleManager instance
     */
    public static ServiceLifecycleManager get() {
        return INSTANCE;
    }
    
    /**
     * Gets a service of the specified type, optionally filtered by lifecycle phase.
     * 
     * @param serviceClass The service class to look up
     * @param phase The lifecycle phase (optional, can be null)
     * @param <T> The service type
     * @return Optional containing the service if found
     */
    @SuppressWarnings("unchecked")
    public static <T> Optional<T> get(Class<T> serviceClass, ServiceLifecyclePhase phase) {
        // Check cache first
        Object cached = serviceCache.get(serviceClass);
        if (cached != null && serviceClass.isInstance(cached)) {
            LOG.debug("Found cached service {} via ServiceLifecycleManager", serviceClass.getSimpleName());
            return Optional.of((T) cached);
        }
        
        // Try ServiceLoader first
        ServiceLoader<T> loader = PluggableService.load(serviceClass);
        Optional<T> service = loader.findFirst();
        if (service.isPresent()) {
            LOG.debug("Found service {} via ServiceLoader", serviceClass.getSimpleName());
            serviceCache.put(serviceClass, service.get());
            return service;
        }
        
        // Special handling for SearchService - create it directly using reflection
        if (serviceClass.getName().equals("dev.ikm.tinkar.provider.search.SearchService")) {
            try {
                File dataStoreRoot = ServiceProperties.get(ServiceKeys.DATA_STORE_ROOT, new File("target/data"));
                Path indexPath = Path.of(dataStoreRoot.getPath(), "lucene");
                // SearchService is now an interface, so instantiate SearchServiceImpl
                Class<?> implClass = Class.forName("dev.ikm.tinkar.provider.search.SearchServiceImpl");
                Constructor<?> constructor = implClass.getConstructor(Path.class);
                T searchService = (T) constructor.newInstance(indexPath);
                LOG.info("Created SearchService instance for path: {}", indexPath);
                serviceCache.put(serviceClass, searchService);
                return Optional.of(searchService);
            } catch (Exception e) {
                LOG.error("Failed to create SearchService", e);
                return Optional.empty();
            }
        }
        
        LOG.warn("No service found for class: {}", serviceClass.getName());
        return Optional.empty();
    }
    
    /**
     * Gets a service of the specified type.
     * 
     * @param serviceClass The service class to look up
     * @param <T> The service type
     * @return Optional containing the service if found
     */
    public static <T> Optional<T> get(Class<T> serviceClass) {
        return get(serviceClass, null);
    }
    
    /**
     * Gets a service of the specified type, or throws an exception if not found.
     * 
     * @param serviceClass The service class to look up
     * @param <T> The service type
     * @return The service instance
     * @throws IllegalStateException if the service is not found
     */
    public static <T> T getOrThrow(Class<T> serviceClass) {
        return get(serviceClass).orElseThrow(() -> 
            new IllegalStateException("Service not found: " + serviceClass.getName()));
    }
    
    /**
     * Gets a service of the specified type with a supplier for creating a default instance.
     * 
     * @param serviceClass The service class to look up
     * @param defaultSupplier Supplier for creating a default instance if service is not found
     * @param <T> The service type
     * @return The service instance or the default
     */
    public static <T> T getOrDefault(Class<T> serviceClass, Supplier<T> defaultSupplier) {
        return get(serviceClass).orElseGet(defaultSupplier);
    }
    
    /**
     * Gets a running service of the specified type (instance method).
     * This method is similar to get() but may filter for services that are in a running state.
     * 
     * @param serviceClass The service class to look up
     * @param <T> The service type
     * @return Optional containing the service if found and running
     */
    public <T> Optional<T> getRunningService(Class<T> serviceClass) {
        // For now, delegate to static get() - all services we return are considered "running"
        // This can be enhanced later to check service lifecycle state if needed
        return ServiceLifecycleManager.get(serviceClass);
    }
}

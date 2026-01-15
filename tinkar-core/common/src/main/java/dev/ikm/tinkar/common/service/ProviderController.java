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

import org.eclipse.collections.api.list.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Base abstract class for provider controllers that manage a single provider instance.
 * This provides a common pattern for lifecycle management of providers.
 *
 * @param <P> The provider type that this controller manages
 */
public abstract class ProviderController<P> implements DataServiceController<PrimitiveDataService> {
    private static final Logger LOG = LoggerFactory.getLogger(ProviderController.class);
    
    private final AtomicReference<P> providerReference = new AtomicReference<>();
    private final AtomicReference<LifecycleState> lifecycleState = new AtomicReference<>(LifecycleState.UNINITIALIZED);
    
    protected enum LifecycleState {
        UNINITIALIZED,
        INITIALIZING,
        RUNNING,
        STOPPING,
        STOPPED
    }
    
    /**
     * Creates the provider instance. Subclasses must implement this.
     */
    protected abstract P createProvider() throws Exception;
    
    /**
     * Starts the provider. Default implementation calls createProvider() and startProvider().
     * Subclasses can override for custom startup logic.
     */
    protected void startProvider(P provider) {
        // Default: provider should start itself during creation
    }
    
    /**
     * Stops the provider. Subclasses must implement this.
     */
    protected abstract void stopProvider(P provider);
    
    /**
     * Cleans up the provider after stopping. Default implementation does nothing.
     * Subclasses can override for custom cleanup logic.
     */
    protected void cleanupProvider(P provider) throws Exception {
        // Default: no cleanup needed
    }
    
    /**
     * Returns the provider name for logging purposes.
     */
    protected abstract String getProviderName();
    
    /**
     * Returns the lifecycle phase for this controller.
     * Default implementation returns DATA_STORAGE.
     */
    public ServiceLifecyclePhase getLifecyclePhase() {
        return ServiceLifecyclePhase.DATA_STORAGE;
    }
    
    /**
     * Returns the mutual exclusion group for this controller.
     * Default implementation returns DATA_PROVIDER group.
     */
    public java.util.Optional<ServiceExclusionGroup> getMutualExclusionGroup() {
        return java.util.Optional.of(ServiceExclusionGroup.DATA_PROVIDER);
    }
    
    /**
     * Gets the provider instance, creating it if necessary.
     */
    protected P getProvider() {
        return providerReference.updateAndGet(provider -> {
            if (provider != null) {
                return provider;
            }
            try {
                lifecycleState.set(LifecycleState.INITIALIZING);
                LOG.info("Creating {} provider", getProviderName());
                P newProvider = createProvider();
                startProvider(newProvider);
                lifecycleState.set(LifecycleState.RUNNING);
                LOG.info("{} provider created and started", getProviderName());
                return newProvider;
            } catch (Exception e) {
                lifecycleState.set(LifecycleState.STOPPED);
                LOG.error("Failed to create {} provider", getProviderName(), e);
                throw new RuntimeException("Failed to create " + getProviderName() + " provider", e);
            }
        });
    }
    
    /**
     * Starts the provider controller and initializes the provider.
     */
    protected void startup() {
        getProvider(); // This will create and start the provider if needed
    }
    
    /**
     * Stops the provider controller and cleans up the provider.
     */
    protected void shutdown() {
        P provider = providerReference.getAndSet(null);
        if (provider != null) {
            try {
                lifecycleState.set(LifecycleState.STOPPING);
                LOG.info("Stopping {} provider", getProviderName());
                stopProvider(provider);
                cleanupProvider(provider);
                lifecycleState.set(LifecycleState.STOPPED);
                LOG.info("{} provider stopped", getProviderName());
            } catch (Exception e) {
                LOG.error("Error stopping {} provider", getProviderName(), e);
                lifecycleState.set(LifecycleState.STOPPED);
            }
        }
    }
    
    // ========== DataServiceController Implementation ==========
    
    /**
     * Returns the service classes provided by this controller.
     * This is used by the service loader system.
     */
    public ImmutableList<Class<?>> serviceClasses() {
        return org.eclipse.collections.api.factory.Lists.immutable.of(PrimitiveDataService.class);
    }
    
    @Override
    public boolean running() {
        P provider = providerReference.get();
        return provider != null && lifecycleState.get() == LifecycleState.RUNNING;
    }
    
    @Override
    public void start() {
        startup();
    }
    
    @Override
    public void stop() {
        shutdown();
    }
    
    @Override
    public void save() {
        // Default implementation: save is handled by the controller, not the provider
        // Subclasses can override if needed
    }
    
    @Override
    public void reload() {
        throw new UnsupportedOperationException("Reload not yet supported");
    }
    
    @Override
    public PrimitiveDataService provider() {
        P provider = getProvider();
        if (provider instanceof PrimitiveDataService) {
            return (PrimitiveDataService) provider;
        }
        throw new IllegalStateException("Provider is not a PrimitiveDataService");
    }
    
    @Override
    public Class<? extends PrimitiveDataService> serviceClass() {
        return PrimitiveDataService.class;
    }
}

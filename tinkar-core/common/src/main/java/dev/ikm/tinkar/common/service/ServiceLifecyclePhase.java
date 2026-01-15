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

/**
 * Represents the lifecycle phase of a service.
 * Services are organized into phases that determine when they are initialized
 * and how they interact with other services.
 */
public enum ServiceLifecyclePhase {
    DATA_STORAGE,
    ENTITY_SERVICES,
    SEARCH_SERVICES,
    REASONER_SERVICES,
    APPLICATION_SERVICES
}

/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.tasks;

import org.gradle.api.internal.cache.StringInterner;
import org.gradle.api.internal.tasks.compile.incremental.cache.UserHomeScopedCompileCaches;
import org.gradle.cache.CacheRepository;
import org.gradle.cache.internal.InMemoryCacheDecoratorFactory;
import org.gradle.initialization.JdkToolsInitializer;
import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.service.scopes.AbstractPluginServiceRegistry;

public class CompileServices extends AbstractPluginServiceRegistry {

    @Override
    public void registerGradleServices(ServiceRegistration registration) {
        registration.addProvider(new GradleScopeCompileServices());
    }

    @Override
    public void registerGradleUserHomeServices(ServiceRegistration registration) {
        registration.addProvider(new UserHomeScopeServices());
    }

    private static class GradleScopeCompileServices {
        void configure(ServiceRegistration registration, JdkToolsInitializer initializer) {
            // Hackery
            initializer.initializeJdkTools();
        }
    }

    private static class UserHomeScopeServices {
        UserHomeScopedCompileCaches createCompileCaches(CacheRepository cacheRepository, InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory, StringInterner interner) {
            return new UserHomeScopedCompileCaches(cacheRepository, inMemoryCacheDecoratorFactory, interner);
        }
    }
}

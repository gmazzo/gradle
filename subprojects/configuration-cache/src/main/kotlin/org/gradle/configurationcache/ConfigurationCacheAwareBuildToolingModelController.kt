/*
 * Copyright 2021 the original author or authors.
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

package org.gradle.configurationcache

import org.gradle.api.internal.GradleInternal
import org.gradle.api.internal.project.ProjectState
import org.gradle.configurationcache.fingerprint.ConfigurationCacheFingerprintController
import org.gradle.internal.build.BuildToolingModelController
import org.gradle.tooling.provider.model.internal.ToolingModelScope
import java.util.function.Function


internal
class ConfigurationCacheAwareBuildToolingModelController(
    private val delegate: BuildToolingModelController,
    private val controller: ConfigurationCacheFingerprintController
) : BuildToolingModelController {
    override fun getConfiguredModel(): GradleInternal = delegate.configuredModel

    override fun locateBuilderForTarget(modelName: String, param: Boolean): ToolingModelScope {
        return wrap(delegate.locateBuilderForTarget(modelName, param))
    }

    override fun locateBuilderForTarget(target: ProjectState, modelName: String, param: Boolean): ToolingModelScope {
        return wrap(delegate.locateBuilderForTarget(target, modelName, param))
    }

    private
    fun wrap(scope: ToolingModelScope): ToolingModelScope {
        val target = scope.target
        return if (target == null) {
            scope
        } else {
            ProjectModelScope(scope, target, controller)
        }
    }

    private
    class ProjectModelScope(
        val delegate: ToolingModelScope,
        private val target: ProjectState,
        val controller: ConfigurationCacheFingerprintController
    ) : ToolingModelScope {
        override fun getTarget() = target

        override fun getModel(modelName: String, parameterFactory: Function<Class<*>, Any>?): Any {
            return controller.collectFingerprintForProject(target.identityPath) {
                delegate.getModel(modelName, parameterFactory)
            }
        }
    }
}

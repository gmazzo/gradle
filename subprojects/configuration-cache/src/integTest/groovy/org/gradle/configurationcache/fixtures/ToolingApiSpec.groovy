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

package org.gradle.configurationcache.fixtures

import groovy.transform.SelfType
import org.apache.tools.ant.util.TeeOutputStream
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.executer.OutputScrapingExecutionFailure
import org.gradle.integtests.fixtures.executer.OutputScrapingExecutionResult
import org.gradle.internal.Pair
import org.gradle.tooling.BuildAction
import org.gradle.tooling.BuildActionExecuter
import org.gradle.tooling.provider.model.ToolingModelBuilder
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry

import javax.inject.Inject

@SelfType(AbstractIntegrationSpec)
trait ToolingApiSpec {
    ToolingApiBackedGradleExecuter getToolingApiExecutor() {
        return (ToolingApiBackedGradleExecuter) getExecuter()
    }

    void withSomeToolingModelBuilderPluginInBuildSrc(String content = "") {
        withSomeToolingModelBuilderPluginInChildBuild("buildSrc", content)
    }

    void withSomeToolingModelBuilderPluginInChildBuild(String childBuildName, String content = "") {
        file("$childBuildName/build.gradle") << """
            plugins {
                id("groovy-gradle-plugin")
            }
            gradlePlugin {
                plugins {
                    test {
                        id = "my.plugin"
                        implementationClass = "my.MyPlugin"
                    }
                }
            }
        """
        file("$childBuildName/src/main/groovy/my/MyModel.groovy") << """
            package my

            class MyModel implements java.io.Serializable {
                private final String message
                MyModel(String message) { this.message = message }
                String getMessage() { message }
            }
        """.stripIndent()

        file("$childBuildName/src/main/groovy/my/MyModelBuilder.groovy") << """
            package my

            import ${ToolingModelBuilder.name}
            import ${Project.name}

            class MyModelBuilder implements ToolingModelBuilder {
                boolean canBuild(String modelName) {
                    return modelName == "${SomeToolingModel.class.name}"
                }
                Object buildAll(String modelName, Project project) {
                    println("creating model for \$project")
                    $content
                    return new MyModel("It works from project \${project.path}")
                }
            }
        """.stripIndent()

        file("$childBuildName/src/main/groovy/my/MyPlugin.groovy") << """
            package my

            import ${Project.name}
            import ${Plugin.name}
            import ${Inject.name}
            import ${ToolingModelBuilderRegistry.name}

            abstract class MyPlugin implements Plugin<Project> {
                void apply(Project project) {
                    registry.register(new my.MyModelBuilder())
                }

                @Inject
                abstract ToolingModelBuilderRegistry getRegistry()
            }
        """.stripIndent()
    }

    def <T> T fetchModel(Class<T> type = SomeToolingModel.class) {
        def output = new ByteArrayOutputStream()
        def error = new ByteArrayOutputStream()
        def args = executer.allArgs
        args.remove("--no-daemon")

        def model = null
        toolingApiExecutor.usingToolingConnection(testDirectory) { connection ->
            model = connection.model(type)
                .addJvmArguments(executer.jvmArgs)
                .withArguments(args)
                .setStandardOutput(new TeeOutputStream(output, System.out))
                .setStandardError(new TeeOutputStream(error, System.err))
                .get()
        }
        result = OutputScrapingExecutionResult.from(output.toString(), error.toString())
        return model
    }

    void fetchModelFails() {
        def output = new ByteArrayOutputStream()
        def error = new ByteArrayOutputStream()
        def args = executer.allArgs
        args.remove("--no-daemon")

        try {
            toolingApiExecutor.usingToolingConnection(testDirectory) { connection ->
                connection.model(SomeToolingModel)
                    .addJvmArguments(executer.jvmArgs)
                    .withArguments(args)
                    .setStandardOutput(new TeeOutputStream(output, System.out))
                    .setStandardError(new TeeOutputStream(error, System.err))
                    .get()
            }
        } catch (Throwable t) {
            failure = OutputScrapingExecutionFailure.from(output.toString(), error.toString())
            return
        }
        throw new IllegalStateException("Expected build to fail but it did not.")
    }

    def <T> T runBuildAction(BuildAction<T> buildAction) {
        def output = new ByteArrayOutputStream()
        def error = new ByteArrayOutputStream()
        def args = executer.allArgs
        args.remove("--no-daemon")

        def model = null
        toolingApiExecutor.usingToolingConnection(testDirectory) { connection ->
            model = connection.action(buildAction)
                .addJvmArguments(executer.jvmArgs)
                .withArguments(args)
                .setStandardOutput(new TeeOutputStream(output, System.out))
                .setStandardError(new TeeOutputStream(error, System.err))
                .run()
        }
        result = OutputScrapingExecutionResult.from(output.toString(), error.toString())
        return model
    }

    def <T, S> Pair<T, S> runPhasedBuildAction(BuildAction<T> projectsLoadedAction, BuildAction<S> modelAction, @DelegatesTo(BuildActionExecuter) Closure config = {}) {
        def output = new ByteArrayOutputStream()
        def error = new ByteArrayOutputStream()
        def args = executer.allArgs
        args.remove("--no-daemon")

        T projectsLoadedModel = null
        S buildModel = null
        toolingApiExecutor.usingToolingConnection(testDirectory) { connection ->
            def builder = connection.action()
                .projectsLoaded(projectsLoadedAction, { Object model ->
                    projectsLoadedModel = model
                })
                .buildFinished(modelAction, { Object model ->
                    buildModel = model
                })
                .build()
            config.delegate = builder
            config.call()
            builder
                .addJvmArguments(executer.jvmArgs)
                .withArguments(args)
                .setStandardOutput(new TeeOutputStream(output, System.out))
                .setStandardError(new TeeOutputStream(error, System.err))
                .run()
        }
        result = OutputScrapingExecutionResult.from(output.toString(), error.toString())
        return Pair.of(projectsLoadedModel, buildModel)
    }

    def runTestClasses(String... testClasses) {
        def output = new ByteArrayOutputStream()
        def error = new ByteArrayOutputStream()
        def args = executer.allArgs
        args.remove("--no-daemon")

        toolingApiExecutor.usingToolingConnection(testDirectory) { connection ->
            connection.newTestLauncher()
                .withJvmTestClasses(testClasses)
                .addJvmArguments(executer.jvmArgs)
                .withArguments(args)
                .setStandardOutput(new TeeOutputStream(output, System.out))
                .setStandardError(new TeeOutputStream(error, System.err))
                .run()
        }

        result = OutputScrapingExecutionResult.from(output.toString(), error.toString())
    }
}

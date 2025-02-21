/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.buildinit.plugins

import org.gradle.buildinit.plugins.fixtures.ScriptDslFixture
import spock.lang.Unroll

class ScalaApplicationInitIntegrationTest extends AbstractInitIntegrationSpec {

    public static final String SAMPLE_APP_CLASS = "some/thing/App.scala"
    public static final String SAMPLE_APP_TEST_CLASS = "some/thing/AppSuite.scala"

    @Override
    String subprojectName() { 'app' }

    @Unroll
    def "creates sample source if no source present with #scriptDsl build scripts"() {
        when:
        run('init', '--type', 'scala-application', '--dsl', scriptDsl.id)

        then:
        subprojectDir.file("src/main/scala").assertHasDescendants(SAMPLE_APP_CLASS)
        subprojectDir.file("src/test/scala").assertHasDescendants(SAMPLE_APP_TEST_CLASS)

        and:
        commonJvmFilesGenerated(scriptDsl)

        when:
        run("build")

        then:
        assertTestPassed("some.thing.AppSuite", "App has a greeting")

        when:
        run("run")

        then:
        outputContains("Hello, world!")

        where:
        scriptDsl << ScriptDslFixture.SCRIPT_DSLS
    }

    @Unroll
    def "creates build using test suites with #scriptDsl build scripts when using --incubating"() {
        when:
        run('init', '--type', 'scala-application', '--dsl', scriptDsl.id, '--incubating')

        then:
        subprojectDir.file("src/main/scala").assertHasDescendants(SAMPLE_APP_CLASS)
        subprojectDir.file("src/test/scala").assertHasDescendants(SAMPLE_APP_TEST_CLASS)

        and:
        commonJvmFilesGenerated(scriptDsl)
        dslFixtureFor(scriptDsl).assertHasTestSuite("test")

        when:
        run("build")

        then:
        assertTestPassed("some.thing.AppSuite", "App has a greeting")

        when:
        run("run")

        then:
        outputContains("Hello, world!")

        where:
        scriptDsl << ScriptDslFixture.SCRIPT_DSLS
    }

    @Unroll
    def "specifying JUnit4 is not supported with #scriptDsl build scripts"() {
        when:
        fails('init', '--type', 'scala-application', '--test-framework', 'junit-4', '--dsl', scriptDsl.id)

        then:
        failure.assertHasCause("""The requested test framework 'junit-4' is not supported for 'scala-application' build type. Supported frameworks:
  - 'scalatest'""")

        where:
        scriptDsl << ScriptDslFixture.SCRIPT_DSLS
    }

    @Unroll
    def "creates sample source with package and #scriptDsl build scripts"() {
        when:
        run('init', '--type', 'scala-application', '--package', 'my.app', '--dsl', scriptDsl.id)

        then:
        subprojectDir.file("src/main/scala").assertHasDescendants("my/app/App.scala")
        subprojectDir.file("src/test/scala").assertHasDescendants("my/app/AppSuite.scala")

        and:
        commonJvmFilesGenerated(scriptDsl)

        when:
        run("build")

        then:
        assertTestPassed("my.app.AppSuite", "App has a greeting")

        when:
        run("run")

        then:
        outputContains("Hello, world!")

        where:
        scriptDsl << ScriptDslFixture.SCRIPT_DSLS
    }

    @Unroll
    def "source generation is skipped when scala sources detected with #scriptDsl build scripts"() {
        setup:
        subprojectDir.file("src/main/scala/org/acme/SampleMain.scala") << """
        package org.acme;

        class SampleMain {
        }
"""
        subprojectDir.file("src/test/scala/org/acme/SampleMainSuite.scala") << """
                package org.acme;

                class SampleMainSuite {
                }
        """
        when:
        run('init', '--type', 'scala-application', '--dsl', scriptDsl.id)

        then:
        subprojectDir.file("src/main/scala").assertHasDescendants("org/acme/SampleMain.scala")
        subprojectDir.file("src/test/scala").assertHasDescendants("org/acme/SampleMainSuite.scala")
        dslFixtureFor(scriptDsl).assertGradleFilesGenerated()

        when:
        run("build")

        then:
        executed(":app:test")

        where:
        scriptDsl << ScriptDslFixture.SCRIPT_DSLS
    }
}

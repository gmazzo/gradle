/*
 * Copyright 2009 the original author or authors.
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
package org.gradle;

import static org.gradle.util.WrapUtil.toList;
import static org.gradle.util.WrapUtil.toMap;
import org.gradle.util.WrapUtil;
import org.gradle.util.GUtil;
import org.gradle.util.HelperUtil;
import org.gradle.util.Matchers;
import org.gradle.api.artifacts.ProjectDependenciesBuildInstruction;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.InvalidUserDataException;
import org.gradle.groovy.scripts.StrictScriptSource;
import org.gradle.execution.BuildExecuter;
import org.gradle.execution.BuiltInTasksBuildExecuter;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.equalTo;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;

/**
 * @author Hans Dockter
 */
public class DefaultCommandLine2StartParameterConverterTest {
    // This property has to be also set as system property gradle.home when running this test
    private final static String TEST_GRADLE_HOME = "roadToNowhere";

    private String previousGradleHome;
    private File expectedBuildFile = null;
    private File expectedGradleUserHome = new File(Main.DEFAULT_GRADLE_USER_HOME);
    private File expectedGradleImportsFile;
    private File expectedPluginPropertiesFile;
    private File expectedProjectDir;
    private List expectedTaskNames = toList();
    private ProjectDependenciesBuildInstruction expectedProjectDependenciesBuildInstruction = new ProjectDependenciesBuildInstruction(
            WrapUtil.<String>toList()
    );
    private Map expectedSystemProperties = new HashMap();
    private Map expectedProjectProperties = new HashMap();
    private CacheUsage expectedCacheUsage = CacheUsage.ON;
    private boolean expectedSearchUpwards = true;
    private boolean expectedDryRun = false;
    private boolean expectedShowHelp = false;
    private boolean expectedShowVersion = false;
    private StartParameter.ShowStacktrace expectedShowStackTrace = StartParameter.ShowStacktrace.INTERNAL_EXCEPTIONS;
    private String expectedEmbeddedScript = "somescript";
    private LogLevel expectedLogLevel = LogLevel.LIFECYCLE;
    private StartParameter actualStartParameter;

    @Before
    public void setUp() throws IOException {
        previousGradleHome = System.getProperty("gradle.home");
        System.setProperty("gradle.home", "roadToNowhere");

        expectedGradleImportsFile = new File(TEST_GRADLE_HOME, Main.IMPORTS_FILE_NAME).getCanonicalFile();
        expectedPluginPropertiesFile = new File(TEST_GRADLE_HOME, Main.DEFAULT_PLUGIN_PROPERTIES).getCanonicalFile();
        expectedProjectDir = new File("").getCanonicalFile();
    }

    @After
    public void tearDown() {
        if (previousGradleHome != null) {
            System.setProperty("gradle.home", previousGradleHome);
        } else {
            System.getProperties().remove("gradle.home");
        }
        Gradle.injectCustomFactory(null);
    }

    @Test
    public void withoutAnyOptions() {
        checkConversion();
    }

    private void checkConversion(String... args) {
        checkConversion(false, false, args);
    }

    private void checkStartParameter(StartParameter startParameter, boolean emptyTasks) {
        assertEquals(expectedBuildFile, startParameter.getBuildFile());
        assertEquals(emptyTasks ? new ArrayList() : expectedTaskNames, startParameter.getTaskNames());
        assertEquals(expectedProjectDependenciesBuildInstruction, startParameter.getProjectDependenciesBuildInstruction());
        assertEquals(expectedProjectDir.getAbsoluteFile(), startParameter.getCurrentDir().getAbsoluteFile());
        assertEquals(expectedCacheUsage, startParameter.getCacheUsage());
        assertEquals(expectedSearchUpwards, startParameter.isSearchUpwards());
        assertEquals(expectedProjectProperties, startParameter.getProjectProperties());
        assertEquals(expectedSystemProperties, startParameter.getSystemPropertiesArgs());
        assertEquals(expectedGradleUserHome.getAbsoluteFile(), startParameter.getGradleUserHomeDir().getAbsoluteFile());
        assertEquals(expectedGradleImportsFile, startParameter.getDefaultImportsFile());
        assertEquals(expectedPluginPropertiesFile, startParameter.getPluginPropertiesFile());
        assertEquals(expectedGradleUserHome.getAbsoluteFile(), startParameter.getGradleUserHomeDir().getAbsoluteFile());
        assertEquals(expectedLogLevel, startParameter.getLogLevel());
        assertEquals(expectedDryRun, startParameter.isDryRun());
        assertEquals(expectedShowHelp, startParameter.isShowHelp());
        assertEquals(expectedShowVersion, startParameter.isShowVersion());
        assertEquals(expectedShowStackTrace, startParameter.getShowStacktrace());
    }

    private void checkConversion(final boolean embedded, final boolean noTasks, String... args) {
        actualStartParameter = new DefaultCommandLine2StartParameterConverter().convert(args);
        // We check the params passed to the build factory
        checkStartParameter(actualStartParameter, noTasks);
        if (embedded) {
            assertThat(actualStartParameter.getBuildScriptSource().getText(), equalTo(expectedEmbeddedScript));
        } else {
            assert !GUtil.isTrue(actualStartParameter.getBuildScriptSource());
        }
    }

    @Test
    public void withSpecifiedGradleUserHomeDirectory() {
        expectedGradleUserHome = HelperUtil.makeNewTestDir();
        checkConversion("-g", expectedGradleUserHome.getAbsoluteFile().toString());
    }

    @Test
    public void withSpecifiedProjectDirectory() {
        expectedProjectDir = HelperUtil.makeNewTestDir();
        checkConversion("-p", expectedProjectDir.getAbsoluteFile().toString());
    }

    @Test
    public void withDisabledDefaultImports() {
        expectedGradleImportsFile = null;
        checkConversion("-I");
    }

    @Test
    public void withSpecifiedDefaultImportsFile() {
        expectedGradleImportsFile = new File("somename");
        checkConversion("-K", expectedGradleImportsFile.toString());
    }

    @Test
    public void withSpecifiedPluginPropertiesFile() {
        expectedPluginPropertiesFile = new File("somename");
        checkConversion("-l", expectedPluginPropertiesFile.toString());
    }

    @Test
    public void withSpecifiedBuildFileName() throws IOException {
        expectedBuildFile = new File("somename").getCanonicalFile();
        checkConversion("-b", "somename");
    }

    @Test
    public void withSpecifiedSettingsFileName() throws IOException {
        checkConversion("-c", "somesettings");

        assertThat(actualStartParameter.getSettingsScriptSource(), instanceOf(StrictScriptSource.class));
        assertThat(actualStartParameter.getSettingsScriptSource().getSourceFile(), equalTo(new File(
                "somesettings").getCanonicalFile()));
    }

    @Test
    public void withSystemProperties() {
        final String prop1 = "gradle.prop1";
        final String valueProp1 = "value1";
        final String prop2 = "gradle.prop2";
        final String valueProp2 = "value2";
        expectedSystemProperties = toMap(prop1, valueProp1);
        expectedSystemProperties.put(prop2, valueProp2);
        checkConversion("-D", prop1 + "=" + valueProp1, "-D", prop2 + "=" + valueProp2);
    }

    @Test
    public void withStartProperties() {
        final String prop1 = "prop1";
        final String valueProp1 = "value1";
        final String prop2 = "prop2";
        final String valueProp2 = "value2";
        expectedProjectProperties = toMap(prop1, valueProp1);
        expectedProjectProperties.put(prop2, valueProp2);
        checkConversion("-P", prop1 + "=" + valueProp1, "-P", prop2 + "=" + valueProp2);
    }

    @Test
    public void withTaskNames() {
        expectedTaskNames = toList("a", "b");
        checkConversion("a", "b");
    }

    @Test
    public void withCacheOffFlagSet() {
        expectedCacheUsage = CacheUsage.OFF;
        checkConversion("-C", "off");
    }

    @Test
    public void withRebuildCacheFlagSet() {
        expectedCacheUsage = CacheUsage.REBUILD;
        checkConversion("-C", "rebuild");
    }

    @Test
    public void withCacheOnFlagSet() {
        checkConversion("-C", "on");
    }

    @Test(expected = CommandLineArgumentException.class)
    public void withUnknownCacheFlags() {
        checkConversion("-C", "unknown");
    }

    @Test
    public void withSearchUpwardsFlagSet() {
        expectedSearchUpwards = false;
        checkConversion("-u");
    }

    @Test
    public void withDryRunFlagSet() {
        expectedDryRun = true;
        checkConversion("-m");
    }

    @Test
    public void withShowHelp() {
        expectedPluginPropertiesFile = null;
        expectedGradleImportsFile = null;
        expectedShowHelp = true;
        checkConversion("-h");
    }

    @Test
    public void withShowVersion() {
        expectedPluginPropertiesFile = null;
        expectedGradleImportsFile = null;
        expectedShowVersion = true;
        checkConversion("-v");
    }

    @Test
    public void withEmbeddedScript() {
        expectedSearchUpwards = false;
        checkConversion(true, false, "-e", expectedEmbeddedScript);
    }

    @Test(expected = CommandLineArgumentException.class)
    public void withEmbeddedScriptAndConflictingNoSearchUpwardsOption() {
        checkConversion("-e", "someScript", "-u", "clean");
    }

    @Test(expected = CommandLineArgumentException.class)
    public void withEmbeddedScriptAndConflictingSpecifyBuildFileOption() {
        checkConversion("-e", "someScript", "-bsomeFile", "clean");
    }

    @Test(expected = CommandLineArgumentException.class)
    public void withEmbeddedScriptAndConflictingSpecifySettingsFileOption() {
        checkConversion("-e", "someScript", "-csomeFile", "clean");
    }

    public void withConflictingLoggingOptionsDQ() {
        List<String> illegalOptions = toList("dq", "di", "qd", "qi", "iq", "id");
        for (String illegalOption : illegalOptions) {
            try {
                checkConversion("-" + illegalOption, "clean");
            } catch (InvalidUserDataException e) {
                continue;
            }
            fail("Expected InvalidUserDataException");
        }
    }

    @Test
    public void withQuietLoggingOptions() {
        expectedLogLevel = LogLevel.QUIET;
        checkConversion("-q");
    }

    @Test
    public void withNoProjectDependencyRebuild() {
        expectedProjectDependenciesBuildInstruction = new ProjectDependenciesBuildInstruction(null);
        checkConversion("-a");
    }

    @Test
    public void withProjectDependencyTaskNames() {
        expectedProjectDependenciesBuildInstruction = new ProjectDependenciesBuildInstruction(
                WrapUtil.toList("task1", "task2"));
        checkConversion("-Atask1", "-A task2");
    }

    @Test
    public void withInfoLoggingOptions() {
        expectedLogLevel = LogLevel.INFO;
        checkConversion("-i");
    }

    @Test
    public void withDebugLoggingOptions() {
        expectedLogLevel = LogLevel.DEBUG;
        checkConversion("-d");
    }

    @Test
    public void withShowTasks() {
        checkConversion(false, true, "-t");
        BuildExecuter expectedExecuter = new BuiltInTasksBuildExecuter(BuiltInTasksBuildExecuter.Options.TASKS);
        assertThat(actualStartParameter.getBuildExecuter(), Matchers.reflectionEquals(expectedExecuter));
    }

    @Test
    public void withShowTasksAndEmbeddedScript() {
        expectedSearchUpwards = false;
        checkConversion(true, true, "-e", expectedEmbeddedScript, "-t");
    }

    @Test
    public void withShowProperties() {
        checkConversion(false, true, "-r");
        BuildExecuter expectedExecuter = new BuiltInTasksBuildExecuter(BuiltInTasksBuildExecuter.Options.PROPERTIES);
        assertThat(actualStartParameter.getBuildExecuter(), Matchers.reflectionEquals(expectedExecuter));
    }

    @Test
    public void withShowDependencies() {
        checkConversion(false, true, "-n");
        BuildExecuter expectedExecuter = new BuiltInTasksBuildExecuter(BuiltInTasksBuildExecuter.Options.DEPENDENCIES);
        assertThat(actualStartParameter.getBuildExecuter(), Matchers.reflectionEquals(expectedExecuter));
    }

    @Test(expected = CommandLineArgumentException.class)
    public void withShowTasksPropertiesAndDependencies() {
        checkConversion("-r", "-t");
        checkConversion("-r", "-n");
        checkConversion("-r", "-n", "-t");
    }

    @Test(expected = CommandLineArgumentException.class)
    public void withLowerPParameterWithoutArgument() {
        checkConversion("-p");
    }

    @Test(expected = CommandLineArgumentException.class)
    public void withAParameterWithoutArgument() {
        checkConversion("-A");
    }

    @Test(expected = CommandLineArgumentException.class)
    public void withUpperAAndLowerAParameter() {
        checkConversion("-a -Atask1");
    }

    @Test(expected = CommandLineArgumentException.class)
    public void withMissingGradleHome() {
        System.getProperties().remove(Main.GRADLE_HOME_PROPERTY_KEY);
        checkConversion("clean");
    }
}

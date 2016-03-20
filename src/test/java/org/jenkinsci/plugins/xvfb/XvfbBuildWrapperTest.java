/**
 * Copyright © 2012, Zoran Regvart
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation are those
 * of the authors and should not be interpreted as representing official policies,
 * either expressed or implied, of the FreeBSD Project.
 */
package org.jenkinsci.plugins.xvfb;

import static com.google.common.collect.Iterables.filter;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.core.IsNot.not;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;

import hudson.FilePath;
import hudson.Launcher;
import hudson.Launcher.LocalLauncher;
import hudson.model.AbstractBuild;
import hudson.model.Computer;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Result;
import hudson.model.StreamBuildListener;
import hudson.model.queue.QueueTaskFuture;
import hudson.tasks.BuildWrapper.Environment;
import hudson.util.ArgumentListBuilder;
import hudson.util.ProcessTree;
import hudson.util.ProcessTree.OSProcess;

public class XvfbBuildWrapperTest extends BaseXvfbTest {

    @Rule
    public JenkinsRule system = new JenkinsRule();

    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();

    @Test
    public void byDefaultBuildnumbersShouldBeBasedOnExecutorNumber() throws Exception {
        system.jenkins.setNumExecutors(3);

        final Xvfb xvfb = new Xvfb();
        xvfb.setInstallationName("working");

        final FreeStyleProject project1 = createFreeStyleJob(system, "byDefaultBuildnumbersShouldBeBasedOnExecutorNumber1");
        setupXvfbOn(project1, xvfb);

        final FreeStyleProject project2 = createFreeStyleJob(system, "byDefaultBuildnumbersShouldBeBasedOnExecutorNumber2");
        setupXvfbOn(project2, xvfb);

        final FreeStyleProject project3 = createFreeStyleJob(system, "byDefaultBuildnumbersShouldBeBasedOnExecutorNumber3");
        setupXvfbOn(project3, xvfb);

        final QueueTaskFuture<FreeStyleBuild> build1Result = project1.scheduleBuild2(0);
        final QueueTaskFuture<FreeStyleBuild> build2Result = project2.scheduleBuild2(0);
        final QueueTaskFuture<FreeStyleBuild> build3Result = project3.scheduleBuild2(0);

        final FreeStyleBuild build1 = build1Result.get();
        final FreeStyleBuild build2 = build2Result.get();
        final FreeStyleBuild build3 = build3Result.get();

        final int display1 = build1.getAction(XvfbEnvironment.class).displayName;
        final int display2 = build2.getAction(XvfbEnvironment.class).displayName;
        final int display3 = build3.getAction(XvfbEnvironment.class).displayName;

        final List<Integer> displayNumbersUsed = Arrays.asList(display1, display2, display3);
        assertThat("By default display numbers should be based on executor number, they were: " + displayNumbersUsed, displayNumbersUsed, containsInAnyOrder(1, 2, 3));
    }

    @Test
    @Issue("JENKINS-26848")
    public void inParallelBuildsBuildnumbersShouldBeOffsettedByComputerNumber() throws Exception {
        system.createSlave("label1", null);

        final Xvfb xvfb = new Xvfb();
        xvfb.setInstallationName("working");
        xvfb.setParallelBuild(true);

        final FreeStyleProject project = createFreeStyleJob(system, "inParallelBuildsBuildnumbersShouldBeOffsettedByComputerNumber");
        setupXvfbOn(project, xvfb);
        project.setAssignedLabel(Label.get("label1"));

        final FreeStyleBuild build = system.buildAndAssertSuccess(project);

        assertThat("For parallel builds display name should be based on computer number 100 * computer index offset", build.getAction(XvfbEnvironment.class).displayName, is(101));
    }

    @Before
    public void setupXvfbInstallations() throws IOException {
        setupXvfbInstallations(system.jenkins, tempDir);
    }

    @Test
    public void shouldCreateCommandLineArguments() throws IOException {
        final Xvfb xvfb = new Xvfb();
        xvfb.setInstallationName("cmd");
        xvfb.setDisplayName(42);

        final XvfbInstallation installation = new XvfbInstallation("cmd", "/usr/local/cmd-xvfb", null);

        final File tempDirRoot = tempDir.getRoot();
        final ArgumentListBuilder arguments = xvfb.createCommandArguments(installation, new FilePath(tempDirRoot), 42);

        assertThat(arguments.toList(), contains("/usr/local/cmd-xvfb/Xvfb", ":42", "-screen", "0", Xvfb.DEFAULT_SCREEN, "-fbdir", tempDirRoot.getAbsolutePath()));
    }

    @Test
    @Issue("JENKINS-32039")
    public void shouldCreateCommandLineArgumentsWithoutScreenIfEmpty() throws IOException {
        final Xvfb xvfb = new Xvfb();
        xvfb.setInstallationName("cmd");
        xvfb.setDisplayName(42);
        xvfb.setScreen("");

        final XvfbInstallation installation = new XvfbInstallation("cmd", "/usr/local/cmd-xvfb", null);

        final File tempDirRoot = tempDir.getRoot();
        final ArgumentListBuilder arguments = xvfb.createCommandArguments(installation, new FilePath(tempDirRoot), 42);

        assertThat(arguments.toList(), contains("/usr/local/cmd-xvfb/Xvfb", ":42", "-fbdir", tempDirRoot.getAbsolutePath()));
    }

    @Test
    @Issue("JENKINS-32039")
    public void shouldCreateCommandLineArgumentsWithoutScreenIfNotGiven() throws IOException {
        final Xvfb xvfb = new Xvfb();
        xvfb.setInstallationName("cmd");
        xvfb.setDisplayName(42);
        xvfb.setScreen(null);

        final XvfbInstallation installation = new XvfbInstallation("cmd", "/usr/local/cmd-xvfb", null);

        final File tempDirRoot = tempDir.getRoot();
        final ArgumentListBuilder arguments = xvfb.createCommandArguments(installation, new FilePath(tempDirRoot), 42);

        assertThat(arguments.toList(), contains("/usr/local/cmd-xvfb/Xvfb", ":42", "-fbdir", tempDirRoot.getAbsolutePath()));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldFailIfInstallationIsNotFound() throws Exception {
        final Xvfb xvfb = new Xvfb();
        xvfb.setInstallationName("nonexistant");

        final FreeStyleProject project = createFreeStyleJob(system, "shouldFailIfInstallationIsNotFound");
        setupXvfbOn(project, xvfb);

        final QueueTaskFuture<FreeStyleBuild> buildResult = project.scheduleBuild2(0);

        final FreeStyleBuild build = buildResult.get();

        system.assertBuildStatus(Result.FAILURE, build);

        final List<String> logLines = build.getLog(10);

        assertThat("If no Xvfb installations defined for use log should note that", logLines, hasItems(containsString("No Xvfb installations defined, please define one in the configuration")));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldFailIfNoInstallationIsDefined() throws Exception {
        final Xvfb xvfb = new Xvfb();

        final FreeStyleProject project = createFreeStyleJob(system, "shouldFailIfNoInstallationIsDefined");
        setupXvfbOn(project, xvfb);

        final QueueTaskFuture<FreeStyleBuild> buildResult = project.scheduleBuild2(0);

        final FreeStyleBuild build = buildResult.get();

        system.assertBuildStatus(Result.FAILURE, build);

        final List<String> logLines = build.getLog(10);

        assertThat("If no Xvfb installations defined for use log should note that", logLines, hasItems(containsString("No Xvfb installations defined, please define one in the configuration")));
    }

    @SuppressWarnings("unchecked")
    @Test
    @Issue("JENKINS-18094")
    public void shouldFailIfXvfbFailsToStart() throws Exception {
        final Xvfb xvfb = new Xvfb();
        xvfb.setInstallationName("failing");
        xvfb.setTimeout(10);

        final FreeStyleProject project = createFreeStyleJob(system, "shouldFailIfXvfbFailsToStart");
        setupXvfbOn(project, xvfb);

        final QueueTaskFuture<FreeStyleBuild> buildResult = project.scheduleBuild2(0);

        final FreeStyleBuild build = buildResult.get();

        system.assertBuildStatus(Result.FAILURE, build);

        final List<String> logLines = build.getLog(10);

        assertThat("If Xvfb fails to start log should indicate that", logLines,
                hasItems(containsString("This Xvfb will fail"), containsString("Xvfb failed to start, consult the lines above for errors")));

    }

    @Test
    public void shouldHonourSpecifiedDisplayNameOffset() throws Exception {
        final Xvfb xvfb = new Xvfb();
        xvfb.setInstallationName("working");
        xvfb.setDisplayNameOffset(100);

        final FreeStyleBuild build = runFreestyleJobWith(system, xvfb);

        assertThat("DISPLAY environment variable should be larger than 100 as is specified by offset of 100", build.getAction(XvfbEnvironment.class).displayName, greaterThanOrEqualTo(100));
    }

    @After
    public void shouldNotLeakProcesses() throws Exception {
        final String testXvfbDir = tempDir.getRoot().getName();

        final Iterable<Integer> leaks = Iterables.transform(filter(ProcessTree.get(), new Predicate<OSProcess>() {
            @Override
            public boolean apply(final OSProcess process) {
                final List<String> arguments = process.getArguments();
                return Iterables.any(arguments, Predicates.containsPattern(testXvfbDir + "/\\w+/Xvfb"));
            }
        }), new Function<OSProcess, Integer>() {

            @Override
            public Integer apply(final OSProcess process) {
                return process.getPid();
            }
        });

        assertThat("Found leaked processes, PIDs: " + Joiner.on(',').join(leaks), leaks.iterator().hasNext(), is(false));
    }

    @Test
    @Issue("JENKINS-14483")
    public void shouldNotRunOnNonUnixNodes() throws IOException, InterruptedException {
        final Xvfb xvfb = new Xvfb();

        final LocalLauncher launcher = new Launcher.LocalLauncher(system.createTaskListener()) {
            @Override
            public boolean isUnix() {
                return false;
            }
        };

        final OutputStream out = new ByteArrayOutputStream();

        final FreeStyleProject project = system.createFreeStyleProject();

        @SuppressWarnings("rawtypes")
        final Environment environment = xvfb.setUp((AbstractBuild) new FreeStyleBuild(project), launcher, new StreamBuildListener(out));

        assertThat("Environment should not be setup", environment, not(instanceOf(XvfbEnvironment.class)));
    }

    @Test
    @Issue("JENKINS-23155")
    public void shouldNotRunOnUnLabeledNodes() throws Exception {
        final FreeStyleProject project = createFreeStyleJob(system, "shouldNotRunOnUnLabeledNodes");

        final Xvfb xvfb = new Xvfb();
        xvfb.setInstallationName("working");
        xvfb.setAssignedLabels("label1");

        setupXvfbOn(project, xvfb);

        final FreeStyleBuild build = system.buildAndAssertSuccess(project);
        assertThat("Xvfb should not be started if label is not matched", build.getActions(XvfbEnvironment.class), empty());
    }

    @SuppressWarnings("unchecked")
    @Test
    @Issue("JENKINS-13046")
    public void shouldPassOnAdditionalCommandLineArguments() throws Exception {
        final Xvfb xvfb = new Xvfb();
        xvfb.setInstallationName("working");
        xvfb.setDisplayName(42);
        xvfb.setAdditionalOptions("additional options");

        final FreeStyleBuild build = runFreestyleJobWith(system, xvfb);

        final List<String> logLines = build.getLog(10);

        assertThat("Additional command line options should be passed to Xvfb", logLines, hasItems(containsString("additional options")));
    }

    @Test
    public void shouldPickupXvfbAutoDeterminedDisplayNumber() throws Exception {
        final Xvfb xvfb = new Xvfb();
        xvfb.setInstallationName("auto");
        xvfb.setAutoDisplayName(true);

        final FreeStyleBuild build = runFreestyleJobWith(system, xvfb);

        assertThat("DISPLAY environment variable should be 42, as it was determined automatically by Xvfb", build.getAction(XvfbEnvironment.class).displayName, is(42));
    }

    @Test
    @Issue("JENKINS-23155")
    public void shouldRunOnLabeledNodes() throws Exception {
        final Computer computer = system.jenkins.createComputer();
        final Node node = computer.getNode();
        node.setLabelString("label1");

        final FreeStyleProject project = createFreeStyleJob(system, "shouldRunOnLabeledNodes");
        project.setAssignedNode(node);

        final Xvfb xvfb = new Xvfb();
        xvfb.setInstallationName("working");
        xvfb.setAssignedLabels("label1");

        setupXvfbOn(project, xvfb);

        final FreeStyleBuild build = system.buildAndAssertSuccess(project);
        assertThat("Xvfb should be started if label is matched", build.getActions(XvfbEnvironment.class), hasSize(1));
    }

    @Test
    @Issue("JENKINS-14483")
    public void shouldRunOnUnixNodes() throws Exception {
        final Xvfb xvfb = new Xvfb();
        xvfb.setInstallationName("working");

        final FreeStyleProject project = createFreeStyleJob(system, "shouldRunOnUnixNodes");
        setupXvfbOn(project, xvfb);

        final FreeStyleBuild build = system.buildAndAssertSuccess(project);

        final Launcher launcher = build.getBuiltOn().createLauncher(system.createTaskListener());

        assertThat("Launcher should be on Unix", launcher.isUnix(), is(true));

        assertThat("Environment should be setup", build.getActions(XvfbEnvironment.class), hasSize(1));
    }

    @Test
    public void shouldShutdownWithBuild() throws Exception {
        final Xvfb xvfb = new Xvfb();
        xvfb.setInstallationName("working");
        xvfb.setShutdownWithBuild(true);

        runFreestyleJobWith(system, xvfb);

    }

    @Test
    public void shouldUseSpecifiedDisplayName() throws Exception {
        final Xvfb xvfb = new Xvfb();
        xvfb.setInstallationName("working");
        xvfb.setDisplayName(42);

        final FreeStyleBuild build = runFreestyleJobWith(system, xvfb);

        assertThat("DISPLAY environment variable should be 42, as is specified by configuration", build.getAction(XvfbEnvironment.class).displayName, is(42));
    }
}

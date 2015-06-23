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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.core.IsNot.not;
import hudson.DescriptorExtensionList;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Launcher.LocalLauncher;
import hudson.model.FreeStyleBuild;
import hudson.model.Result;
import hudson.model.StreamBuildListener;
import hudson.model.AbstractBuild;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.queue.QueueTaskFuture;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapper.Environment;
import hudson.tools.ToolInstallation;
import hudson.util.ArgumentListBuilder;
import hudson.util.DescribableList;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

import com.google.common.io.ByteStreams;
import com.google.common.io.Closeables;

public class XvfbBuildWrapperTest {

	@Rule
	public JenkinsRule system = new JenkinsRule();

	@Rule
	public TemporaryFolder tempDir = new TemporaryFolder();

	@Test
	public void byDefaultBuildnumbersShouldBeBasedOnExecutorNumber() throws Exception {
		system.jenkins.setNumExecutors(3);

		final XvfbBuildWrapper xvfb = new XvfbBuildWrapper("working", null, null, null, 0, 0, null, null, null, null,
				false);

		final FreeStyleProject project1 = createFreeStyleJob("byDefaultBuildnumbersShouldBeBasedOnExecutorNumber1");
		setupXvfbBuildWrapperOn(project1, xvfb);

		final FreeStyleProject project2 = createFreeStyleJob("byDefaultBuildnumbersShouldBeBasedOnExecutorNumber2");
		setupXvfbBuildWrapperOn(project2, xvfb);

		final FreeStyleProject project3 = createFreeStyleJob("byDefaultBuildnumbersShouldBeBasedOnExecutorNumber3");
		setupXvfbBuildWrapperOn(project3, xvfb);

		final QueueTaskFuture<FreeStyleBuild> build1Result = project1.scheduleBuild2(0);
		final QueueTaskFuture<FreeStyleBuild> build2Result = project2.scheduleBuild2(0);
		final QueueTaskFuture<FreeStyleBuild> build3Result = project3.scheduleBuild2(0);

		final FreeStyleBuild build1 = build1Result.get();
		final FreeStyleBuild build2 = build2Result.get();
		final FreeStyleBuild build3 = build3Result.get();

		final Integer display1 = build1.getAction(XvfbEnvironment.class).getDisplayNameUsed();
		final Integer display2 = build2.getAction(XvfbEnvironment.class).getDisplayNameUsed();
		final Integer display3 = build3.getAction(XvfbEnvironment.class).getDisplayNameUsed();

		final List<Integer> displayNumbersUsed = Arrays.asList(display1, display2, display3);
		assertThat("By default display numbers should be based on executor number, they were: " + displayNumbersUsed,
				displayNumbersUsed, containsInAnyOrder(1, 2, 3));
	}

	private FreeStyleProject createFreeStyleJob(final String name) throws IOException {
		return system.jenkins.createProject(FreeStyleProject.class, name);
	}

	private XvfbInstallation createInstallation(final String nameAndPath) throws IOException, FileNotFoundException {
		final File xvfbDir = tempDir.newFolder(nameAndPath);
		final File xvfb = new File(xvfbDir, "Xvfb");

		InputStream xvfbFrom = null;
		FileOutputStream xvfbTo = null;
		try {
			xvfbFrom = XvfbBuildWrapperTest.class.getResourceAsStream("/" + nameAndPath + "/Xvfb");
			xvfbTo = new FileOutputStream(xvfb);
			ByteStreams.copy(xvfbFrom, xvfbTo);
		} finally {
			Closeables.closeQuietly(xvfbFrom);
			Closeables.closeQuietly(xvfbTo);
		}
		xvfb.setExecutable(true);

		return new XvfbInstallation(nameAndPath, xvfbDir.getAbsolutePath(), null);
	}

	@Test
	@Issue("JENKINS-26848")
	public void inParallelBuildsBuildnumbersShouldBeOffsettedByComputerNumber() throws Exception {
		system.createSlave("label1", null);

		final XvfbBuildWrapper xvfb = new XvfbBuildWrapper("working", null, null, null, 0, 0, null, null, null, null,
				true);

		final FreeStyleProject project = createFreeStyleJob("inParallelBuildsBuildnumbersShouldBeOffsettedByComputerNumber");
		setupXvfbBuildWrapperOn(project, xvfb);
		project.setAssignedLabel(Label.get("label1"));

		final FreeStyleBuild build = system.buildAndAssertSuccess(project);

		assertThat("For parallel builds display name should be based on computer number 100 * computer index offset",
				build.getAction(XvfbEnvironment.class).getDisplayNameUsed(), is(101));
	}

	private FreeStyleBuild runFreestyleJobWith(final XvfbBuildWrapper xvfb) throws IOException, Exception {
		final FreeStyleProject project = createFreeStyleJob("xvfbFreestyleJob");
		setupXvfbBuildWrapperOn(project, xvfb);

		return system.buildAndAssertSuccess(project);
	}

	private void setupXvfbBuildWrapperOn(final FreeStyleProject project, final XvfbBuildWrapper xvfb) {
		final DescribableList<BuildWrapper, Descriptor<BuildWrapper>> buildWrappers = project.getBuildWrappersList();

		buildWrappers.add(xvfb);
	}

	@Before
	public void setupXvfbInstallations() throws IOException {
		final XvfbInstallation.DescriptorImpl installations = new XvfbInstallation.DescriptorImpl();

		installations.setInstallations(createInstallation("working"), createInstallation("failing"),
				createInstallation("auto"));

		final DescriptorExtensionList<ToolInstallation, Descriptor<ToolInstallation>> toolInstallations = system.jenkins
				.getDescriptorList(ToolInstallation.class);
		toolInstallations.add(installations);
	}

	@SuppressWarnings("unchecked")
	@Test
	public void shouldAbortIfInstallationIsNotFound() throws Exception {
		final XvfbBuildWrapper xvfb = new XvfbBuildWrapper("nonexistant", null, null, null, 0, 0, null, null, null,
				null, false);

		final FreeStyleProject project = createFreeStyleJob("shouldAbortIfInstallationIsNotFound");
		setupXvfbBuildWrapperOn(project, xvfb);

		final QueueTaskFuture<FreeStyleBuild> buildResult = project.scheduleBuild2(0);

		final FreeStyleBuild build = buildResult.get();

		system.assertBuildStatus(Result.ABORTED, build);

		final List<String> logLines = build.getLog(10);

		assertThat("If no Xvfb installations defined for use log should note that", logLines,
				hasItems(containsString("No Xvfb installations defined, please define one in the configuration")));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void shouldAbortIfNoInstallationIsDefined() throws Exception {
		final XvfbBuildWrapper xvfb = new XvfbBuildWrapper(null, null, null, null, 0, 0, null, null, null, null, false);

		final FreeStyleProject project = createFreeStyleJob("shouldAbortIfNoInstallationIsDefined");
		setupXvfbBuildWrapperOn(project, xvfb);

		final QueueTaskFuture<FreeStyleBuild> buildResult = project.scheduleBuild2(0);

		final FreeStyleBuild build = buildResult.get();

		system.assertBuildStatus(Result.ABORTED, build);

		final List<String> logLines = build.getLog(10);

		assertThat("If no Xvfb installations defined for use log should note that", logLines,
				hasItems(containsString("No Xvfb installations defined, please define one in the configuration")));
	}

	@SuppressWarnings("unchecked")
	@Test
	@Issue("JENKINS-18094")
	public void shouldAbortIfXvfbFailsToStart() throws Exception {
		final XvfbBuildWrapper xvfb = new XvfbBuildWrapper("failing", null, null, null, 10, 0, null, null, null, null,
				false);

		final FreeStyleProject project = createFreeStyleJob("shouldAbortIfXvfbFailsToStart");
		setupXvfbBuildWrapperOn(project, xvfb);

		final QueueTaskFuture<FreeStyleBuild> buildResult = project.scheduleBuild2(0);

		final FreeStyleBuild build = buildResult.get();

		system.assertBuildStatus(Result.FAILURE, build);

		final List<String> logLines = build.getLog(10);

		assertThat(
				"If Xvfb fails to start log should indicate that",
				logLines,
				hasItems(containsString("This Xvfb will fail"),
						containsString("Xvfb failed to start, consult the lines above for errors")));

	}

	@Test
	public void shouldCreateCommandLineArguments() throws IOException {
		final XvfbBuildWrapper xvfb = new XvfbBuildWrapper("cmd", 42, null, null, 0, 0, null, null, null, null, false);

		final XvfbInstallation installation = new XvfbInstallation("cmd", "/usr/local/cmd-xvfb", null);

		final File tempDirRoot = tempDir.getRoot();
		final ArgumentListBuilder arguments = xvfb.createCommandArguments(installation, new FilePath(tempDirRoot), 42);

		assertThat(
				arguments.toList(),
				containsInAnyOrder("/usr/local/cmd-xvfb/Xvfb", ":42", "-screen", "0", "-fbdir",
						tempDirRoot.getAbsolutePath()));
	}

	@Test
	public void shouldHonourSpecifiedDisplayNameOffset() throws Exception {
		final XvfbBuildWrapper xvfb = new XvfbBuildWrapper("working", null, null, null, 0, 100, null, null, null, null,
				false);

		final FreeStyleBuild build = runFreestyleJobWith(xvfb);

		assertThat("DISPLAY environment variable should be larger than 100 as is specified by offset of 100", build
				.getAction(XvfbEnvironment.class).getDisplayNameUsed(), greaterThanOrEqualTo(100));
	}

	@SuppressWarnings("rawtypes")
	@Test
	@Issue("JENKINS-14483")
	public void shouldNotRunOnNonUnixNodes() throws IOException, InterruptedException {
		final XvfbBuildWrapper xvfb = new XvfbBuildWrapper("", null, null, null, 0, 0, null, null, null, null, false);

		final LocalLauncher launcher = new Launcher.LocalLauncher(system.createTaskListener()) {
			@Override
			public boolean isUnix() {
				return false;
			}
		};

		final OutputStream out = new ByteArrayOutputStream();
		final Environment environment = xvfb.setUp((AbstractBuild) null, launcher, new StreamBuildListener(out));

		assertThat("Environment should not be setup", environment, not(instanceOf(XvfbEnvironment.class)));
	}

	@Test
	@Issue("JENKINS-23155")
	public void shouldNotRunOnUnLabeledNodes() throws Exception {
		final FreeStyleProject project = createFreeStyleJob("shouldNotRunOnUnLabeledNodes");

		final XvfbBuildWrapper xvfb = new XvfbBuildWrapper("working", null, null, null, 0, 0, null, null, null,
				"label1", false);
		setupXvfbBuildWrapperOn(project, xvfb);

		final FreeStyleBuild build = system.buildAndAssertSuccess(project);
		assertThat("Xvfb should not be started if label is not matched", build.getActions(XvfbEnvironment.class),
				empty());
	}

	@SuppressWarnings("unchecked")
	@Test
	@Issue("JENKINS-13046")
	public void shouldPassOnAdditionalCommandLineArguments() throws Exception {
		final XvfbBuildWrapper xvfb = new XvfbBuildWrapper("working", 42, null, null, 0, 0, "additional options", null,
				null, null, false);

		final FreeStyleBuild build = runFreestyleJobWith(xvfb);

		final List<String> logLines = build.getLog(10);

		assertThat("Additional command line options should be passed to Xvfb", logLines,
				hasItems(containsString("additional options")));
	}

	@Test
	public void shouldPickupXvfbAutoDeterminedDisplayNumber() throws Exception {
		final XvfbBuildWrapper xvfb = new XvfbBuildWrapper("auto", null, null, null, 0, 0, null, null, true, null,
				false);

		final FreeStyleBuild build = runFreestyleJobWith(xvfb);

		assertThat("DISPLAY environment variable should be 42, as it was determined automatically by Xvfb", build
				.getAction(XvfbEnvironment.class).getDisplayNameUsed(), is(42));
	}

	@Test
	@Issue("JENKINS-23155")
	public void shouldRunOnLabeledNodes() throws Exception {
		final Computer computer = system.jenkins.createComputer();
		final Node node = computer.getNode();
		node.setLabelString("label1");

		final FreeStyleProject project = createFreeStyleJob("shouldRunOnLabeledNodes");
		project.setAssignedNode(node);

		final XvfbBuildWrapper xvfb = new XvfbBuildWrapper("working", null, null, null, 0, 0, null, null, null,
				"label1", false);
		setupXvfbBuildWrapperOn(project, xvfb);

		final FreeStyleBuild build = system.buildAndAssertSuccess(project);
		assertThat("Xvfb should be started if label is matched", build.getActions(XvfbEnvironment.class), hasSize(1));
	}

	@Test
	@Issue("JENKINS-14483")
	public void shouldRunOnUnixNodes() throws Exception {
		final XvfbBuildWrapper xvfb = new XvfbBuildWrapper("working", null, null, null, 0, 0, null, null, null, null,
				false);

		final FreeStyleProject project = createFreeStyleJob("shouldRunOnUnixNodes");
		setupXvfbBuildWrapperOn(project, xvfb);

		final FreeStyleBuild build = system.buildAndAssertSuccess(project);

		final Launcher launcher = build.getBuiltOn().createLauncher(system.createTaskListener());

		assertThat("Launcher should be on Unix", launcher.isUnix(), is(true));

		assertThat("Environment should be setup", build.getActions(XvfbEnvironment.class), hasSize(1));
	}

	@Test
	public void shouldUseSpecifiedDisplayName() throws Exception {
		final XvfbBuildWrapper xvfb = new XvfbBuildWrapper("working", 42, null, null, 0, 0, null, null, null, null,
				false);

		final FreeStyleBuild build = runFreestyleJobWith(xvfb);

		assertThat("DISPLAY environment variable should be 42, as is specified by configuration",
				build.getAction(XvfbEnvironment.class).getDisplayNameUsed(), is(42));
	}
}

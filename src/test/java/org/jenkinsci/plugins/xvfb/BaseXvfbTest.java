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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;

import com.google.common.io.ByteStreams;
import com.google.common.io.Closeables;

import hudson.DescriptorExtensionList;
import hudson.model.Descriptor;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.tasks.BuildWrapper;
import hudson.tools.ToolInstallation;
import hudson.util.DescribableList;
import jenkins.model.Jenkins;

public abstract class BaseXvfbTest {

    protected static FreeStyleProject createFreeStyleJob(final JenkinsRule system, final String name) throws IOException {
        return system.jenkins.createProject(FreeStyleProject.class, name);
    }

    protected static XvfbInstallation createInstallation(final String nameAndPath, final TemporaryFolder tempDir) throws IOException, FileNotFoundException {
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

    protected static FreeStyleBuild runFreestyleJobWith(final JenkinsRule system, final Xvfb xvfb) throws IOException, Exception {
        final FreeStyleProject project = createFreeStyleJob(system, "xvfbFreestyleJob");
        setupXvfbOn(project, xvfb);

        return system.buildAndAssertSuccess(project);
    }

    protected static void setupXvfbOn(final FreeStyleProject project, final Xvfb xvfb) {
        final DescribableList<BuildWrapper, Descriptor<BuildWrapper>> buildWrappers = project.getBuildWrappersList();

        buildWrappers.add(xvfb);
    }

    protected void setupXvfbInstallations(final Jenkins jenkins, final TemporaryFolder tempDir) throws IOException {
        final XvfbInstallation.DescriptorImpl installations = new XvfbInstallation.DescriptorImpl();

        installations.setInstallations(createInstallation("working", tempDir), createInstallation("failing", tempDir), createInstallation("auto", tempDir));

        final DescriptorExtensionList<ToolInstallation, Descriptor<ToolInstallation>> toolInstallations = jenkins.getDescriptorList(ToolInstallation.class);
        toolInstallations.add(installations);
    }
}

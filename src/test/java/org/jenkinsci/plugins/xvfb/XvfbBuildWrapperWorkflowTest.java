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

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.CoreWrapperStep;
import org.jenkinsci.plugins.workflow.steps.StepConfigTester;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RestartableJenkinsRule;

public class XvfbBuildWrapperWorkflowTest extends BaseXvfbTest {

    @Rule
    public RestartableJenkinsRule restartableSystem = new RestartableJenkinsRule();

    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();

    @Test
    public void configurationShouldRoundTrip() {
        restartableSystem.then(new RestartableJenkinsRule.Step() {

            @Override
            public void run(final JenkinsRule rule) throws Throwable {
                final Xvfb xvfb = new Xvfb();
                xvfb.setAssignedLabels("");
                xvfb.setAdditionalOptions("");

                final CoreWrapperStep wrapperStep = new CoreWrapperStep(xvfb);

                final CoreWrapperStep testerStep = new StepConfigTester(restartableSystem.j).configRoundTrip(wrapperStep);

                restartableSystem.j.assertEqualDataBoundBeans(xvfb, testerStep.getDelegate());
            }
        });
    }

    @Test
    public void shouldAllowWorkflowRestarts() {
        restartableSystem.then(new RestartableJenkinsRule.Step() {

            @Override
            public void run(final JenkinsRule rule) throws Throwable {
                setupXvfbInstallations(restartableSystem.j.jenkins, tempDir);

                final WorkflowJob workflowJob = restartableSystem.j.jenkins.createProject(WorkflowJob.class, "shouldAllowWorkflowRestarts");

                workflowJob.setDefinition(new CpsFlowDefinition(""//
                        + "node {\n"//
                        + "  wrap([$class: 'Xvfb', installationName: 'working']) {\n"//
                        + "    semaphore 'shouldAllowWorkflowRestarts'\n"//
                        + "    sh 'echo DISPLAY=$DISPLAY'\n"//
                        + "  }\n"//
                        + "}", true));

                final WorkflowRun workflowRun = workflowJob.scheduleBuild2(0).waitForStart();

                SemaphoreStep.waitForStart("shouldAllowWorkflowRestarts/1", workflowRun);
            }

        });

        restartableSystem.then(new RestartableJenkinsRule.Step() {

            @Override
            public void run(final JenkinsRule rule) throws Throwable {
                SemaphoreStep.success("shouldAllowWorkflowRestarts/1", null);

                final WorkflowJob workflowProject = restartableSystem.j.jenkins.getItemByFullName("shouldAllowWorkflowRestarts", WorkflowJob.class);

                final WorkflowRun workflowRun = workflowProject.getBuildByNumber(1);

                restartableSystem.j.assertBuildStatusSuccess(restartableSystem.j.waitForCompletion(workflowRun));

                restartableSystem.j.assertLogContains("DISPLAY=:", workflowRun);
            }

        });
    }

    @Test
    public void shouldSupportWorkflow() {
        restartableSystem.then(new RestartableJenkinsRule.Step() {

            @Override
            public void run(final JenkinsRule rule) throws Throwable {
                setupXvfbInstallations(restartableSystem.j.jenkins, tempDir);

                final WorkflowJob workflowJob = restartableSystem.j.jenkins.createProject(WorkflowJob.class, "shouldSupportWorkflow");

                workflowJob.setDefinition(new CpsFlowDefinition(""//
                        + "node {\n"//
                        + "  wrap([$class: 'Xvfb', installationName: 'working']) {\n"//
                        + "    sh 'echo DISPLAY=$DISPLAY'\n"//
                        + "  }\n"//
                        + "}", true));

                final WorkflowRun workflowRun = workflowJob.scheduleBuild2(0).waitForStart();

                restartableSystem.j.assertBuildStatusSuccess(restartableSystem.j.waitForCompletion(workflowRun));

                restartableSystem.j.assertLogContains("DISPLAY=:", workflowRun);
            }

        });
    }
}

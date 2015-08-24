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

import hudson.CopyOnWrite;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Launcher.ProcStarter;
import hudson.Proc;
import hudson.Util;
import hudson.XmlFile;
import hudson.model.AutoCompletionCandidates;
import hudson.model.TaskListener;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.Run.RunnerAbortedException;
import hudson.model.labels.LabelAtom;
import hudson.model.listeners.RunListener;
import hudson.remoting.Channel;
import hudson.remoting.ChannelClosedException;
import hudson.slaves.ComputerListener;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.tools.ToolInstallation;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import hudson.util.ProcessTree;
import hudson.util.ProcessTree.OSProcess;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import jenkins.model.Jenkins;
import jenkins.security.MasterToSlaveCallable;
import jenkins.tasks.SimpleBuildWrapper;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import antlr.ANTLRException;

public class XvfbBuildWrapper extends SimpleBuildWrapper {

    private static final String JENKINS_XVFB_COOKIE = "_JENKINS_XVFB_COOKIE";

    @Extension(ordinal = Double.MAX_VALUE)
    public static class XvfbBuildWrapperDescriptor extends BuildWrapperDescriptor {

        /** Xvfb installations, this descriptor persists all installations configured. */
        @CopyOnWrite
        private volatile XvfbInstallation[] installations = new XvfbInstallation[0];

        public XvfbBuildWrapperDescriptor() {
            load();
        }

        public FormValidation doCheckDisplayName(@QueryParameter final String value) throws IOException {
            return validateOptionalNonNegativeInteger(value);
        }

        public FormValidation doCheckDisplayNameOffset(@QueryParameter final String value) throws IOException {
            return validateOptionalPositiveInteger(value);
        }

        public FormValidation doCheckTimeout(@QueryParameter final String value) throws IOException {
            return validateOptionalNonNegativeInteger(value);
        }

        @Override
        public String getDisplayName() {
            return Messages.XvfbBuildWrapper_DisplayName();
        }

        public XvfbInstallation[] getInstallations() {
            return installations;
        }

        public XvfbInstallation.DescriptorImpl getToolDescriptor() {
            return ToolInstallation.all().get(XvfbInstallation.DescriptorImpl.class);
        }

        @Override
        public boolean isApplicable(final AbstractProject<?, ?> item) {
            return true;
        }

        @Override
        public BuildWrapper newInstance(final StaplerRequest req, final JSONObject formData) throws hudson.model.Descriptor.FormException {
            return req.bindJSON(XvfbBuildWrapper.class, formData);
        }

        public void setInstallations(final XvfbInstallation... installations) {
            this.installations = installations;
            save();
        }

        private FormValidation validateOptionalNonNegativeInteger(final String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.ok();
            }

            return FormValidation.validateNonNegativeInteger(value);
        }

        private FormValidation validateOptionalPositiveInteger(final String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.ok();
            }

            return FormValidation.validatePositiveInteger(value);
        }

        /** adopted from @see hudson.model.AbstractProject.AbstractProjectDescriptor#doAutoCompleteAssignedLabels */
        public AutoCompletionCandidates doAutoCompleteAssignedLabels(@AncestorInPath AbstractProject<?, ?> project, @QueryParameter String value) {
            final AutoCompletionCandidates candidates = new AutoCompletionCandidates();
            final Set<Label> labels = Jenkins.getInstance().getLabels();

            for (Label label : labels) {
                if (value == null || label.getName().startsWith(value)) {
                    candidates.add(label.getName());
                }
            }

            return candidates;
        }

        /** adopted from @see hudson.model.AbstractProject.AbstractProjectDescriptor#doCheckAssignedLabels */
        public FormValidation doCheckAssignedLabels(@AncestorInPath AbstractProject<?, ?> project, @QueryParameter String value) {
            if (Util.fixEmpty(value) == null) {
                return FormValidation.ok();
            }

            try {
                Label.parseExpression(value);
            } catch (ANTLRException e) {
                return FormValidation.error(e, Messages.XvfbBuildWrapper_AssignedLabelString_InvalidBooleanExpression(e.getMessage()));
            }

            final Jenkins jenkins = Jenkins.getInstance();
            final Label label = jenkins.getLabel(value);

            if (label.isEmpty()) {
                for (LabelAtom labelAtom : label.listAtoms()) {
                    if (labelAtom.isEmpty()) {
                        LabelAtom nearest = LabelAtom.findNearest(labelAtom.getName());
                        return FormValidation.warning(Messages.XvfbBuildWrapper_AssignedLabelString_NoMatch_DidYouMean(labelAtom.getName(), nearest.getDisplayName()));
                    }
                }
                return FormValidation.warning(Messages.XvfbBuildWrapper_AssignedLabelString_NoMatch());
            }

            return FormValidation.okWithMarkup(Messages.XvfbBuildWrapper_LabelLink(jenkins.getRootUrl(), label.getUrl(), label.getNodes().size() + label.getClouds().size()));
        }
    }

    private static final String                             STDERR_FD            = "2";

    @SuppressWarnings("rawtypes")
    @Extension
    public static final RunListener<Run>                    xvfbShutdownListener = new RunListener<Run>() {
                                                                                     @Override
                                                                                     public void onCompleted(final Run r, final TaskListener listener) {
                                                                                         //			final XvfbEnvironment xvfbEnvironment = r.getAction(XvfbEnvironment.class);
                                                                                         //
                                                                                         //			if (xvfbEnvironment != null && xvfbEnvironment.isShutdownWithBuild()) {
                                                                                         //				try {
                                                                                         //					shutdownAndCleanup(xvfbEnvironment, listener);
                                                                                         //				} catch (final IOException e) {
                                                                                         //					throw new RuntimeException(e);
                                                                                         //				} catch (final InterruptedException e) {
                                                                                         //					throw new RuntimeException(e);
                                                                                         //				}
                                                                                         //			}
                                                                                     }
                                                                                 };

    private static final Map<String, List<XvfbEnvironment>> zombies              = createOrLoadZombiesMap();

    private static ConcurrentHashMap<String, List<XvfbEnvironment>> createOrLoadZombiesMap() {
        Jenkins.XSTREAM.registerConverter(new XvfbEnvironmentConverter());

        final XmlFile fileOfZombies = zombiesFile();

        if (fileOfZombies.exists()) {
            try {
                @SuppressWarnings("unchecked")
                final ConcurrentHashMap<String, List<XvfbEnvironment>> oldZombies = (ConcurrentHashMap<String, List<XvfbEnvironment>>) fileOfZombies.read();

                return oldZombies;
            } catch (IOException ignore) {
            } finally {
                fileOfZombies.delete();
            }
        }

        return new ConcurrentHashMap<String, List<XvfbEnvironment>>();
    }

    @Extension
    public static final ComputerListener nodeListener = new ComputerListener() {
                                                          @Override
                                                          public void preOnline(final Computer c, final Channel channel, final FilePath root, final TaskListener listener) throws IOException,
                                                                  InterruptedException {
                                                              final List<XvfbEnvironment> zombiesAtComputer = zombies.get(c.getName());

                                                              if (zombiesAtComputer == null) {
                                                                  return;
                                                              }

                                                              final List<XvfbEnvironment> slained = new ArrayList<XvfbEnvironment>();
                                                              for (final XvfbEnvironment zombie : zombiesAtComputer) {
                                                                  shutdownAndCleanupZombie(channel, zombie, listener);

                                                                  slained.add(zombie);
                                                              }

                                                              zombiesAtComputer.removeAll(slained);
                                                          }

                                                      };

    private static final class ComputerNameComparator implements Comparator<Computer> {

        private static final ComputerNameComparator INSTANCE = new ComputerNameComparator();

        public int compare(Computer left, Computer right) {
            return left.getName().compareTo(right.getName());
        }

    }

    private static final int MILLIS_IN_SECOND = 1000;

    /** default screen configuration for Xvfb, used by default, and if user left screen configuration blank */
    static final String      DEFAULT_SCREEN   = "1024x768x24";

    static void shutdownAndCleanup(final XvfbEnvironment xvfbEnvironment, final Launcher launcher, final TaskListener listener) throws IOException, InterruptedException {

        listener.getLogger().println(Messages.XvfbBuildWrapper_Stopping());

        try {
            launcher.kill(Collections.singletonMap(JENKINS_XVFB_COOKIE, xvfbEnvironment.cookie));
            final FilePath frameBufferPath = new FilePath(launcher.getChannel(), xvfbEnvironment.frameBufferDir);
            frameBufferPath.deleteRecursive();
        } catch (final ChannelClosedException e) {
            synchronized (zombies) {
                final Computer currentComputer = Computer.currentComputer();
                final String computerName = currentComputer.getName();

                List<XvfbEnvironment> zombiesAtComputer = zombies.get(computerName);

                if (zombiesAtComputer == null) {
                    zombiesAtComputer = new CopyOnWriteArrayList<XvfbEnvironment>();
                    zombies.put(computerName, zombiesAtComputer);
                }

                zombiesAtComputer.add(new XvfbEnvironment(xvfbEnvironment.cookie, xvfbEnvironment.frameBufferDir, xvfbEnvironment.displayName, false));

                final XmlFile fileOfZombies = zombiesFile();
                fileOfZombies.write(zombies);
            }
        }
    }

    private static XmlFile zombiesFile() {
        return new XmlFile(Jenkins.XSTREAM, new File(Jenkins.getInstance().getRootDir(), XvfbEnvironment.class.getName() + "-zombies.xml"));
    }

    private static void shutdownAndCleanupZombie(final Channel channel, final XvfbEnvironment zombie, final TaskListener listener) throws IOException, InterruptedException {

        listener.getLogger().println(Messages.XvfbBuildWrapper_KillingZombies(zombie.displayName, zombie.frameBufferDir));

        try {
            channel.call(new MasterToSlaveCallable<Void, InterruptedException>() {
                private static final long serialVersionUID = 1L;

                public Void call() throws InterruptedException {
                    final ProcessTree processTree = ProcessTree.get();

                    for (final Iterator<OSProcess> i = processTree.iterator(); i.hasNext();) {
                        final OSProcess osProcess = i.next();

                        final EnvVars environment = osProcess.getEnvironmentVariables();

                        final String processCookie = environment.get(JENKINS_XVFB_COOKIE);

                        if (processCookie != null && processCookie.equals(zombie.cookie)) {
                            osProcess.kill();
                            new File(zombie.frameBufferDir).delete();
                        }
                    }

                    return null;
                }
            });
        } catch (final InterruptedException e) {
            // if we propagate the exception, slave will be obstructed from going online
            listener.getLogger().println(Messages.XvfbBuildWrapper_ZombieSlainFailed());
            e.printStackTrace(listener.getLogger());
        }
    };

    /** Name of the installation used in a configured job. */
    private String  installationName;

    /** X11 DISPLAY name, if NULL chosen based on current executor number. */
    private Integer displayName;

    /** Xvfb screen argument, in the form WxHxD (width x height x pixel depth), i.e. 800x600x8. */
    private String  screen            = DEFAULT_SCREEN;

    /** Should the Xvfb output be displayed in job output. */
    private boolean debug             = false;

    /** Time in milliseconds to wait for Xvfb initialization, by default 0 -- do not wait. */
    private long    timeout;

    /** Offset for display names, default is 1. Display names are taken from build executor's number, i.e. if the build is performed by executor 4, and offset is 100, display name will be 104. */
    private int     displayNameOffset = 1;

    /** Additional options to be passed to Xvfb */
    private String  additionalOptions;

    /** Should the Xvfb display be around for post build actions, i.e. should it terminate with the whole build */
    private boolean shutdownWithBuild = false;

    /** Let Xvfb pick display number */
    private boolean autoDisplayName   = false;
    
    /** If an old Xvfb process is found, kill it */
    private boolean killExistingInstance = false;

    /** Run only on nodes labeled */
    private String  assignedLabels;

    /** Run on same node in parallel */
    private boolean parallelBuild     = false;

    @DataBoundConstructor
    public XvfbBuildWrapper() {
    }

    public String getAdditionalOptions() {
        return additionalOptions;
    }

    public String getAssignedLabels() {
        return assignedLabels;
    }

    @Override
    public XvfbBuildWrapperDescriptor getDescriptor() {
        return (XvfbBuildWrapperDescriptor) super.getDescriptor();
    }

    public Integer getDisplayName() {
        return displayName;
    }

    public int getDisplayNameOffset() {
        return displayNameOffset;
    }

    public XvfbInstallation getInstallation(final EnvVars env, final Node node, final TaskListener listener) {
        for (final XvfbInstallation installation : getDescriptor().getInstallations()) {
            if (installationName != null && installationName.equals(installation.getName())) {
                try {
                    return installation.forEnvironment(env).forNode(node, TaskListener.NULL);
                } catch (final IOException e) {
                    listener.fatalError("IOException while locating installation", e);
                } catch (final InterruptedException e) {
                    listener.fatalError("InterruptedException while locating installation", e);
                }
            }
        }

        return null;
    }

    public String getInstallationName() {
        return installationName;
    }

    public String getScreen() {
        return screen;
    }

    public long getTimeout() {
        return timeout;
    }

    public boolean isAutoDisplayName() {
        return autoDisplayName;
    }
    
    public boolean iskillExistingInstance() {
        return killExistingInstance;
    }

    public boolean isDebug() {
        return debug;
    }

    public boolean isParallelBuild() {
        return parallelBuild;
    }

    public boolean isShutdownWithBuild() {
        return shutdownWithBuild;
    }

    private XvfbEnvironment launchXvfb(final Run<?, ?> run, final FilePath workspace, final Launcher launcher, final TaskListener listener) throws IOException, InterruptedException {
        final Computer currentComputer = workspace.toComputer();

        int displayNameUsed;

        if (displayName == null) {
            if (!autoDisplayName) {
                final Executor executor = run.getExecutor();
                if (parallelBuild) {
                    final Computer[] computers = Jenkins.getInstance().getComputers();
                    final int nodeIndex = Arrays.binarySearch(computers, currentComputer, ComputerNameComparator.INSTANCE);

                    displayNameUsed = nodeIndex * 100 + executor.getNumber() + displayNameOffset;
                }
                else {
                    displayNameUsed = executor.getNumber() + displayNameOffset;
                }
            }
            else {
                displayNameUsed = -1;
            }
        }
        else {
            displayNameUsed = displayName;
        }

        final Node currentNode = currentComputer.getNode();
        final FilePath rootPath = currentNode.getRootPath();

        final FilePath frameBufferDir = rootPath.createTempDir("xvfb-" + run.getId() + "-", ".fbdir");

        final EnvVars environment = currentComputer.getEnvironment();
        final XvfbInstallation installation = getInstallation(environment, currentNode, listener);

        if (installation == null) {
            listener.error(Messages.XvfbBuildWrapper_NoInstallationsConfigured());

            throw new InterruptedException();
        }

        final ArgumentListBuilder cmd = createCommandArguments(installation, frameBufferDir, displayNameUsed);

        if (killExistingInstance) {
            // Use ps to get Xvfb processes
            try {
                String line;
                // pgrep found on every *NIX we could care about.
                String pgrep = "pgrep -f " + cmd.toCommandArray()[0] + ".*:" + displayNameUsed;
                Process p = Runtime.getRuntime().exec(pgrep);
                BufferedReader input =
                    new BufferedReader(new InputStreamReader(p.getInputStream()));
                while ((line = input.readLine()) != null) {
                    listener.getLogger().print(Messages.XvfbBuildWrapper_KillOldProcess(displayNameUsed, line));
                    Process p2 = Runtime.getRuntime().exec("kill " + line);
                }
                input.close();
            } catch (Exception err) {
                err.printStackTrace();
            }
        }
        
        final ProcStarter procStarter = launcher.launch().cmds(cmd);

        final ByteArrayOutputStream stdoutStream = new ByteArrayOutputStream();
        final OutputStream stdout = debug ? listener.getLogger() : stdoutStream;

        final ByteArrayOutputStream stderrStream = new ByteArrayOutputStream();
        final AutoDisplayNameFilterStream stderr = new AutoDisplayNameFilterStream(debug ? listener.getLogger() : stderrStream);

        listener.getLogger().print(Messages.XvfbBuildWrapper_Starting());
        procStarter.stdout(stdout).stderr(stderr);

        final String cookie = UUID.randomUUID().toString();
        procStarter.envs(Collections.singletonMap(JENKINS_XVFB_COOKIE, cookie));

        final Proc process = procStarter.start();

        Thread.sleep(timeout * MILLIS_IN_SECOND);

        if (!process.isAlive()) {
            if (!debug) {
                listener.getLogger().write(stdoutStream.toByteArray());
                listener.getLogger().write(stderrStream.toByteArray());
            }

            listener.getLogger().println();

            listener.error(Messages.XvfbBuildWrapper_FailedToStart());

            throw new RunnerAbortedException();
        }

        if (autoDisplayName) {
            displayNameUsed = stderr.getDisplayNumber();
        }

        final XvfbEnvironment xvfbEnvironment = new XvfbEnvironment(cookie, frameBufferDir.getRemote(), displayNameUsed, shutdownWithBuild);

        return xvfbEnvironment;
    }

    protected ArgumentListBuilder createCommandArguments(final XvfbInstallation installation, final FilePath frameBufferDir, int displayNameUsed) {
        final String path = installation.getHome();

        final ArgumentListBuilder cmd;
        if ("".equals(path)) {
            cmd = new ArgumentListBuilder("Xvfb");
        }
        else {
            cmd = new ArgumentListBuilder(path + "/Xvfb");
        }

        if (autoDisplayName) {
            cmd.add("-displayfd", STDERR_FD);
        }
        else {
            cmd.add(":" + displayNameUsed);
        }

        cmd.add("-screen").add("0").add(screen).add("-fbdir").add(frameBufferDir);

        if (additionalOptions != null) {
            cmd.addTokenized(additionalOptions);
        }
        return cmd;
    }

    @Override
    public void setUp(Context context, Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener, EnvVars initialEnvironment) throws IOException, InterruptedException {
        if (assignedLabels != null && !assignedLabels.trim().isEmpty()) {
            final Label label;
            try {
                label = Label.parseExpression(assignedLabels);
            } catch (ANTLRException e) {
                throw new IOException(e);
            }

            final Computer computer = Computer.currentComputer();
            final Node node = computer.getNode();

            if (!label.matches(node)) {
                // not running on node with requested label
                return;
            }
        }

        if (!launcher.isUnix()) {
            listener.getLogger().println(Messages.XvfbBuildWrapper_NotUnix());

            // we will not run on non Unix machines
            return;
        }

        @SuppressWarnings("rawtypes")
        final Run rawRun = (Run) run;
        final XvfbEnvironment xvfbEnvironment = launchXvfb(rawRun, workspace, launcher, listener);
        run.addAction(xvfbEnvironment);

        context.env("DISPLAY", ":" + xvfbEnvironment.displayName);
        context.setDisposer(new XvfbDisposer(xvfbEnvironment));
    }

    @DataBoundSetter
    public void setAdditionalOptions(String additionalOptions) {
        this.additionalOptions = additionalOptions;
    }

    @DataBoundSetter
    public void setAssignedLabels(String assignedLabels) {
        this.assignedLabels = assignedLabels;
    }

    @DataBoundSetter
    public void setAutoDisplayName(boolean autoDisplayName) {
        this.autoDisplayName = autoDisplayName;
    }
    
    @DataBoundSetter
    public void setKillExistingInstance(boolean killExistingInstance) {
        this.killExistingInstance = killExistingInstance;
    }

    @DataBoundSetter
    public void setDisplayName(int displayName) {
        this.displayName = displayName;
    }

    @DataBoundSetter
    public void setDisplayNameOffset(int displayNameOffset) {
        this.displayNameOffset = displayNameOffset;
    }

    @DataBoundSetter
    public void setInstallationName(final String installationName) {
        this.installationName = installationName;
    }

    @DataBoundSetter
    public void setParallelBuild(boolean parallelBuild) {
        this.parallelBuild = parallelBuild;
    }

    @DataBoundSetter
    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

}

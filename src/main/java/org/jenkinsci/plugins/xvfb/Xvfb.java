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

import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.google.common.base.Optional;
import com.thoughtworks.xstream.XStream;

import antlr.ANTLRException;
import hudson.CopyOnWrite;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Launcher.ProcStarter;
import hudson.Proc;
import hudson.Util;
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.AbstractProject;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Items;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
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
import hudson.util.XStream2;
import jenkins.model.Jenkins;
import jenkins.security.MasterToSlaveCallable;
import jenkins.tasks.SimpleBuildWrapper;
import net.sf.json.JSONObject;

public class Xvfb extends SimpleBuildWrapper {

    private static final class ComputerNameComparator implements Comparator<Computer> {

        private static final ComputerNameComparator INSTANCE = new ComputerNameComparator();

        @Override
        public int compare(final Computer left, final Computer right) {
            return left.getName().compareTo(right.getName());
        }

    }

    @Extension(ordinal = Double.MAX_VALUE)
    public static class XvfbBuildWrapperDescriptor extends BuildWrapperDescriptor {

        @Initializer(before = InitMilestone.PLUGINS_LISTED)
        public static void setupBackwardCompatibility() {
            setupBackwardCompatibilityOn(Items.XSTREAM2);
            final XStream xstream = new XmlFile(new File("mapping")).getXStream();
            if (xstream instanceof XStream2) {
                setupBackwardCompatibilityOn((XStream2) xstream);
            }
        }

        private static void setupBackwardCompatibilityOn(final XStream2 instance) {
            instance.addCompatibilityAlias("org.jenkinsci.plugins.xvfb.XvfbBuildWrapper", Xvfb.class);
            instance.addCompatibilityAlias("org.jenkinsci.plugins.xvfb.XvfbBuildWrapper$XvfbBuildWrapperDescriptor", Xvfb.XvfbBuildWrapperDescriptor.class);
        }

        /** Xvfb installations, this descriptor persists all installations configured. */
        @CopyOnWrite
        private volatile XvfbInstallation[] installations = new XvfbInstallation[0];

        public XvfbBuildWrapperDescriptor() {
            load();
        }

        /** adopted from @see hudson.model.AbstractProject.AbstractProjectDescriptor#doAutoCompleteAssignedLabels */
        public AutoCompletionCandidates doAutoCompleteAssignedLabels(@AncestorInPath final AbstractProject<?, ?> project, @QueryParameter final String value) {
            final AutoCompletionCandidates candidates = new AutoCompletionCandidates();
            final Set<Label> labels = Jenkins.get().getLabels();

            for (final Label label : labels) {
                if (value == null || label.getName().startsWith(value)) {
                    candidates.add(label.getName());
                }
            }

            return candidates;
        }

        /** adopted from @see hudson.model.AbstractProject.AbstractProjectDescriptor#doCheckAssignedLabels */
        public FormValidation doCheckAssignedLabels(@AncestorInPath final AbstractProject<?, ?> project, @QueryParameter final String value) {
            if (Util.fixEmpty(value) == null) {
                return FormValidation.ok();
            }

            try {
                Label.parseExpression(value);
            } catch (final ANTLRException e) {
                return FormValidation.error(e, Messages.XvfbBuildWrapper_AssignedLabelString_InvalidBooleanExpression(e.getMessage()));
            }

            final Jenkins jenkins = Jenkins.get();
            final Label label = jenkins.getLabel(value);

            if (label.isEmpty()) {
                for (final LabelAtom labelAtom : label.listAtoms()) {
                    if (labelAtom.isEmpty()) {
                        final LabelAtom nearest = LabelAtom.findNearest(labelAtom.getName());
                        return FormValidation.warning(Messages.XvfbBuildWrapper_AssignedLabelString_NoMatch_DidYouMean(labelAtom.getName(), nearest.getDisplayName()));
                    }
                }
                return FormValidation.warning(Messages.XvfbBuildWrapper_AssignedLabelString_NoMatch());
            }

            return FormValidation.okWithMarkup(Messages.XvfbBuildWrapper_LabelLink(jenkins.getRootUrl(), label.getUrl(), label.getNodes().size() + label.getClouds().size()));
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
        protected XmlFile getConfigFile() {
            final File rootDir = Jenkins.get().getRootDir();

            final File legacyConfiguration = new File(rootDir, "org.jenkinsci.plugins.xvfb.XvfbBuildWrapper.xml");
            if (legacyConfiguration.exists()) {
                final File newConfigurationFile = new File(rootDir, getId() + ".xml");
                if (!legacyConfiguration.renameTo(newConfigurationFile)) {
                    throw new IllegalStateException("Unable to rename legacy configuration file at " + legacyConfiguration + " to " + newConfigurationFile);
                }
            }

            return super.getConfigFile();
        }

        @Override
        public String getDisplayName() {
            return Messages.XvfbBuildWrapper_DisplayName();
        }

        public XvfbInstallation[] getInstallations() {
            return installations.clone();
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
            return req.bindJSON(Xvfb.class, formData);
        }

        public void setInstallations(final XvfbInstallation... installations) {
            if (installations == null) {
                this.installations = new XvfbInstallation[0];
            } else {
                this.installations = installations;
            }
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
    }

    private static final String JENKINS_XVFB_COOKIE = "_JENKINS_XVFB_COOKIE";

    private static final String STDERR_FD = "2";

    @SuppressWarnings("rawtypes")
    @Extension
    public static final RunListener<Run> xvfbShutdownListener = new RunListener<Run>() {
        @Override
        public void onCompleted(final Run r, final TaskListener listener) {
            final XvfbEnvironment xvfbEnvironment = r.getAction(XvfbEnvironment.class);

            if (xvfbEnvironment == null || !xvfbEnvironment.shutdownWithBuild) {
                return;
            }

            try {
                final Executor executor = r.getExecutor();
                if (executor == null) {
                    // not much we can do, but let's not fail the build if we can't cleanup
                    return;
                }

                final Computer computer = executor.getOwner();
                final Node node = computer.getNode();
                if (node == null) {
                    // not much we can do, but let's not fail the build if we can't cleanup
                    return;
                }

                final Launcher launcher = node.createLauncher(listener);

                Xvfb.shutdownAndCleanup(xvfbEnvironment, launcher, listener);
            } catch (final IOException e) {
                throw new RuntimeException(e);
            } catch (final InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    };

    private static final Map<String, List<XvfbEnvironment>> zombies = createOrLoadZombiesMap();

    @Extension
    public static final ComputerListener nodeListener = new ComputerListener() {
        @Override
        public void preOnline(final Computer c, final Channel channel, final FilePath root, final TaskListener listener) throws IOException, InterruptedException {
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

    private static final int MILLIS_IN_SECOND = 1000;

    /** default screen configuration for Xvfb, used by default, and if user left screen configuration blank */
    static final String DEFAULT_SCREEN = "1024x768x24";

    private static ConcurrentHashMap<String, List<XvfbEnvironment>> createOrLoadZombiesMap() {
        Jenkins.XSTREAM.registerConverter(new XvfbEnvironmentConverter());

        final XmlFile fileOfZombies = zombiesFile();

        if (fileOfZombies.exists()) {
            try {
                @SuppressWarnings("unchecked")
                final ConcurrentHashMap<String, List<XvfbEnvironment>> oldZombies = (ConcurrentHashMap<String, List<XvfbEnvironment>>) fileOfZombies.read();

                return oldZombies;
            } catch (final IOException ignore) {
            } finally {
                fileOfZombies.delete();
            }
        }

        return new ConcurrentHashMap<String, List<XvfbEnvironment>>();
    }

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

    private static void shutdownAndCleanupZombie(final Channel channel, final XvfbEnvironment zombie, final TaskListener listener) throws IOException, InterruptedException {

        listener.getLogger().println(Messages.XvfbBuildWrapper_KillingZombies(zombie.displayName, zombie.frameBufferDir));

        try {
            channel.call(new MasterToSlaveCallable<Void, InterruptedException>() {
                private static final long serialVersionUID = 1L;

                @Override
                public Void call() throws InterruptedException {
                    final ProcessTree processTree = ProcessTree.get();

                    for (final Iterator<OSProcess> i = processTree.iterator(); i.hasNext();) {
                        final OSProcess osProcess = i.next();

                        final EnvVars environment = osProcess.getEnvironmentVariables();

                        final String processCookie = environment.get(JENKINS_XVFB_COOKIE);

                        if (processCookie != null && processCookie.equals(zombie.cookie)) {
                            osProcess.kill();
                            final File zombieDir = new File(zombie.frameBufferDir);
                            if (!zombieDir.delete()) {
                                zombieDir.deleteOnExit();
                            }
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
    }

    private static XmlFile zombiesFile() {
        return new XmlFile(Jenkins.XSTREAM, new File(Jenkins.get().getRootDir(), XvfbEnvironment.class.getName() + "-zombies.xml"));
    };

    /** Name of the installation used in a configured job. */
    private String installationName;

    /** X11 DISPLAY name, if NULL chosen based on current executor number. */
    private Integer displayName;

    /** Xvfb screen argument, in the form WxHxD (width x height x pixel depth), i.e. 800x600x8. */
    private String screen = DEFAULT_SCREEN;

    /** Should the Xvfb output be displayed in job output. */
    private boolean debug = false;

    /** Time in milliseconds to wait for Xvfb initialization, by default 1 second. */
    private long timeout = 1;

    /** Offset for display names, default is 1. Display names are taken from build executor's number, i.e. if the build is performed by executor 4, and offset is 100, display name will be 104. */
    private int displayNameOffset = 1;

    /** Additional options to be passed to Xvfb */
    private String additionalOptions;

    /** Should the Xvfb display be around for post build actions, i.e. should it terminate with the whole build */
    private boolean shutdownWithBuild = false;

    /** Let Xvfb pick display number */
    private boolean autoDisplayName = false;

    /** Run only on nodes labeled */
    private String assignedLabels;

    /** Run on same node in parallel */
    private boolean parallelBuild = false;

    @DataBoundConstructor
    public Xvfb() {
    }

    protected ArgumentListBuilder createCommandArguments(final XvfbInstallation installation, final FilePath frameBufferDir, final int displayNameUsed) {
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

        if (screen != null && !screen.trim().isEmpty()) {
            cmd.add("-screen").add("0").add(screen);
        }

        cmd.add("-fbdir").add(frameBufferDir);

        if (additionalOptions != null) {
            cmd.addTokenized(additionalOptions);
        }
        return cmd;
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
        final XvfbInstallation[] installations = getDescriptor().getInstallations();

        // if there is only one installation and no name specified use that
        if (installationName == null && installations.length == 1) {
            return installations[0];
        }

        // if no installation name specified use 'default'
        final String installationNameToUse = Optional.fromNullable(installationName).or("default");

        for (final XvfbInstallation installation : installations) {
            if (installationNameToUse.equals(installation.getName())) {
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
        if (currentComputer == null) {
        	throw new IllegalStateException("Unable to access workspace on a node running the build, cannot continue.");
        }

        int displayNameUsed = determineDisplayName(run, currentComputer);

        final Node currentNode = currentComputer.getNode();
        if (currentNode == null) {
        	throw new IllegalStateException("Node is being removed, cannot continue");
        }

        if (!workspace.exists()) {
            workspace.mkdirs();
        }

        final FilePath frameBufferDir = workspace.createTempDir(".xvfb-" + run.getId() + "-", ".fbdir");

        final EnvVars environment = currentComputer.getEnvironment();
        final XvfbInstallation installation = getInstallation(environment, currentNode, listener);

        if (installation == null) {
            listener.error(Messages.XvfbBuildWrapper_NoInstallationsConfigured());

            throw new RunnerAbortedException();
        }

        final ArgumentListBuilder cmd = createCommandArguments(installation, frameBufferDir, displayNameUsed);

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

	private int determineDisplayName(final Run<?, ?> run, final Computer currentComputer) {
		if (displayName != null) {
			return displayName;
		}

        if (autoDisplayName) {
        	return -1;
        }

        final Executor executor = run.getExecutor();

        if (executor == null) {
        	throw new IllegalStateException("Invoked outside of build: executor of the run is null");
        }

        final int executorNumber= executor.getNumber();

        if (parallelBuild) {
            final Computer[] computers = Jenkins.get().getComputers();
            final int nodeIndex = Arrays.binarySearch(computers, currentComputer, ComputerNameComparator.INSTANCE);

            return nodeIndex * 100 + executorNumber + displayNameOffset;
        }
        else {
            return executorNumber + displayNameOffset;
        }
	}

    @DataBoundSetter
    public void setAdditionalOptions(final String additionalOptions) {
        this.additionalOptions = additionalOptions;
    }

    @DataBoundSetter
    public void setAssignedLabels(final String assignedLabels) {
        this.assignedLabels = assignedLabels;
    }

    @DataBoundSetter
    public void setAutoDisplayName(final boolean autoDisplayName) {
        this.autoDisplayName = autoDisplayName;
    }

    @DataBoundSetter
    public void setDebug(final boolean debug) {
        this.debug = debug;
    }

    @DataBoundSetter
    public void setDisplayName(final Integer displayName) {
        this.displayName = displayName;
    }

    @DataBoundSetter
    public void setDisplayNameOffset(final int displayNameOffset) {
        this.displayNameOffset = displayNameOffset;
    }

    @DataBoundSetter
    public void setInstallationName(final String installationName) {
        this.installationName = installationName;
    }

    @DataBoundSetter
    public void setParallelBuild(final boolean parallelBuild) {
        this.parallelBuild = parallelBuild;
    }

    @DataBoundSetter
    public void setScreen(final String screen) {
        this.screen = screen;
    }

    @DataBoundSetter
    public void setShutdownWithBuild(final boolean shutdownWithBuild) {
        this.shutdownWithBuild = shutdownWithBuild;
    }

    @DataBoundSetter
    public void setTimeout(final long timeout) {
        this.timeout = timeout;
    }

    @Override
    public void setUp(final Context context, final Run<?, ?> run, final FilePath workspace, final Launcher launcher, final TaskListener listener, final EnvVars initialEnvironment)
            throws IOException, InterruptedException {
        if (assignedLabels != null && !assignedLabels.trim().isEmpty()) {
            final Label label;
            try {
                label = Label.parseExpression(assignedLabels);
            } catch (final ANTLRException e) {
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
        final Run rawRun = run;
        final XvfbEnvironment xvfbEnvironment = launchXvfb(rawRun, workspace, launcher, listener);
        run.addAction(xvfbEnvironment);

        context.env("DISPLAY", ":" + xvfbEnvironment.displayName);
        context.setDisposer(new XvfbDisposer(xvfbEnvironment));
    }
}

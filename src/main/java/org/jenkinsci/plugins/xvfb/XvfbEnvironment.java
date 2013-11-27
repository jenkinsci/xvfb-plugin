package org.jenkinsci.plugins.xvfb;

import hudson.FilePath;
import hudson.Proc;
import hudson.model.InvisibleAction;

public class XvfbEnvironment extends InvisibleAction {
    /** Temporary directory to hold Xvfb session data, will not be persisted. */
    private final transient FilePath frameBufferDir;

    /** Remote path of the frame buffer dir, used only for killing zombies */
    private final transient String   remoteFrameBufferDir;

    /** Actual display name used, will not be persisted. */
    private final transient int      displayNameUsed;

    /** Handle to the Xvfb process. */
    private final transient Proc     process;

    /** The shutdownWithBuild indicator from the job configuration. */
    private final transient boolean  shutdownWithBuild;

    public XvfbEnvironment(final FilePath frameBufferDir, final int displayNameUsed, final Proc process, boolean shutdownWithBuild) {
        this.frameBufferDir = frameBufferDir;
        this.remoteFrameBufferDir = frameBufferDir.getRemote();
        this.displayNameUsed = displayNameUsed;
        this.process = process;
        this.shutdownWithBuild = shutdownWithBuild;
    }

    public int getDisplayNameUsed() {
        return displayNameUsed;
    }

    public FilePath getFrameBufferDir() {
        return frameBufferDir;
    }

    public Proc getProcess() {
        return process;
    }

    public boolean isShutdownWithBuild() {
        return shutdownWithBuild;
    }

    public String getRemoteFrameBufferDir() {
        return remoteFrameBufferDir;
    }
}

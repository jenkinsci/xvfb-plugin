package org.jenkinsci.plugins.xvfb;

import hudson.FilePath;
import hudson.Proc;
import hudson.model.InvisibleAction;

public class XvfbEnvironment extends InvisibleAction {
    /** Temporary directory to hold Xvfb session data, will not be persisted. */
    private final transient FilePath frameBufferDir;

    /** Actual display name used, will not be persisted. */
    private final transient int      displayNameUsed;

    /** Handle to the Xvfb process. */
    private final transient Proc     process;

    public XvfbEnvironment(final FilePath frameBufferDir, final int displayNameUsed, final Proc process) {
        this.frameBufferDir = frameBufferDir;
        this.displayNameUsed = displayNameUsed;
        this.process = process;
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
}

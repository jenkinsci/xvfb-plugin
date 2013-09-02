package org.jenkinsci.plugins.xvfb;

import hudson.FilePath;
import hudson.Proc;
import hudson.model.InvisibleAction;
import hudson.model.TaskListener;

public class XvfbEnvironment extends InvisibleAction {
    /** Temporary directory to hold Xvfb session data, will not be persisted. */
    private final transient FilePath frameBufferDir;

    /** Actual display name used, will not be persisted. */
    private final transient int      displayNameUsed;

    /** Handle to the Xvfb process. */
    private final transient Proc     process;

    /** The shutdownWithBuild indicator from the job configuration. */
    private final transient boolean  shutdownWithBuild;

	private final Boolean assignRandomDisplay;

	private XvfbDisplayAllocator allocator;

    public XvfbEnvironment(final FilePath frameBufferDir, final int usedDisplayName, final Proc process, boolean shutdownWithBuild, 
    		Boolean assignRandomDisplay, XvfbDisplayAllocator allocator) {
        this.frameBufferDir = frameBufferDir;
        this.displayNameUsed = usedDisplayName;
        this.process = process;
        this.shutdownWithBuild = shutdownWithBuild;
		this.assignRandomDisplay = assignRandomDisplay;
		this.allocator = allocator;
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

	public Boolean isAssignRandomDisplay() {
		return assignRandomDisplay;
	}

	public void shutdownAndCleanup(TaskListener listener) {
		final FilePath frameBufferDir = getFrameBufferDir();
        final Proc process = getProcess();

        listener.getLogger().println(Messages.XvfbBuildWrapper_Stopping());
        
        try{
            process.kill();
            frameBufferDir.deleteRecursive();
        } catch(Exception e){
        	throw new RuntimeException(e);
        }
        
        if (isAssignRandomDisplay())
        	allocator.free(getDisplayNameUsed());
	}
}

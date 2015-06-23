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

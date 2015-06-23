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
import hudson.remoting.VirtualChannel;

import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;

public class XvfbEnvironmentConverter implements Converter {

    private static final String REMOTE_FRAME_BUFFER_DIR_ATTR = "remoteFrameBufferDir";

    private static final String DISPLAY_NAME_USED_ATTR       = "displayNameUsed";

    public boolean canConvert(@SuppressWarnings("rawtypes") Class type) {
        return type != null && XvfbEnvironment.class.isAssignableFrom(type);
    }

    public void marshal(Object source, HierarchicalStreamWriter writer, MarshallingContext context) {
        final XvfbEnvironment xvfbEnvironment = (XvfbEnvironment) source;

        writer.addAttribute(DISPLAY_NAME_USED_ATTR, String.valueOf(xvfbEnvironment.getDisplayNameUsed()));
        writer.addAttribute(REMOTE_FRAME_BUFFER_DIR_ATTR, xvfbEnvironment.getRemoteFrameBufferDir());
    }

    public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
        final FilePath filePath = new FilePath((VirtualChannel) null, reader.getAttribute(REMOTE_FRAME_BUFFER_DIR_ATTR));

        final int displayNameUsed = Integer.parseInt(reader.getAttribute(DISPLAY_NAME_USED_ATTR));

        final XvfbEnvironment xvfbEnvironment = new XvfbEnvironment(filePath, displayNameUsed, null, false);

        return xvfbEnvironment;
    }
}

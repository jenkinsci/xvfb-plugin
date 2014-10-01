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

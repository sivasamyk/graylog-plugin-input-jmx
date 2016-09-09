package org.graylog.inputs.jmx;

import org.graylog2.plugin.PluginMetaData;
import org.graylog2.plugin.ServerStatus;
import org.graylog2.plugin.Version;

import java.net.URI;
import java.util.Collections;
import java.util.Set;

/**
 * Implement the PluginMetaData interface here.
 */
public class JMXInputMetaData implements PluginMetaData {
    @Override
    public String getUniqueId() {
        return "org.graylog.inputs.jmx.JMXInputPlugin";
    }

    @Override
    public String getName() {
        return "JMXInput";
    }

    @Override
    public String getAuthor() {
        return "Sivasamy Kaliappan";
    }

    @Override
    public URI getURL() {
        return URI.create("https://www.graylog.org/");
    }

    @Override
    public Version getVersion() {
        return new Version(1, 0, 2);
    }

    @Override
    public String getDescription() {
        return "Input Plugin to monitor JMX end points";
    }

    @Override
    public Version getRequiredVersion() {
        return new Version(1, 0, 0);
    }

    @Override
    public Set<ServerStatus.Capability> getRequiredCapabilities() {
        return Collections.emptySet();
    }
}

package io.github.lgcastellucci.upxcompress;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.EnvironmentSpecific;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.NodeSpecific;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolProperty;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.util.List;

/**
 * A configured UPX installation: a name plus how to obtain it (see
 * {@link UpxInstaller}). Configured under "Manage Jenkins &gt; Tools",
 * the same pattern used by the built-in JDK/Git/Maven tool installations.
 */
public class UpxInstallation extends ToolInstallation
        implements NodeSpecific<UpxInstallation>, EnvironmentSpecific<UpxInstallation> {

    private static final long serialVersionUID = 1L;

    @DataBoundConstructor
    public UpxInstallation(String name, String home, List<? extends ToolProperty<?>> properties) {
        super(name, home, properties);
    }

    @Override
    public UpxInstallation forNode(Node node, TaskListener log) throws IOException, InterruptedException {
        return new UpxInstallation(getName(), translateFor(node, log), getProperties().toList());
    }

    @Override
    public UpxInstallation forEnvironment(EnvVars environment) {
        return new UpxInstallation(getName(), environment.expand(getHome()), getProperties().toList());
    }

    @Extension
    public static class DescriptorImpl extends ToolDescriptor<UpxInstallation> {

        public DescriptorImpl() {
            load();
        }

        @Override
        public String getDisplayName() {
            return "UPX";
        }
    }
}

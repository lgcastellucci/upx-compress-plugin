package io.github.lgcastellucci.upxcompress;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

<<<<<<< HEAD
import javax.annotation.Nonnull;
=======
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
>>>>>>> eb1a20f1e4e6cf38af595fc10eac9574db4f9d3c
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Build step "Compress executable with UPX".
 *
 * The actual UPX binary is provided by a configured {@link UpxInstallation}
 * (see "Manage Jenkins &gt; Tools"), installed on demand by {@link UpxInstaller}
 * for the right OS/architecture. This step just resolves that installation
 * and invokes it against the given file.
 */
public class UpxCompressBuilder extends Builder implements SimpleBuildStep {

    private static final Pattern WINDOWS_VAR_PATTERN = Pattern.compile("%([A-Za-z_][A-Za-z0-9_]*)%");

    private final String executable;
    private String options = "--best --lzma";
    private String upxName;

    @DataBoundConstructor
    public UpxCompressBuilder(String executable) {
        this.executable = executable;
    }

    public String getExecutable() {
        return executable;
    }

    public String getOptions() {
        return options;
    }

    @DataBoundSetter
    public void setOptions(String options) {
        this.options = options;
    }

    public String getUpxName() {
        return upxName;
    }

    @DataBoundSetter
    public void setUpxName(String upxName) {
        this.upxName = upxName;
    }

    @Override
<<<<<<< HEAD
    public void perform(@Nonnull Run<?, ?> run,
                         @Nonnull FilePath workspace,
                         @Nonnull EnvVars env,
                         @Nonnull Launcher launcher,
                         @Nonnull TaskListener listener) throws InterruptedException, IOException {
=======
    public void perform(@NonNull Run<?, ?> run,
                         @NonNull FilePath workspace,
                         @NonNull hudson.EnvVars env,
                         @NonNull Launcher launcher,
                         @NonNull TaskListener listener) throws InterruptedException, IOException {
>>>>>>> eb1a20f1e4e6cf38af595fc10eac9574db4f9d3c

        // Supports both Jenkins-style (${VAR} / $VAR) and Windows batch-style
        // (%VAR%) variables, since the latter is only expanded automatically
        // by cmd.exe inside a batch step, not when read as plain text here.
        String resolvedExecutable = expand(executable, env);
        String resolvedOptions = options == null ? "" : expand(options, env);

        if (resolvedExecutable == null || resolvedExecutable.trim().isEmpty()) {
            listener.getLogger().println("[UPX] No executable name configured.");
            run.setResult(Result.FAILURE);
            return;
        }

        FilePath target = workspace.child(resolvedExecutable);
        if (!target.exists()) {
            listener.getLogger().println("[UPX] File not found: " + target.getRemote());
            run.setResult(Result.UNSTABLE);
            return;
        }

        UpxInstallation installation = resolveInstallation(listener);
        if (installation == null) {
            run.setResult(Result.FAILURE);
            return;
        }

        Computer computer = workspace.toComputer();
        Node node = computer != null ? computer.getNode() : Jenkins.get();
        if (node == null) {
            listener.getLogger().println("[UPX] Could not determine the node this build is running on.");
            run.setResult(Result.FAILURE);
            return;
        }
        installation = installation.forNode(node, listener).forEnvironment(env);

        String exeName = launcher.isUnix() ? "upx" : "upx.exe";
        FilePath upxBin = new FilePath(launcher.getChannel(), installation.getHome()).child(exeName);

        List<String> cmd = new ArrayList<>();
        cmd.add(upxBin.getRemote());
        if (!resolvedOptions.trim().isEmpty()) {
            cmd.addAll(Arrays.asList(resolvedOptions.trim().split("\\s+")));
        }
        cmd.add(target.getRemote());

        listener.getLogger().println("[UPX] Running: " + String.join(" ", cmd));

        int exitCode = launcher.launch()
                .cmds(cmd)
                .stdout(listener)
                .pwd(workspace)
                .join();

        if (exitCode != 0) {
            listener.getLogger().println("[UPX] Compression failed (exit code " + exitCode + ")");
            run.setResult(Result.FAILURE);
        }
    }

    private UpxInstallation resolveInstallation(TaskListener listener) {
        UpxInstallation[] all = DescriptorImpl.allInstallations();
        if (all.length == 0) {
            listener.getLogger().println("[UPX] No UPX installation is configured. "
                    + "Add one under Manage Jenkins > Tools.");
            return null;
        }
        if (upxName != null && !upxName.trim().isEmpty()) {
            for (UpxInstallation i : all) {
                if (i.getName().equals(upxName)) {
                    return i;
                }
            }
            listener.getLogger().println("[UPX] No UPX installation named \"" + upxName + "\" is configured.");
            return null;
        }
        if (all.length == 1) {
            return all[0];
        }
        listener.getLogger().println("[UPX] Several UPX installations are configured; "
                + "please pick one in the step's \"UPX installation\" field.");
        return null;
    }

    /**
     * Expands variables in both the Jenkins syntax ({@code ${VAR}} / {@code $VAR})
     * and the Windows batch syntax ({@code %VAR%}) — the latter is only expanded
     * automatically by cmd.exe inside a batch step, not when read as plain text
     * as we do here.
     */
    private static String expand(String input, EnvVars env) {
        if (input == null) {
            return null;
        }
        String result = env.expand(input);
        Matcher matcher = WINDOWS_VAR_PATTERN.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String value = env.get(matcher.group(1));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : matcher.group()));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    @Symbol("upxCompress")
    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Compress executable with UPX";
        }

        public ListBoxModel doFillUpxNameItems() {
            ListBoxModel items = new ListBoxModel();
            for (UpxInstallation i : allInstallations()) {
                items.add(i.getName());
            }
            return items;
        }

        static UpxInstallation[] allInstallations() {
            return Jenkins.get().getDescriptorByType(UpxInstallation.DescriptorImpl.class).getInstallations();
        }
    }
}

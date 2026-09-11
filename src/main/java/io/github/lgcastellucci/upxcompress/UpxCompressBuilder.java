package io.github.lgcastellucci.upxcompress;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Build step "Compactar executável com UPX".
 *
 * O binário do UPX (Windows e Linux) fica embutido dentro do próprio .hpi,
 * em src/main/resources/upx-bin/. Em tempo de execução ele é copiado para
 * dentro do workspace do build (funciona também em agentes remotos, já que
 * usamos FilePath/Launcher em vez de acessar o disco local do master).
 */
public class UpxCompressBuilder extends Builder implements SimpleBuildStep {

    private final String executable;
    private String options = "--best --lzma";

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

    @Override
    public void perform(@Nonnull Run<?, ?> run,
                         @Nonnull FilePath workspace,
                         @Nonnull hudson.EnvVars env,
                         @Nonnull Launcher launcher,
                         @Nonnull TaskListener listener) throws InterruptedException, IOException {

        // Permite usar variáveis do Jenkins no nome do executável, ex: ${PROJETO}.exe
        String resolvedExecutable = env.expand(executable);
        String resolvedOptions = options == null ? "" : env.expand(options);

        FilePath target = workspace.child(resolvedExecutable);
        if (!target.exists()) {
            listener.getLogger().println("[UPX] Arquivo não encontrado: " + target.getRemote());
            run.setResult(Result.UNSTABLE);
            return;
        }

        FilePath upxBin = extractUpx(workspace, launcher, listener);

        List<String> cmd = new ArrayList<>();
        cmd.add(upxBin.getRemote());
        if (!resolvedOptions.trim().isEmpty()) {
            cmd.addAll(Arrays.asList(resolvedOptions.trim().split("\\s+")));
        }
        cmd.add(target.getRemote());

        listener.getLogger().println("[UPX] Executando: " + String.join(" ", cmd));

        int exitCode = launcher.launch()
                .cmds(cmd)
                .stdout(listener)
                .pwd(workspace)
                .join();

        if (exitCode != 0) {
            listener.getLogger().println("[UPX] Falha ao compactar (exit code " + exitCode + ")");
            run.setResult(Result.FAILURE);
        }
    }

    /**
     * Extrai o binário do UPX correto (Windows/Linux) para dentro do workspace
     * do build atual, tornando o step independente de qualquer caminho fixo
     * no disco do agente.
     */
    private FilePath extractUpx(FilePath workspace, Launcher launcher, TaskListener listener)
            throws IOException, InterruptedException {

        boolean isUnix = launcher.isUnix();
        String resourceName = isUnix ? "/upx-bin/upx-linux" : "/upx-bin/upx.exe";
        String targetName = isUnix ? "upx" : "upx.exe";

        FilePath tmpDir = workspace.child(".upx-tool");
        tmpDir.mkdirs();
        FilePath binPath = tmpDir.child(targetName);

        // Só reextrai se ainda não existir (evita custo em builds repetidos no mesmo workspace)
        if (!binPath.exists()) {
            try (InputStream in = UpxCompressBuilder.class.getResourceAsStream(resourceName)) {
                if (in == null) {
                    throw new IOException("Binário do UPX não encontrado dentro do plugin: " + resourceName +
                            ". Confira src/main/resources" + resourceName);
                }
                binPath.copyFrom(in);
            }
            if (isUnix) {
                binPath.chmod(0755);
            }
        }

        return binPath;
    }

    @Symbol("upxCompress")
    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Compactar executável com UPX";
        }

        public FormValidation doCheckExecutable(@QueryParameter String value) {
            if (Util.fixEmptyAndTrim(value) == null) {
                return FormValidation.error("Informe o nome do executável, ex: projeto.exe");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckOptions(@QueryParameter String value) {
            return FormValidation.ok();
        }
    }
}

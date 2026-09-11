package io.github.lgcastellucci.upxcompress;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import jenkins.MasterToSlaveFileCallable;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Build step "Compactar executável com UPX".
 *
 * Em vez de depender de um upx.exe fixo no disco (ou embutido no .hpi), este
 * step baixa o binário oficial do UPX diretamente das releases do GitHub
 * (upx/upx) na primeira execução em cada workspace, confere o hash SHA-256
 * contra um valor fixado no código-fonte e só então extrai e executa o
 * binário. Isso evita versionar um binário de terceiros no repositório do
 * plugin e garante que o que é executado é exatamente o que a UPX publicou.
 */
public class UpxCompressBuilder extends Builder implements SimpleBuildStep {

    // Atualize estes três valores juntos ao adotar uma nova versão do UPX.
    // Hashes conferidos a partir dos artefatos oficiais em
    // https://github.com/upx/upx/releases/tag/v5.2.1
    private static final String UPX_VERSION = "5.2.1";

    private static final String WIN64_URL =
            "https://github.com/upx/upx/releases/download/v" + UPX_VERSION + "/upx-" + UPX_VERSION + "-win64.zip";
    private static final String WIN64_SHA256 =
            "eabc6792a347d45e945be7748423e7868fd01b0d2bcaa2f4b1031fd71ff69bda";
    private static final String WIN64_ENTRY = "upx-" + UPX_VERSION + "-win64/upx.exe";

    private static final String LINUX_AMD64_URL =
            "https://github.com/upx/upx/releases/download/v" + UPX_VERSION + "/upx-" + UPX_VERSION + "-amd64_linux.tar.xz";
    private static final String LINUX_AMD64_SHA256 =
            "402162aad30af47e60dbd767fb2e64ca394ace9727ba1f40283641f1d1b91657";
    private static final String LINUX_AMD64_ENTRY = "upx-" + UPX_VERSION + "-amd64_linux/upx";

    private static final Pattern WINDOWS_VAR_PATTERN = Pattern.compile("%([A-Za-z_][A-Za-z0-9_]*)%");

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
        // e também a sintaxe %PROJETO% do Windows (não é expandida pelo Jenkins
        // sozinho fora de um step de batch, então fazemos isso manualmente aqui).
        String resolvedExecutable = expand(executable, env);
        String resolvedOptions = options == null ? "" : expand(options, env);

        FilePath target = workspace.child(resolvedExecutable);
        if (!target.exists()) {
            listener.getLogger().println("[UPX] Arquivo não encontrado: " + target.getRemote());
            run.setResult(Result.UNSTABLE);
            return;
        }

        FilePath upxBin;
        try {
            upxBin = obtainUpx(workspace, launcher, listener);
        } catch (IOException e) {
            listener.getLogger().println("[UPX] " + e.getMessage());
            run.setResult(Result.FAILURE);
            return;
        }

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
     * Garante que o binário do UPX (correto para o SO do agente) esteja
     * disponível dentro do workspace, baixando-o das releases oficiais do
     * GitHub e conferindo o SHA-256 quando ainda não tiver sido baixado.
     * Fica em cache em ".upx-tool/&lt;versão&gt;/" no workspace, então builds
     * seguintes no mesmo workspace não baixam de novo.
     */
    private FilePath obtainUpx(FilePath workspace, Launcher launcher, TaskListener listener)
            throws IOException, InterruptedException {

        boolean isUnix = launcher.isUnix();
        String downloadUrl = isUnix ? LINUX_AMD64_URL : WIN64_URL;
        String expectedSha256 = isUnix ? LINUX_AMD64_SHA256 : WIN64_SHA256;
        String entryName = isUnix ? LINUX_AMD64_ENTRY : WIN64_ENTRY;
        String targetName = isUnix ? "upx" : "upx.exe";

        FilePath cacheDir = workspace.child(".upx-tool").child(UPX_VERSION);
        FilePath binPath = cacheDir.child(targetName);

        if (binPath.exists()) {
            return binPath;
        }

        cacheDir.mkdirs();
        FilePath archivePath = cacheDir.child(isUnix ? "upx.tar.xz" : "upx.zip");

        listener.getLogger().println("[UPX] Baixando " + downloadUrl);
        archivePath.copyFrom(new URL(downloadUrl));

        String actualSha256 = sha256Of(archivePath);
        if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
            archivePath.delete();
            throw new IOException("hash SHA-256 não confere para " + downloadUrl
                    + " (esperado " + expectedSha256 + ", obtido " + actualSha256
                    + "). Download abortado por segurança.");
        }
        listener.getLogger().println("[UPX] Hash SHA-256 verificado com sucesso.");

        if (isUnix) {
            // "tar" com suporte a xz está disponível por padrão em praticamente
            // qualquer agente Linux, então evitamos depender de bibliotecas
            // extras só para descompactar um .tar.xz.
            int exit = launcher.launch()
                    .cmds("tar", "-xf", archivePath.getRemote(), "-C", cacheDir.getRemote(), entryName)
                    .stdout(listener)
                    .pwd(cacheDir)
                    .join();
            if (exit != 0) {
                throw new IOException("falha ao extrair " + entryName + " de " + archivePath.getRemote());
            }
            FilePath extracted = cacheDir.child(entryName);
            extracted.renameTo(binPath);
            // remove a pasta "upx-X.Y.Z-amd64_linux/" que sobrou vazia
            cacheDir.child(entryName.substring(0, entryName.indexOf('/'))).deleteRecursive();
            binPath.chmod(0755);
        } else {
            extractZipEntry(archivePath, entryName, binPath);
        }

        archivePath.delete();
        return binPath;
    }

    /** Calcula o SHA-256 de um arquivo, rodando no mesmo nó (master ou agente) onde o arquivo está. */
    private static String sha256Of(FilePath file) throws IOException, InterruptedException {
        return file.act(new MasterToSlaveFileCallable<String>() {
            @Override
            public String invoke(File f, VirtualChannel channel) throws IOException {
                try (InputStream in = new FileInputStream(f)) {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                    }
                    StringBuilder hex = new StringBuilder();
                    for (byte b : digest.digest()) {
                        hex.append(String.format("%02x", b));
                    }
                    return hex.toString();
                } catch (NoSuchAlgorithmException e) {
                    throw new IOException("SHA-256 indisponível na JVM", e);
                }
            }
        });
    }

    /** Extrai uma única entrada de um .zip para o caminho de destino, no mesmo nó onde o .zip está. */
    private static void extractZipEntry(FilePath zipPath, String entryName, FilePath destPath)
            throws IOException, InterruptedException {
        zipPath.act(new MasterToSlaveFileCallable<Void>() {
            @Override
            public Void invoke(File zipFile, VirtualChannel channel) throws IOException {
                File dest = new File(destPath.getRemote());
                File parent = dest.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (ZipFile zip = new ZipFile(zipFile)) {
                    ZipEntry entry = zip.getEntry(entryName);
                    if (entry == null) {
                        throw new IOException("entrada não encontrada no zip: " + entryName);
                    }
                    try (InputStream in = zip.getInputStream(entry);
                         OutputStream out = new FileOutputStream(dest)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }
                }
                return null;
            }
        });
    }

    /**
     * Expande variáveis tanto na sintaxe do Jenkins ({@code ${VAR}} / {@code $VAR})
     * quanto na sintaxe do Windows batch ({@code %VAR%}), já que esta última só é
     * expandida automaticamente pelo cmd.exe dentro de um step de batch — não
     * quando o valor só é lido como texto, como fazemos aqui.
     */
    private static String expand(String input, hudson.EnvVars env) {
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

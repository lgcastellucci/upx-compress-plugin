package io.github.lgcastellucci.upxcompress;

import hudson.Extension;
import hudson.FilePath;
import hudson.ProxyConfiguration;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolInstallerDescriptor;
import jenkins.MasterToSlaveFileCallable;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Downloads and installs a specific version of UPX, straight from the
 * official upx/upx GitHub releases, matching the OS/architecture of the
 * node the build runs on. Verifies the downloaded archive's SHA-256
 * against the digest GitHub's release API publishes for the asset,
 * when available.
 *
 * The download itself runs on the target node (not the controller),
 * using Jenkins' own proxy configuration ({@link ProxyConfiguration}),
 * so it also works through an agent behind a proxy.
 */
public class UpxInstaller extends ToolInstaller {

    private final String version;

    @DataBoundConstructor
    public UpxInstaller(String label, String version) {
        super(label);
        this.version = version;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public FilePath performInstallation(ToolInstallation tool, Node node, TaskListener log)
            throws IOException, InterruptedException {

        FilePath dir = preferredLocation(tool, node);
        Asset asset = resolveAsset(node);
        String fileName = asset.fileName(version);

        FilePath marker = dir.child(".installed-" + version + "-" + asset.suffix);
        FilePath bin = dir.child(asset.exeName);
        if (marker.exists() && bin.exists()) {
            return dir;
        }

        dir.mkdirs();
        FilePath archive = dir.child(fileName);
        String url = "https://github.com/upx/upx/releases/download/v" + version + "/" + fileName;

        log.getLogger().println("[UPX] Downloading " + url);
        download(archive, url);
        verify(archive, fileName, url, log);

        if (asset.zip) {
            archive.unzip(dir);
        } else {
            int exit = node.createLauncher(log).launch()
                    .cmds("tar", "-xf", archive.getRemote(), "-C", dir.getRemote())
                    .stdout(log)
                    .pwd(dir)
                    .join();
            if (exit != 0) {
                throw new IOException("Failed to extract " + archive.getRemote()
                        + " (is \"tar\" available on this node?)");
            }
        }
        archive.delete();

        FilePath extractedDir = dir.child(asset.entryDir(version));
        FilePath extractedBin = extractedDir.child(asset.exeName);
        extractedBin.renameTo(bin);
        extractedDir.deleteRecursive();
        if (!asset.zip) {
            bin.chmod(0755);
        }

        marker.write("", "UTF-8");
        return dir;
    }

    // ---------------------------------------------------------------
    // Platform/architecture resolution
    // ---------------------------------------------------------------

    private static Asset resolveAsset(Node node) throws IOException, InterruptedException {
        Map<String, String> props = systemProperties(node);
        String osName = props.getOrDefault("os.name", "").toLowerCase(Locale.ROOT);
        String osArch = props.getOrDefault("os.arch", "").toLowerCase(Locale.ROOT);

        boolean windows = osName.contains("windows");
        boolean amd64 = osArch.contains("amd64") || osArch.contains("x86_64");
        boolean x86 = osArch.equals("x86") || osArch.equals("i386") || osArch.equals("i686");
        boolean arm64 = osArch.contains("aarch64") || osArch.contains("arm64");
        boolean ppc64le = osArch.contains("ppc64le") || osArch.contains("powerpc64le");

        if (windows) {
            if (amd64) {
                return new Asset("win64", true, "upx.exe");
            }
            if (x86) {
                return new Asset("win32", true, "upx.exe");
            }
        } else {
            // UPX does not publish macOS binaries; only Linux is supported here.
            if (amd64) {
                return new Asset("amd64_linux", false, "upx");
            }
            if (arm64) {
                return new Asset("arm64_linux", false, "upx");
            }
            if (x86) {
                return new Asset("i386_linux", false, "upx");
            }
            if (ppc64le) {
                return new Asset("powerpc64le_linux", false, "upx");
            }
        }
        throw new IOException("No UPX build available for " + osName + "/" + osArch
                + ". See https://github.com/upx/upx/releases for the list of supported platforms.");
    }

    private static Map<String, String> systemProperties(Node node) throws IOException, InterruptedException {
        FilePath root = node.getRootPath();
        if (root == null) {
            throw new IOException("Node \"" + node.getDisplayName() + "\" is offline");
        }
        return root.act(new MasterToSlaveFileCallable<Map<String, String>>() {
            @Override
            public Map<String, String> invoke(File f, VirtualChannel channel) {
                Map<String, String> m = new HashMap<>();
                m.put("os.name", System.getProperty("os.name", ""));
                m.put("os.arch", System.getProperty("os.arch", ""));
                return m;
            }
        });
    }

    private static final class Asset {
        final String suffix;
        final boolean zip;
        final String exeName;

        Asset(String suffix, boolean zip, String exeName) {
            this.suffix = suffix;
            this.zip = zip;
            this.exeName = exeName;
        }

        String fileName(String version) {
            return "upx-" + version + "-" + suffix + (zip ? ".zip" : ".tar.xz");
        }

        String entryDir(String version) {
            return "upx-" + version + "-" + suffix;
        }
    }

    // ---------------------------------------------------------------
    // Download + verification (runs on the target node, proxy-aware)
    // ---------------------------------------------------------------

    private static void download(FilePath dest, String url) throws IOException, InterruptedException {
        dest.act(new MasterToSlaveFileCallable<Void>() {
            @Override
            public Void invoke(File f, VirtualChannel channel) throws IOException {
                try {
                    URI uri = URI.create(url);
                    HttpClient client = ProxyConfiguration.newHttpClient();
                    HttpRequest request = ProxyConfiguration.newHttpRequestBuilder(uri).GET().build();
                    HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    if (response.statusCode() / 100 != 2) {
                        throw new IOException("HTTP " + response.statusCode() + " while downloading " + url);
                    }
                    try (InputStream in = response.body(); OutputStream out = new FileOutputStream(f)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while downloading " + url, e);
                }
                return null;
            }
        });
    }

    /**
     * Cross-checks the downloaded archive's SHA-256 against the digest
     * published by GitHub's release API for that asset, if GitHub provides
     * one. If the metadata call fails for any reason (offline controller,
     * rate limiting, etc.) the install still proceeds, just without that
     * extra cross-check, and a warning is logged.
     */
    private void verify(FilePath archive, String fileName, String url, TaskListener log)
            throws IOException, InterruptedException {
        String actual = sha256Of(archive);
        String expected = fetchExpectedSha256(fileName, log);
        if (expected != null) {
            if (!expected.equalsIgnoreCase(actual)) {
                archive.delete();
                throw new IOException("SHA-256 mismatch for " + url
                        + " (expected " + expected + ", got " + actual + "). Aborting for safety.");
            }
            log.getLogger().println("[UPX] SHA-256 verified against GitHub's published digest: " + actual);
        } else {
            log.getLogger().println("[UPX] SHA-256 (not cross-checked, no digest published for this asset): " + actual);
        }
    }

    private String fetchExpectedSha256(String fileName, TaskListener log) {
        try {
            String apiUrl = "https://api.github.com/repos/upx/upx/releases/tags/v" + version;
            HttpClient client = ProxyConfiguration.newHttpClient();
            HttpRequest request = ProxyConfiguration.newHttpRequestBuilder(URI.create(apiUrl))
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.getLogger().println("[UPX] Could not query GitHub release metadata (HTTP "
                        + response.statusCode() + "); skipping SHA-256 cross-check.");
                return null;
            }
            JSONObject root = JSONObject.fromObject(response.body());
            JSONArray assets = root.getJSONArray("assets");
            for (int i = 0; i < assets.size(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (fileName.equals(asset.optString("name"))) {
                    String digest = asset.optString("digest", null);
                    if (digest != null && digest.startsWith("sha256:")) {
                        return digest.substring("sha256:".length());
                    }
                    return null;
                }
            }
            return null;
        } catch (Exception e) {
            log.getLogger().println("[UPX] Could not query GitHub release metadata (" + e
                    + "); skipping SHA-256 cross-check.");
            return null;
        }
    }

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
                    throw new IOException("SHA-256 not available in this JVM", e);
                }
            }
        });
    }

    @Extension
    public static final class DescriptorImpl extends ToolInstallerDescriptor<UpxInstaller> {

        @Override
        public boolean isApplicable(Class<? extends ToolInstallation> toolType) {
            return toolType == UpxInstallation.class;
        }

        @Override
        public String getDisplayName() {
            return "Download from upx/upx GitHub releases";
        }
    }
}

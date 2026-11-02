package com.hermes.loader.bootstrap;

import com.hermes.loader.Env;
import com.hermes.loader.Prefs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Installs a prebuilt bundle: a Debian rootfs that already has the apt packages
 * and a {@code /opt/hermes} that already has the checkout, a self-contained
 * Python and a finished virtualenv.
 *
 * <p>Why this exists: doing {@code apt-get install} and {@code uv pip install}
 * inside PRoot takes about ten minutes, because PRoot intercepts every syscall
 * and turns them into several. The same work on a build machine takes a minute
 * and a half, so the phone only has to download and untar the result.
 *
 * <p>Everything here is optional and fail-soft: the caller keeps the classic
 * {@code setup.sh} path as the fallback, so a missing, stale or broken bundle can
 * never leave the user without an installation route.
 */
public final class BundleInstaller {

    /**
     * Anonymous, publicly reachable manifest. The source repository is private,
     * and GitHub requires a token for release assets of a private repository, so
     * the archives live in a separate public repository that holds nothing but
     * bundles.
     */
    public static final String DEFAULT_MANIFEST_URL =
            "https://raw.githubusercontent.com/santoni-star/hermes-loader-bundles/main/manifest.json";

    /** Marker written into {@code storage/agent} so support can see the origin. */
    public static final String BUNDLE_MARKER = ".hermes-bundle";

    private BundleInstaller() {
    }

    /** Manifest URL: the setting when present, the published default otherwise. */
    public static String manifestUrl(Prefs prefs) {
        String value = prefs.bundleUrl();
        if (value == null || value.trim().isEmpty()) {
            return DEFAULT_MANIFEST_URL;
        }
        return value.trim();
    }

    public static BundleManifest fetch(Prefs prefs, Log log) throws Exception {
        String url = manifestUrl(prefs);
        log.line("[BUNDLE] Manifest: " + url);
        String json = Downloader.fetchString(url);
        BundleManifest manifest = BundleManifest.parse(json, Env.abiDir());
        log.line("[BUNDLE] Bundle " + manifest.describe() + " (" + manifest.layers.size()
                + " layer(s) for " + Env.abiDir() + ")");
        return manifest;
    }

    /**
     * Downloads and activates the rootfs layer.
     *
     * @return true when a rootfs was deployed; false when the bundle has no
     *         rootfs layer, so the caller should use the normal installer.
     */
    public static boolean installRootfs(Env env, Prefs prefs, Log log) throws Exception {
        BundleManifest manifest = fetch(prefs, log);
        BundleManifest.Layer layer = manifest.layer(BundleManifest.TARGET_ROOTFS);
        if (layer == null) {
            log.line("[BUNDLE] No rootfs layer in this bundle.");
            return false;
        }
        // Never download a userland this build cannot run. The published bundle
        // is still Bookworm (no "debian" field), so it is skipped before the
        // 91 MB archive is fetched and the Trixie installer takes over.
        if (layer.debian != Env.ROOTFS_DEBIAN_MAJOR) {
            log.line("\u001b[1;33m[BUNDLE]\u001b[0m Rootfs layer targets Debian "
                    + (layer.debian == 0 ? "unknown" : layer.debian)
                    + ", this build needs Debian " + Env.ROOTFS_DEBIAN_MAJOR
                    + " - using the normal installer.");
            return false;
        }

        File staging = new File(env.files, "rootfs.new");
        deleteQuietly(staging);
        Env.mkdirs(staging);

        File archive = null;
        try {
            archive = fetchLayer(env, layer, log);
            log.line("[FS] Extracting the prebuilt rootfs…");
            TarExtract.extract(archive, layer.gzip, staging, layer.target, log);

            if (!new File(staging, "bin/bash").isFile()
                    && !new File(staging, "usr/bin/bash").isFile()) {
                throw new IOException("The rootfs layer contains no /bin/bash — it is not"
                        + " a Debian rootfs. Check the bundle manifest.");
            }

            RootfsInstaller.postProcess(staging);
            RootfsInstaller.activate(env, staging, "bundle " + manifest.id, log);
            return true;
        } catch (Exception e) {
            deleteQuietly(staging);
            throw e;
        } finally {
            if (archive != null) {
                //noinspection ResultOfMethodCallIgnored
                archive.delete();
            }
        }
    }

    /**
     * Downloads and activates the agent layer ({@code opt/hermes} inside the
     * archive).
     *
     * <p>The layer is self-contained: the Python interpreter lives in
     * {@code /opt/hermes/.python}, not in the venv owner's home, so activating it
     * is a single directory rename and never touches {@code storage/home}.
     *
     * <p>No provider is written to {@code config.yaml}: the agent's own setup and
     * whatever Nous grants the account decide that.
     *
     * @return true when the agent was deployed.
     */
    public static boolean installAgent(Env env, Prefs prefs, Log log) throws Exception {
        BundleManifest manifest = fetch(prefs, log);
        BundleManifest.Layer layer = manifest.layer(BundleManifest.TARGET_AGENT);
        if (layer == null) {
            log.line("[BUNDLE] No agent layer in this bundle — the normal installer will"
                    + " build the environment on the phone.");
            return false;
        }

        if (env.agentInstalled()) {
            log.line("[BUNDLE] An agent is already installed at " + env.agent + " — keeping it.");
            return false;
        }

        File staging = new File(env.files, "bundle.new");
        deleteQuietly(staging);
        Env.mkdirs(staging);

        File archive = null;
        try {
            archive = fetchLayer(env, layer, log);
            log.line("[FS] Extracting the prebuilt agent…");
            TarExtract.extract(archive, layer.gzip, staging, layer.target, log);

            File fresh = new File(staging, "opt/hermes");
            if (!new File(fresh, ".venv/bin/hermes").isFile()) {
                throw new IOException("The agent layer has no .venv/bin/hermes — refusing to"
                        + " activate it.");
            }

            activateAgent(env, fresh, manifest, log);
            log.line("[BUNDLE] Agent ready — no packages to install on this device.");
            return true;
        } finally {
            if (archive != null) {
                //noinspection ResultOfMethodCallIgnored
                archive.delete();
            }
            deleteQuietly(staging);
        }
    }

    private static void activateAgent(Env env, File fresh, BundleManifest manifest, Log log)
            throws IOException {
        write(new File(fresh, BUNDLE_MARKER),
                "bundle=" + manifest.id + "\n"
                        + "agent_commit=" + manifest.agentCommit + "\n"
                        + "python=" + manifest.python + "\n"
                        + "installed=" + System.currentTimeMillis() + "\n");

        File retired = new File(env.files, "agent.old");
        deleteQuietly(retired);
        if (env.agent.exists() && !env.agent.renameTo(retired)) {
            // A half-written tree from an interrupted install: it cannot be
            // renamed because it is not empty, so remove it entry by entry.
            RootfsInstaller.deleteRecursive(env.agent);
        }
        if (!fresh.renameTo(env.agent)) {
            throw new IOException("Cannot activate " + fresh + " -> " + env.agent);
        }
        RootfsInstaller.deleteRecursive(retired);
        log.line("[BUNDLE] Agent activated at " + env.agent);
    }

    // ------------------------------------------------------------------ plumbing

    /** Downloads one layer, verifies it and returns the local archive. */
    @android.annotation.SuppressLint("UsableSpace")
    private static File fetchLayer(Env env, BundleManifest.Layer layer, Log log) throws Exception {
        File archive = new File(env.cache, safeName(layer.id) + "-" + Env.abiDir()
                + (layer.gzip ? ".tar.gz" : ".tar.xz"));

        long need = layer.bytes + Math.max(layer.unpacked, layer.bytes * 4);
        long free = env.files.getUsableSpace();
        if (need > 0 && free > 0 && free < need) {
            throw new IOException("Not enough free space for the " + layer.target
                    + " layer: need about " + Downloader.mb(need) + ", have "
                    + Downloader.mb(free) + ".");
        }

        log.line("[NET] " + layer.id + " layer — " + layer.sizeText() + ", downloading…");
        Downloader.download(new RemoteArchive(layer.url, layer.gzip, null, null, layer.id), archive, log);

        if (!layer.sha256.isEmpty()) {
            log.line("[NET] Verifying checksum…");
            String actual = RootfsInstaller.digest(archive, "SHA-256");
            if (!layer.sha256.equalsIgnoreCase(actual)) {
                //noinspection ResultOfMethodCallIgnored
                archive.delete();
                throw new IOException("Checksum mismatch for the " + layer.id
                        + " layer — the download is corrupt or the manifest is stale.");
            }
        } else {
            log.line("[NET] \u001b[1;33m[WARN]\u001b[0m The manifest has no checksum for this layer.");
        }
        return archive;
    }

    private static String safeName(String id) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < id.length() && sb.length() < 40; i++) {
            char c = id.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '-');
        }
        return sb.length() == 0 ? "bundle" : sb.toString();
    }

    private static void write(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) Env.mkdirs(parent);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            RootfsInstaller.deleteRecursive(f);
        }
    }
}

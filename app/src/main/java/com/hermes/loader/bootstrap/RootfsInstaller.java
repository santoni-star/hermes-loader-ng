package com.hermes.loader.bootstrap;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;

import com.hermes.loader.Env;
import com.hermes.loader.Prefs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/** Downloads + extracts the Debian mini rootfs into {@code files/rootfs}. */
public final class RootfsInstaller {

    private RootfsInstaller() {
    }

    /** @return true if a fresh rootfs was deployed. */
    public static boolean ensure(Context ctx, Env env, Prefs prefs, Log log) throws Exception {
        if (env.rootfsInstalled()) {
            log.line("[INIT] Linux rootfs present at " + env.rootfs + " — OK.");
            return false;
        }
        return reinstall(ctx, env, prefs, log);
    }

    /** @return true when a new rootfs replaced the old one. */
    public static boolean reinstall(Context ctx, Env env, Prefs prefs, Log log) throws Exception {
        String arch = Env.debianArch();

        // A previous failed attempt (for example a cloud disk image) can leave
        // gigabytes of junk behind. Free that space before downloading again.
        if (env.rootfs.exists() && !env.rootfsInstalled()) {
            log.line("[FS] Removing incomplete rootfs…");
            deleteRecursive(env.rootfs);
        }
        File stale = new File(env.files, "rootfs.new");
        if (stale.exists()) {
            log.line("[FS] Removing incomplete staging tree…");
            deleteRecursive(stale);
        }

        List<RemoteArchive> parts = RootfsSource.resolve(arch, prefs.rootfsUrl(), log);
        File staging = new File(env.files, "rootfs.new");

        Env.mkdirs(staging);

        try {
            for (int i = 0; i < parts.size(); i++) {
                RemoteArchive part = parts.get(i);
                String ext = part.gzip ? ".tar.gz" : ".tar.xz";
                File archive = new File(env.cache, "rootfs-" + arch + "-" + i + ext);

                log.line("[NET] " + part.label + " — downloading…");
                Downloader.download(part, archive, log);

                if (part.sha256 != null || part.sha512 != null) {
                    log.line("[NET] Verifying checksum…");
                    boolean ok = part.sha256 != null
                            ? part.sha256.equalsIgnoreCase(digest(archive, "SHA-256"))
                            : part.sha512.equalsIgnoreCase(digest(archive, "SHA-512"));
                    if (!ok) {
                        //noinspection ResultOfMethodCallIgnored
                        archive.delete();
                        throw new IOException("Checksum mismatch for " + part.label);
                    }
                }

                log.line("[FS] Extracting RootFS (this can take a minute)…");
                TarExtract.extract(archive, part.gzip, staging, "rootfs", log);
                //noinspection ResultOfMethodCallIgnored
                archive.delete();
            }

            // A tarball that is not a rootfs (for example the disk-image tarballs
            // published for cloud providers) would otherwise produce a "successful"
            // install of an empty tree.
            if (!new File(staging, "bin/bash").isFile()
                    && !new File(staging, "usr/bin/bash").isFile()) {
                throw new IOException("The downloaded archive does not contain a Debian rootfs"
                        + " (no /bin/bash). Check the rootfs source in Settings.");
            }
        } catch (Exception e) {
            // Never leave a partial tree behind: it can be several GB.
            deleteRecursive(staging);
            throw e;
        }

        log.line("[FS] Preparing mounts and DNS inside rootfs…");
        postProcess(staging);

        activate(env, staging, parts.get(0).label, log);
        return true;
    }

    /**
     * Moves {@code staging} (a fully extracted tree) into place as the new
     * rootfs. Swaps by rename: deleting the old tree first can leave a non-empty
     * directory behind (unreadable entries, absolute symlinks) and then the
     * rename fails with ENOTEMPTY.
     */
    static void activate(Env env, File staging, String label, Log log) throws IOException {
        log.line("[FS] Activating new rootfs…");
        File retired = new File(env.files, "rootfs.old");
        deleteRecursive(retired);
        if (env.rootfs.exists() && !env.rootfs.renameTo(retired)) {
            deleteRecursive(env.rootfs);
        }
        if (!staging.renameTo(env.rootfs)) {
            throw new IOException("Cannot activate " + staging + " -> " + env.rootfs);
        }
        writeMarker(env.rootfs, label);
        deleteRecursive(retired);
        log.line("[FS] RootFS ready.");
    }

    static String digest(File file, String algorithm) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algorithm);
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    static void postProcess(File root) throws IOException {
        File etc = new File(root, "etc");
        Env.mkdirs(etc);
        write(new File(etc, "resolv.conf"),
                "nameserver 8.8.8.8\n"
                        + "nameserver 1.1.1.1\n"
                        + "nameserver 9.9.9.9\n");
        write(new File(etc, "hosts"),
                "127.0.0.1 localhost hermes\n"
                        + "::1 localhost ip6-localhost\n");
        write(new File(etc, "hostname"), "hermes\n");

        Env.mkdirs(new File(root, "home/hermes/.config/hermes"));
        Env.mkdirs(new File(root, "home/hermes/.local/state/hermes"));
        Env.mkdirs(new File(root, "home/hermes/.hermes/skills"));
        Env.mkdirs(new File(root, "home/hermes/.hermes/data"));
        Env.mkdirs(new File(root, "opt/hermes"));
        Env.mkdirs(new File(root, "opt/loader"));
        Env.mkdirs(new File(root, "dev"));
        Env.mkdirs(new File(root, "proc"));
        Env.mkdirs(new File(root, "sys"));
        Env.mkdirs(new File(root, "root"));
        Env.mkdirs(new File(root, "run"));
        Env.mkdirs(new File(root, "tmp"));
        Env.mkdirs(new File(root, "var/tmp"));

        chmod(new File(root, "tmp"), 01777);
        chmod(new File(root, "var/tmp"), 01777);

        File profileD = new File(root, "etc/profile.d");
        Env.mkdirs(profileD);
        write(new File(profileD, "hermes.sh"),
                "export HERMES_HOME=/home/hermes/.hermes\n"
                        + "export PATH=/opt/hermes/.venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n"
                        + "export LANG=C.UTF-8\n"
                        + "export TERM=${TERM:-xterm-256color}\n"
                        // chroot-ng emulates link() with symlinks + ".l2s." markers,
                        // which uv's cache cannot survive. Copy, always.
                        + "export UV_LINK_MODE=copy\n"
                        + "export UV_CACHE_DIR=/tmp/uv-cache\n"
                        + "export UV_NO_MODIFY_PATH=1\n"
                        // The guest always runs as fake root (--fake-id=0:0), and
                        // Hermes refuses to start its gateway as root unless
                        // this is set. There is no non-root run to protect.
                        + "export HERMES_ALLOW_ROOT_GATEWAY=1\n");
    }

    private static void writeMarker(File root, String source) throws IOException {
        write(new File(root, Env.ROOTFS_MARKER),
                "source=" + source + "\n"
                        + "installed=" + System.currentTimeMillis() + "\n"
                        + "arch=" + Env.debianArch() + "\n");
    }

    private static void write(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) Env.mkdirs(parent);
        // Replace whatever sits there: a stale symlink would redirect the write
        // outside the rootfs and break the later file bind-mount.
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        chmod(file, 0644);
    }

    private static void chmod(File f, int mode) {
        try {
            Os.chmod(f.getAbsolutePath(), mode);
        } catch (ErrnoException | RuntimeException ignored) {
            // best effort
        }
    }

    /**
     * Deletes a tree without ever following a symlink. Debian's rootfs contains
     * links such as {@code var/run -> /run}; a naive recursive delete would walk
     * into the device's real filesystem.
     */
    public static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory() && !isSymlink(f)) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** {@code Os.readlink} only succeeds on links, so an error means "not a link". */
    private static boolean isSymlink(File f) {
        try {
            Os.readlink(f.getAbsolutePath());
            return true;
        } catch (ErrnoException | RuntimeException e) {
            return false;
        }
    }
}

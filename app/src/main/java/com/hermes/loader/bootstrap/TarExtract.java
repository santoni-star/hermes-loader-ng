package com.hermes.loader.bootstrap;

import com.hermes.loader.Env;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;

import android.system.ErrnoException;
import android.system.Os;

/**
 * Extracts a rootfs tarball (gzip or xz) straight into the app's private
 * storage.
 *
 * <p>Ownership is intentionally not preserved ({@code --no-same-owner} in the
 * classic tar world) — an unrooted Android app cannot chown anyway, and PRoot
 * fakes the ownership inside the container. Permissions *are* preserved,
 * otherwise /bin/bash would not be executable.
 */
public final class TarExtract {

    /**
     * A rootfs never contains a file this large. Cloud-provider tarballs wrap a
     * raw disk image, whose single member would be written out byte by byte for
     * many minutes and look exactly like a frozen app.
     */
    private static final long MAX_ENTRY_BYTES = 1L << 30; // 1 GiB
    /** Report progress at least this often, so extraction never looks stuck. */
    private static final long PROGRESS_STEP = 32L << 20;  // 32 MiB

    private TarExtract() {
    }

    /**
     * @param label what is being unpacked ({@code rootfs}, {@code agent}), so the
     *              log line does not claim every archive is a rootfs.
     */
    public static void extract(File archive, boolean gzip, File destDir, String label, Log log)
            throws IOException {
        Env.mkdirs(destDir);
        String root = destDir.getAbsolutePath();
        int files = 0;
        int links = 0;

        try (InputStream fin = new BufferedInputStream(new FileInputStream(archive), 64 * 1024)) {
            InputStream decompressed = gzip
                    ? new GZIPInputStream(fin, 64 * 1024)
                    : new XZInputStream(fin, 64 * 1024);
            try (TarArchiveInputStream tar = new TarArchiveInputStream(decompressed)) {
                TarArchiveEntry entry;
                byte[] buf = new byte[64 * 1024];
                long entries = 0;
                long written = 0;
                long nextReport = PROGRESS_STEP;
                while ((entry = tar.getNextEntry()) != null) {
                    entries++;
                    String name = sanitize(entry.getName());
                    if (name == null || name.isEmpty()) {
                        continue;
                    }
                    File out = new File(root, name);
                    if (!out.getAbsolutePath().startsWith(root)) {
                        throw new IOException("Archive entry escapes rootfs: " + entry.getName());
                    }

                    if (entry.isDirectory()) {
                        Env.mkdirs(out);
                        chmod(out, entry.getMode() | 0700);
                        continue;
                    }

                    File parent = out.getParentFile();
                    if (parent != null) {
                        Env.mkdirs(parent);
                    }
                    deleteQuietly(out);

                    if (entry.isSymbolicLink()) {
                        symlink(entry.getLinkName(), out);
                        links++;
                    } else if (entry.isLink()) {
                        String target = sanitize(entry.getLinkName());
                        if (target != null) {
                            hardlink(new File(root, target), out);
                            links++;
                        }
                    } else if (entry.isFile()) {
                        long size = entry.getSize();
                        if (size > MAX_ENTRY_BYTES) {
                            throw new IOException("Archive member " + entry.getName()
                                    + " is " + (size >> 20) + " MiB — this looks like a raw"
                                    + " disk image, not a Debian rootfs.");
                        }
                        try (OutputStream os = new FileOutputStream(out)) {
                            int n;
                            while ((n = tar.read(buf)) != -1) {
                                os.write(buf, 0, n);
                                written += n;
                                if (written >= nextReport) {
                                    nextReport = written + PROGRESS_STEP;
                                    log.raw("\r[FS] extracting… "
                                            + (written >> 20) + " MB written");
                                }
                            }
                        }
                        chmod(out, entry.getMode() | 0600);
                        files++;
                    } else {
                        // Block/char devices and FIFOs cannot exist in app storage.
                        deleteQuietly(out);
                    }
                }
            }
        }

        log.line("");
        log.line("[FS] Extracted " + files + " files and " + links + " links into " + label + ".");
    }

    private static String sanitize(String name) {
        if (name == null) return null;
        String n = name.replace('\\', '/');
        while (n.startsWith("./")) n = n.substring(2);
        if (n.startsWith("/")) n = n.substring(1);
        if (n.isEmpty() || n.equals(".")) return "";
        if (n.contains("../")) return null;
        if (n.endsWith("/")) n = n.substring(0, n.length() - 1);
        return n;
    }

    private static void chmod(File f, int mode) {
        try {
            Os.chmod(f.getAbsolutePath(), mode & 0777);
        } catch (ErrnoException | RuntimeException ignored) {
            // Not fatal: many files work fine with the default 0600/0700.
        }
    }

    private static void symlink(String target, File link) throws IOException {
        try {
            Os.symlink(target, link.getAbsolutePath());
        } catch (ErrnoException e) {
            throw new IOException("symlink " + link + " -> " + target, e);
        }
    }

    private static void hardlink(File target, File link) {
        try {
            Os.link(target.getAbsolutePath(), link.getAbsolutePath());
        } catch (ErrnoException | RuntimeException e) {
            // Fall back to a copy when the kernel/filesystem refuses the link.
            try {
                copyFile(target, link);
            } catch (IOException ignored) {
                // Nothing else we can do; the file simply stays missing.
            }
        }
    }

    private static void copyFile(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
        chmod(to, 0644);
    }

    private static void deleteQuietly(File f) {
        if (f.exists() && !f.delete()) {
            // ignore — extraction will report the real error if it matters
        }
    }
}

package com.hermes.loader;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.IOException;

/**
 * Every path the loader cares about, in one place.
 *
 * <p>The layout mirrors the persistence contract from the specification:
 *
 * <pre>
 * files/
 *   rootfs/            Linux userland — disposable, re-downloadable
 *   storage/           NEVER deleted by updates
 *     config/          -&gt; /home/hermes/.config/hermes
 *     history/         -&gt; /home/hermes/.local/state/hermes
 *     skills/          -&gt; /home/hermes/.hermes/skills
 *     db/              -&gt; /home/hermes/.hermes/data
 *     hermes/          -&gt; /home/hermes/.hermes   (config.yaml, sessions, plugins, cron)
 *     home/            -&gt; /home/hermes
 *     agent/           -&gt; /opt/hermes           (git checkout + venv)
 *     android/         -&gt; /mnt/android         (copies of phone folders picked with SAF)
 *     sync/            import/export bookkeeping, one JSON per folder
 *     etc/             resolv.conf, hosts
 *     tmp/             -&gt; /tmp
 *   bin/               proot + loader
 *   scripts/           init.sh / setup.sh / run.sh (bind-mounted to /opt/loader)
 *   webdist/           pre-built dashboard SPA (bind-mounted to /opt/webdist)
 *   cache/             downloaded archives
 * </pre>
 */
public final class Env {

    /**
     * Marker written into the rootfs by {@link RootfsInstaller}. The distro name
     * is part of the file name on purpose: a loader that switches to a different
     * userland must not reuse a tree installed by an older build.
     */
    public static final String ROOTFS_MARKER = ".hermes-rootfs-trixie";
    public static final String AGENT_MARKER = ".hermes-agent";

    private static volatile Env instance;

    public final File files;
    public final File storage;
    public final File rootfs;
    public final File bin;
    public final File libexec;
    public final File cache;
    public final File tmp;
    public final File scripts;
    /** Read-only APK-owned directory; the only place we may exec from. */
    public final File nativeLib;

    /** Persistent user data. */
    public final File config;
    public final File history;
    public final File skills;
    public final File db;
    public final File home;
    public final File hermesHome;
    public final File agent;
    public final File storageEtc;
    public final File storageTmp;
    public final File logs;
    /** Pre-built dashboard SPA, bind-mounted at {@code /opt/webdist}. */
    public final File webdist;
    /** Copies of the phone folders picked with SAF, bind-mounted at {@code /home/hermes/phone}. */
    public final File androidRoot;
    /** Per-folder record of the last import/export ({@link Mirror}). */
    public final File syncDir;

    private Env(File files, File nativeLib) {
        this.files = files;
        this.nativeLib = nativeLib;
        this.storage = new File(files, "storage");
        this.rootfs = new File(files, "rootfs");
        this.bin = new File(files, "bin");
        this.libexec = new File(files, "libexec/proot");
        this.cache = new File(files, "cache");
        this.tmp = new File(files, "tmp");
        this.scripts = new File(files, "scripts");

        this.config = new File(storage, "config");
        this.history = new File(storage, "history");
        this.skills = new File(storage, "skills");
        this.db = new File(storage, "db");
        this.home = new File(storage, "home");
        this.hermesHome = new File(storage, "hermes");
        this.agent = new File(storage, "agent");
        this.storageEtc = new File(storage, "etc");
        this.storageTmp = new File(storage, "tmp");
        this.logs = new File(storage, "logs");
        this.webdist = new File(files, "webdist");
        this.androidRoot = new File(storage, "android");
        this.syncDir = new File(storage, "sync");
    }

    public static Env get(Context ctx) {
        Env local = instance;
        if (local == null) {
            synchronized (Env.class) {
                local = instance;
                if (local == null) {
                    Context app = ctx.getApplicationContext();
                    File files = app.getFilesDir();
                    local = new Env(files, new File(app.getApplicationInfo().nativeLibraryDir));
                    instance = local;
                }
            }
        }
        return local;
    }

    public static void forget() {
        instance = null;
    }

    /** Creates every directory that must exist before PRoot starts. */
    public void ensure() throws IOException {
        File[] dirs = {
                storage, rootfs, bin, libexec, cache, tmp, scripts,
                config, history, skills, db, home, hermesHome, agent,
                storageEtc, storageTmp, logs, webdist, androidRoot, syncDir,
                new File(home, ".config"),
                new File(home, "phone"),
                new File(hermesHome, "skills"),
                new File(hermesHome, "data"),
        };
        for (File dir : dirs) {
            mkdirs(dir);
        }
    }

    /** Local copy of one SAF folder; the guest sees it at {@code /mnt/android/<label>}. */
    public File mirrorDir(String label) {
        return new File(androidRoot, label);
    }

    /** Import/export bookkeeping for one folder. */
    public File syncState(String label) {
        return new File(syncDir, label + ".json");
    }

    public boolean rootfsInstalled() {
        return new File(rootfs, "bin/bash").isFile()
                && new File(rootfs, ROOTFS_MARKER).isFile();
    }

    public boolean agentInstalled() {
        return new File(agent, ".venv/bin/hermes").isFile()
                || new File(agent, AGENT_MARKER).isFile();
    }

    /** The {@code chroot-ng} binary that replaces PRoot. */
    public File guestBinary() {
        File lib = new File(nativeLib, "libchrootng.so");
        return lib.isFile() ? lib : new File(bin, "chroot-ng");
    }

    /** Maps the device ABI to the asset folder/asset name we ship. */
    public static String abiDir() {
        for (String abi : Build.SUPPORTED_ABIS) {
            switch (abi) {
                case "arm64-v8a":
                case "armeabi-v7a":
                case "x86_64":
                    return abi;
                default:
                    if (abi.startsWith("arm64")) return "arm64-v8a";
                    break;
            }
        }
        return "";
    }

    /** Debian port name for the current ABI. */
    public static String debianArch() {
        switch (abiDir()) {
            case "arm64-v8a":
                return "arm64";
            case "armeabi-v7a":
                return "armhf";
            case "x86_64":
                return "amd64";
            default:
                return "";
        }
    }

    public static void mkdirs(File dir) throws IOException {
        if (dir.isDirectory()) return;
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Cannot create directory: " + dir);
        }
    }
}

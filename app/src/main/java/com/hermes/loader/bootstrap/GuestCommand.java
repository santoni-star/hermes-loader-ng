package com.hermes.loader.bootstrap;

import com.hermes.loader.Env;
import com.hermes.loader.Prefs;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@code chroot-ng} command line.
 *
 * <p>{@code chroot-ng} replaces PRoot: it emulates {@code chroot} and
 * {@code mount --bind} without {@code ptrace}, so guest syscalls are not
 * trapped one by one. The bind list is the same contract as before —
 * everything valuable lives in {@code files/storage/} on the Android side and
 * appears inside the guest at the paths Hermes expects, while the rootfs itself
 * stays disposable.
 *
 * <p>Differences from the PRoot argv that used to be built here:
 * <ul>
 *   <li>{@code -0} became {@code --fake-id=0:0};</li>
 *   <li>{@code --link2symlink} became {@code --link2symlink};</li>
 *   <li>{@code -r <rootfs>} became the positional {@code <rootfs>};</li>
 *   <li>{@code /proc} and {@code /dev} are not bound at all: chroot-ng
 *       synthesizes a guest-scoped view of both;</li>
 *   <li>{@code --kill-on-exit} has no counterpart; the app kills the session
 *       process group itself (see {@code Session.sweep}).</li>
 * </ul>
 */
public final class GuestCommand {

    public static final String MODE_INSTALL = "install";
    public static final String MODE_BOOT = "boot";
    /** Fast console startup: skips banner/storage/prepare, goes straight to run.sh. */
    public static final String MODE_BOOT_FAST = "boot-fast";
    public static final String MODE_SHELL = "shell";
    public static final String MODE_SETUP = "setup";
    public static final String MODE_UPDATE = "update";
    /**
     * Dashboard as the session itself (no console). Still supported by the
     * scripts, but the app no longer starts it: it leaves TERM with nothing but
     * a server log, so WEB launches the console and {@link #MODE_WEB_AUX}
     * instead (see {@code MainActivity.openDashboard}).
     */
    public static final String MODE_WEB = "web";
    /** Fast dashboard startup: skips banner/storage/prepare, goes straight to run.sh web. */
    public static final String MODE_WEB_FAST = "web-fast";
    /** @deprecated Use {@link #MODE_WEB_FAST}. Kept for source compatibility. */
    @Deprecated
    public static final String MODE_DASHBOARD_FAST = MODE_WEB_FAST;
    /** Dashboard that runs next to a live console session (see {@link AuxDashboard}). */
    public static final String MODE_WEB_AUX = "webaux";
    /** Auxiliary dashboard when the live console has already confirmed its gateway. */
    public static final String MODE_WEB_AUX_READY = "webaux-ready";
    public static final String MODE_LOGIN = "login";

    /** Loopback port the Hermes dashboard listens on. */
    public static final int WEB_PORT = 9119;

    public static final class Spec {
        public final List<String> argv;
        public final Map<String, String> env;

        Spec(List<String> argv, Map<String, String> env) {
            this.argv = argv;
            this.env = env;
        }
    }

    private GuestCommand() {
    }

    public static Spec build(Env env, Prefs prefs, String mode, boolean pty, int columns, int rows)
            throws Exception {
        File chrootNg = env.guestBinary();
        if (!chrootNg.isFile()) {
            throw new IllegalStateException("chroot-ng binary missing: " + chrootNg);
        }

        List<String> a = new ArrayList<>();
        a.add(chrootNg.getAbsolutePath());
        // Fake root identity, as PRoot's -0 used to do.
        a.add("--fake-id=0:0");
        // dpkg relies on hard links (perlthanks -> perl, status-old), and
        // Android refuses link() inside app storage with EACCES. chroot-ng
        // emulates them as symlinks with copy-on-write on first write.
        a.add("--link2symlink");
        // The console and the auxiliary dashboard are separate invocations of
        // the same rootfs; sharing the process view makes `ps` in one session
        // see the gateway started by the other, like PRoot did.
        a.add("--shared-proc");
        if (prefs.compatMode()) {
            // Fallback for devices where the seccomp/SIGSYS tier misbehaves:
            // rewrite every syscall site ahead of time instead.
            a.add("-R");
        }
        a.add("-w");
        a.add("/home/hermes");

        // Kernel filesystems: /proc and /dev are synthesized by chroot-ng, but
        // /sys still has to come from the host.
        a.add("-b");
        a.add("/sys:/sys");

        // Persistent state (never inside the disposable rootfs).
        bind(a, env.home, "/home/hermes");
        bind(a, env.hermesHome, "/home/hermes/.hermes");
        bind(a, env.skills, "/home/hermes/.hermes/skills");
        bind(a, env.db, "/home/hermes/.hermes/data");
        bind(a, env.config, "/home/hermes/.config/hermes");
        bind(a, env.history, "/home/hermes/.local/state/hermes");
        bind(a, env.agent, "/opt/hermes");
        bind(a, env.scripts, "/opt/loader");
        bind(a, env.webdist, "/opt/webdist");
        bind(a, env.storageTmp, "/tmp");

        // Phone folders imported through SAF. SAF gives the app content:// URIs,
        // not paths, so the guest cannot mount them: the app keeps a copy in
        // storage/android and this bind exposes it. init.sh links the same
        // directory as /mnt/android and ~/projects/<label>.
        bind(a, env.androidRoot, "/home/hermes/phone");

        // Network configuration provided by the app, not by netd.
        bind(a, new File(env.storageEtc, "resolv.conf"), "/etc/resolv.conf");
        bind(a, new File(env.storageEtc, "hosts"), "/etc/hosts");
        bind(a, new File(env.storageEtc, "hostname"), "/etc/hostname");

        // The rootfs is the first positional argument.
        a.add(env.rootfs.getAbsolutePath());
        a.add("/usr/bin/env");
        a.add("-i");
        a.add("HOME=/home/hermes");
        // The fake identity is uid 0; matching USER to it keeps tools that
        // cross-check $USER against getuid() happy. Files stay owned by the
        // Android app uid either way.
        a.add("USER=root");
        a.add("LOGNAME=root");
        a.add("SHELL=/bin/bash");
        a.add("TERM=xterm-256color");
        a.add("LANG=C.UTF-8");
        a.add("LC_ALL=C.UTF-8");
        a.add("TMPDIR=/tmp");
        a.add("PATH=/opt/hermes/.venv/bin:/opt/hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        a.add("HERMES_HOME=/home/hermes/.hermes");
        a.add("LOADER_MODE=" + mode);
        a.add("LOADER_REPO=" + prefs.repo());
        a.add("LOADER_BRANCH=" + prefs.branch());
        a.add("LOADER_PROFILE=" + prefs.profile());
        a.add("LOADER_GATEWAY_PREWARM=" + (prefs.gatewayPrewarm() ? "1" : "0"));
        a.add("LOADER_COLUMNS=" + columns);
        a.add("LOADER_ROWS=" + rows);
        // Fallbacks for programs that read the environment instead of doing
        // an ioctl on the PTY (rich, some progress bars).
        a.add("COLUMNS=" + columns);
        a.add("LINES=" + rows);
        // uv (and pip) hardlink from their cache by default; the emulated
        // link() leaks marker files into the package contents. Copying is
        // slower but correct. The cache stays on the /tmp bind so the
        // installer can reclaim it afterwards.
        a.add("UV_LINK_MODE=copy");
        a.add("UV_CACHE_DIR=/tmp/uv-cache");
        a.add("UV_NO_MODIFY_PATH=1");
        // The fake identity is root; Hermes refuses to start its gateway as
        // root unless this is set, and the risk it guards against (root-owned
        // files in HERMES_HOME blocking a later non-root run) cannot happen
        // here — the app always runs as the same fake root.
        a.add("HERMES_ALLOW_ROOT_GATEWAY=1");
        // getuid() is faked as 0, but SO_PEERCRED on the gateway's control
        // socket reports the real Android app uid, so Hermes would refuse every
        // local client ("unsafe_control_peer"). The guest patches that one
        // check to trust the container's own uid; nothing else reads this.
        a.add("LOADER_FAKE_ROOT=1");
        // `hermes dashboard` must not try to compile the SPA with npm on the
        // phone: the APK ships a pre-built bundle, which the server serves
        // directly when this variable points at it.
        a.add("HERMES_WEB_DIST=/opt/webdist");
        a.add("LOADER_WEB_PORT=" + WEB_PORT);
        // Loopback unless the user asked for access from other devices; the
        // dashboard then demands the login from WebAuth.
        a.add("LOADER_WEB_HOST=" + prefs.webHost());
        // Where imported phone folders live; init.sh also links /mnt/android.
        a.add("LOADER_ANDROID=/home/hermes/phone");
        a.add("PYTHONUNBUFFERED=1");
        a.add("PYTHONDONTWRITEBYTECODE=1");

        String guest = "/bin/bash /opt/loader/init.sh " + mode;
        // Fast modes: no PTY, no script wrapper, direct init.sh entrypoint.
        // They skip banner, ensure_storage, prepare_guest for faster startup.
        boolean isFastMode = MODE_BOOT_FAST.equals(mode) || MODE_WEB_FAST.equals(mode);
        if (isFastMode) {
            a.add("/bin/bash");
            a.add("/opt/loader/init.sh");
            a.add(mode);
        } else if (pty && scriptAvailable(env)) {
            a.add("/usr/bin/script");
            a.add("-q");
            a.add("-f");
            a.add("-e");
            a.add("-c");
            a.add(guest);
            a.add("/dev/null");
        } else {
            a.add("/bin/bash");
            a.add("/opt/loader/init.sh");
            a.add(mode);
        }

        Map<String, String> envp = new LinkedHashMap<>();
        // chroot-ng keeps its broker socket under TMPDIR; point it at the app
        // sandbox instead of Android's shared /tmp.
        envp.put("TMPDIR", env.tmp.getAbsolutePath());
        envp.put("HOME", env.home.getAbsolutePath());
        envp.put("PATH", "/system/bin:/system/xbin");
        for (String key : new String[]{"ANDROID_ROOT", "ANDROID_DATA", "ANDROID_STORAGE",
                "EXTERNAL_STORAGE", "BOOTCLASSPATH"}) {
            String v = System.getenv(key);
            if (v != null) envp.put(key, v);
        }
        return new Spec(a, envp);
    }

    /** {@code script(1)} gives the guest a real PTY; it arrives with util-linux. */
    public static boolean scriptAvailable(Env env) {
        return new File(env.rootfs, "usr/bin/script").isFile()
                || new File(env.rootfs, "bin/script").isFile();
    }

    private static void bind(List<String> a, File host, String guest) {
        a.add("-b");
        a.add(host.getAbsolutePath() + ":" + guest);
    }
}

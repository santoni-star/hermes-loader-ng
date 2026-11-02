package com.hermes.loader;

import android.content.Context;

import com.hermes.loader.bootstrap.BundleInstaller;
import com.hermes.loader.bootstrap.Log;
import com.hermes.loader.bootstrap.NetFiles;
import com.hermes.loader.bootstrap.GuestCommand;
import com.hermes.loader.bootstrap.NgInstaller;
import com.hermes.loader.bootstrap.RootfsInstaller;
import com.hermes.loader.bootstrap.Scripts;
import com.hermes.loader.bootstrap.WebAssets;
import com.hermes.loader.bootstrap.WebAuth;

import java.io.IOException;

/**
 * Orchestrates the parts that need Android APIs (assets, downloads, files) and
 * then hands control to the scripts inside the container.
 */
public final class Launcher {

    public interface Ui {
        void line(String text);

        void raw(String text);

        /** Lock the action buttons while a long preparation step runs. */
        void busy(boolean busy);

        /** Short human-readable stage name for the header (null = idle). */
        default void status(String text) {
        }
    }

    private Launcher() {
    }

    public static void start(Context ctx, Prefs prefs, String mode, Ui ui) {
        Context app = ctx.getApplicationContext();
        Thread t = new Thread(() -> run(app, prefs, mode, ui), "loader-bootstrap");
        t.setDaemon(true);
        t.start();
    }

    @android.annotation.SuppressLint("UsableSpace")
    private static void run(Context app, Prefs prefs, String mode, Ui ui) {
        Log log = new Log() {
            @Override
            public void line(String text) {
                ui.line(text);
            }

            @Override
            public void raw(String text) {
                ui.raw(text);
            }
        };

        ui.busy(true);
        final long prepStart = android.os.SystemClock.elapsedRealtime();
        try {
            if (!GuestCommand.MODE_WEB.equals(mode)) {
                // Clear stale guest processes before touching the rootfs, but
                // keep the TERM/grace/KILL sweep off the UI thread. Cold WEB
                // startup used to run this same sweep synchronously in the
                // click handler and then repeat it again here.
                ui.status("cleaning up");
                Session.killLeftovers();
            }
            if (Env.abiDir().isEmpty()) {
                String abis = android.text.TextUtils.join(", ", android.os.Build.SUPPORTED_ABIS);
                throw new IOException(app.getString(R.string.err_not_arm, abis));
            }
            Env env = Env.get(app);
            env.ensure();

            log.line("");
            log.line("\u001b[1;32m[Hermes Loader]\u001b[0m mode=" + mode
                    + "  abi=" + Env.abiDir()
                    + "  android=" + android.os.Build.VERSION.RELEASE);
            log.line("[INIT] Storage: " + env.storage);
            log.line("[INIT] Rootfs:  " + env.rootfs);
            long freeMb = env.files.getUsableSpace() >> 20;
            log.line("[DISK] " + freeMb + " MB free in app storage.");
            if (freeMb > 0 && freeMb < 400) {
                log.line("\u001b[1;33m[DISK]\u001b[0m Low space: the agent's database and logs"
                        + " need room to write. Free some space before the first chat.");
            }

            ui.status("chroot-ng");
            NgInstaller.ensure(env, log);
            Scripts.sync(app, env);
            NetFiles.write(app, env);
            // Rewritten on every start so a password change is picked up and a
            // dashboard switched back to loopback keeps no credentials on disk.
            WebAuth.write(env, prefs);

            ui.status("rootfs");
            boolean fromBundle = false;
            if (!env.rootfsInstalled() && prefs.useBundle()) {
                ui.status("prebuilt rootfs");
                try {
                    fromBundle = BundleInstaller.installRootfs(env, prefs, log);
                } catch (Throwable e) {
                    // A bundle is a shortcut, never a requirement: fall through
                    // to the classic download instead of failing the start.
                    log.line("\u001b[1;33m[BUNDLE]\u001b[0m Prebuilt rootfs unavailable ("
                            + describe(e) + ") — using the normal installer.");
                }
            }
            boolean fresh = false;
            if (!env.rootfsInstalled()) {
                fresh = RootfsInstaller.ensure(app, env, prefs, log);
            }
            if (fresh) {
                log.line("[INIT] Fresh rootfs deployed. Your storage/ was not touched.");
            }
            if (fromBundle) {
                log.line("[INIT] Rootfs came from a prebuilt bundle.");
            }

            if (GuestCommand.MODE_WEB.equals(mode)
                    || GuestCommand.MODE_WEB_FAST.equals(mode)
                    || GuestCommand.MODE_DASHBOARD_FAST.equals(mode)) {
                ui.status("web UI");
                WebAssets.sync(app, env);
                log.line("[WEB] Dashboard bundle: " + env.webdist
                        + " → http://127.0.0.1:" + GuestCommand.WEB_PORT + "/");
            }

            if (!env.agentInstalled() && !GuestCommand.MODE_SHELL.equals(mode)
                    && !GuestCommand.MODE_INSTALL.equals(mode)) {
                boolean fromAgentBundle = false;
                if (prefs.useBundle()) {
                    ui.status("prebuilt agent");
                    try {
                        fromAgentBundle = BundleInstaller.installAgent(env, prefs, log);
                    } catch (Throwable e) {
                        log.line("\u001b[1;33m[BUNDLE]\u001b[0m Prebuilt agent unavailable ("
                                + describe(e) + ") — building it on the phone instead.");
                    }
                }
                if (!env.agentInstalled()) {
                    log.line("[INIT] First run: installing packages and Hermes agent…");
                    ui.status("installing");
                    if (!runBlocking(app, env, prefs, GuestCommand.MODE_INSTALL, false, log, ui)) {
                        throw new IOException("Installation failed. See the log above.");
                    }
                    if (!env.agentInstalled()) {
                        throw new IOException("Hermes was not installed correctly"
                                + " (no .venv/bin/hermes).");
                    }
                } else if (fromAgentBundle) {
                    log.line("[INIT] Agent came from a prebuilt bundle.");
                }
            }

            if (!new java.io.File(env.hermesHome, "config.yaml").isFile()
                    && (GuestCommand.MODE_BOOT.equals(mode)
                    || GuestCommand.MODE_BOOT_FAST.equals(mode)
                    || GuestCommand.MODE_WEB.equals(mode)
                    || GuestCommand.MODE_WEB_FAST.equals(mode))) {
                // Nothing is seeded on purpose: the agent's own setup decides the
                // provider, and a free Nous tier (when the account has one) is
                // picked up by the agent itself.
                log.line("[HINT] No provider is configured yet: START lets the agent ask,");
                log.line("[HINT]   or use \u22ee \u2192 Sign in to Nous to try the free tier.");
            }
            boolean dashboardFast = GuestCommand.MODE_DASHBOARD_FAST.equals(mode)
                    || GuestCommand.MODE_WEB_FAST.equals(mode);
            boolean pty = !dashboardFast && prefs.pty()
                    && GuestCommand.scriptAvailable(env);
            ui.status("starting");
            log.line("[TIME] Android prep " + (android.os.SystemClock.elapsedRealtime()
                    - prepStart) + " ms.");
            log.line("[EXEC] Starting " + mode + (pty ? " (pty)" : "") + "…");
            startSession(app, env, prefs, mode, pty, ui);
            RunnerService.start(app);

            // `script(1)` needs a working /dev/ptmx inside chroot-ng. If the wrapper
            // dies immediately we retry once over a plain pipe instead of
            // leaving the user with a dead terminal.
            if (pty && ptyLooksBroken(2500)) {
                log.line("\u001b[1;33m[PTY]\u001b[0m terminal wrapper failed — retrying without a PTY.");
                log.line("[HINT] You can turn PTY off permanently in Settings.");
                startSession(app, env, prefs, mode, false, ui);
                RunnerService.start(app);
            }
        } catch (Throwable e) {
            String msg = describe(e);
            log.line("");
            log.line("\u001b[1;31m[ERROR]\u001b[0m " + msg);
            if (msg.contains("error=13") || msg.toLowerCase(java.util.Locale.ROOT)
                    .contains("permission denied")) {
                log.line("[HINT] Android refused to execute the bundled chroot-ng binary.");
                log.line("[HINT] Reinstall the APK (native libs are extracted at install time),"
                        + " and do not move the app to adopted storage.");
            } else if (msg.contains("Function not implemented")) {
                log.line("[HINT] The kernel's seccomp filter blocked a chroot-ng syscall."
                        + " Enable compatibility mode in Settings, then retry.");
            } else if (msg.startsWith("Installation failed")) {
                log.line("[HINT] Packages already downloaded are kept in the rootfs, so"
                        + " pressing START again continues where it stopped.");
                log.line("[HINT] If dpkg keeps failing, use ⋮ → Settings → Reinstall Linux rootfs.");
            } else {
                log.line("[HINT] Open ⋮ → Settings to switch chroot-ng into compatibility"
                        + " mode or to reinstall the rootfs.");
            }
            RunnerService.stop(app);
        } finally {
            ui.busy(false);
        }
    }

    /**
     * Waits a few seconds after start-up and reports whether the guest process
     * died with an error — the usual symptom of a broken {@code script(1)} PTY.
     */
    private static boolean ptyLooksBroken(long waitMs) {
        long deadline = System.currentTimeMillis() + waitMs;
        while (Session.get().running() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !Session.get().running() && Session.get().lastExitCode() != 0;
    }

    /** Runs a non-interactive preparation step and waits for it to finish. */
    private static boolean runBlocking(Context app, Env env, Prefs prefs, String mode,
                                       boolean pty, Log log, Ui ui) throws Exception {
        startSession(app, env, prefs, mode, pty, ui);
        while (Session.get().running()) {
            Thread.sleep(400);
        }
        int code = Session.get().lastExitCode();
        if (code != 0) {
            log.line("\u001b[1;31m[ERROR]\u001b[0m step '" + mode + "' exited with code " + code + ".");
        }
        return code == 0;
    }

    private static void startSession(Context app, Env env, Prefs prefs, String mode,
                                     boolean pty, Ui ui) throws Exception {
        GuestCommand.Spec spec = GuestCommand.build(
                env, prefs, mode, pty, prefs.columns(), prefs.rows());
        Session.get().start(mode, pty, spec.argv, spec.env, env.files);
    }

    private static String describe(Throwable e) {
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) {
            msg = e.getClass().getSimpleName();
        }
        return msg;
    }
}

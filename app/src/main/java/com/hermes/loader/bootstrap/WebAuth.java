package com.hermes.loader.bootstrap;

import android.system.ErrnoException;
import android.system.Os;

import com.hermes.loader.Env;
import com.hermes.loader.Prefs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The dashboard login, handed to the guest as a file instead of environment
 * variables.
 *
 * <p>{@link GuestCommand} passes the guest environment through {@code env -i
 * NAME=value …}, which puts every value into PRoot's own command line. A
 * password there would show up in {@code ps} for as long as the dashboard runs.
 * The file is bind-mounted at {@value #GUEST_PATH} and sourced by {@code run.sh}
 * before the dashboard starts, so the secret stays on disk (app-private storage,
 * mode 0600) and out of any process listing.
 *
 * <p>Hermes reads {@code HERMES_DASHBOARD_BASIC_AUTH_*}, which wins over
 * {@code config.yaml}; nothing is written into the user's configuration.
 */
public final class WebAuth {

    /**
     * Where the file lands inside the container: next to the scripts, which are
     * already bind-mounted as a directory. Binding one extra file would need the
     * mount point to exist in the rootfs first; a file inside an existing
     * directory bind needs nothing.
     */
    public static final String GUEST_PATH = "/opt/loader/web-auth.env";

    private WebAuth() {
    }

    /**
     * Rewrites the file on every start: a password changed in Settings must not
     * wait for the next install, and a dashboard switched back to loopback must
     * not keep credentials on disk.
     */
    public static void write(Env env, Prefs prefs) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Written by Hermes Loader on every start — do not edit.\n");
        if (prefs.webNetwork() && prefs.webAuthReady()) {
            sb.append("export HERMES_DASHBOARD_BASIC_AUTH_USERNAME=")
                    .append(quote(prefs.webUser())).append('\n');
            sb.append("export HERMES_DASHBOARD_BASIC_AUTH_PASSWORD=")
                    .append(quote(prefs.webPassword())).append('\n');
            sb.append("export HERMES_DASHBOARD_BASIC_AUTH_SECRET=")
                    .append(quote(prefs.webSecret())).append('\n');
        }
        Env.mkdirs(env.scripts);
        File file = new File(env.scripts, "web-auth.env");
        // Drop a squatter (often a symlink) so the write cannot escape the tree.
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        try {
            Os.chmod(file.getAbsolutePath(), 0600);
        } catch (ErrnoException | RuntimeException ignored) {
            // Best effort: the parent directory is already app-private.
        }
    }

    /** A POSIX single-quoted string: the only quoting bash cannot expand. */
    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}

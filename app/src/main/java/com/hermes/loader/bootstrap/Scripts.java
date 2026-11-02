package com.hermes.loader.bootstrap;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.system.ErrnoException;
import android.system.Os;

import com.hermes.loader.Env;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Copies the shell scripts from {@code assets/scripts/} into
 * {@code files/scripts/} so they can be bind-mounted at {@code /opt/loader}.
 *
 * <p>Scripts are refreshed on every start, so shipping a new APK updates the
 * bootstrap logic immediately — no rootfs reinstall required.
 */
public final class Scripts {

    private static final String ASSET_DIR = "scripts";
    private static final String MARKER = ".hermes-scripts";

    private Scripts() {
    }

    public static synchronized void sync(Context ctx, Env env) throws IOException {
        AssetManager am = ctx.getAssets();
        String[] names = am.list(ASSET_DIR);
        if (names == null) names = new String[0];
        String current = installedApkStamp(ctx);
        if (isCurrent(env.scripts, names, current)) return;

        Env.mkdirs(env.scripts);
        File marker = new File(env.scripts, MARKER);
        // A crash during a refresh must not leave a matching marker that would
        // make the next launch trust a partially copied script set.
        //noinspection ResultOfMethodCallIgnored
        marker.delete();
        for (String name : names) {
            File dest = new File(env.scripts, name);
            try (InputStream in = am.open(ASSET_DIR + "/" + name);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            chmod(dest, 0755);
        }
        if (current != null) {
            try (OutputStream out = new FileOutputStream(marker)) {
                out.write(current.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        // Directories are not executable by PRoot without the permission bit.
        chmod(env.scripts, 0755);
    }

    private static boolean isCurrent(File dir, String[] names, String stamp) {
        if (stamp == null) return false;
        File marker = new File(dir, MARKER);
        if (!marker.isFile()) return false;
        for (String name : names) {
            if (!new File(dir, name).isFile()) return false;
        }
        try (InputStream in = new java.io.FileInputStream(marker)) {
            byte[] bytes = new byte[128];
            int n = in.read(bytes);
            return n >= 0 && stamp.equals(new String(bytes, 0, n,
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
    }

    /** Includes install time so same-version debug APK reinstalls refresh assets. */
    private static String installedApkStamp(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return info.versionCode + ":" + info.lastUpdateTime;
        } catch (Exception e) {
            // Losing the cache only costs a few small writes; it must not make
            // starting the container fail.
            return null;
        }
    }

    private static void chmod(File f, int mode) {
        try {
            Os.chmod(f.getAbsolutePath(), mode);
        } catch (ErrnoException | RuntimeException ignored) {
            // best effort
        }
    }
}

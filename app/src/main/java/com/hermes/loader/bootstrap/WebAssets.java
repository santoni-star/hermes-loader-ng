package com.hermes.loader.bootstrap;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;

import com.hermes.loader.Env;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Unpacks the dashboard SPA from {@code assets/web/} into {@code files/webdist/}.
 *
 * <p>{@code hermes dashboard} normally compiles the web UI with npm on first
 * launch. That needs Node and a few hundred megabytes of dependencies, which is
 * not something a phone should do. The APK therefore ships a pre-built bundle
 * and the guest is pointed at it with {@code HERMES_WEB_DIST}; the server is
 * started with {@code --skip-build}.
 *
 * <p>Files are refreshed when the APK version changes, so updating the app also
 * updates the UI without touching {@code storage/}.
 */
public final class WebAssets {

    private static final String ASSET_DIR = "web";
    private static final String MARKER = ".hermes-webdist";
    private static final String ENTRY = "index.html";

    private WebAssets() {
    }

    public static synchronized void sync(Context ctx, Env env) throws IOException {
        File dir = env.webdist;
        if (isCurrent(ctx, dir)) return;

        deleteRecursive(dir);
        Env.mkdirs(dir);
        copyDir(ctx.getAssets(), ASSET_DIR, dir);

        if (!new File(dir, ENTRY).isFile()) {
            throw new IOException("Dashboard assets are missing " + ENTRY + " in " + dir);
        }
        writeString(new File(dir, MARKER), String.valueOf(versionCode(ctx)));
    }

    /** True when the extracted bundle is complete and belongs to this APK. */
    public static boolean ready(Context ctx, Env env) {
        return new File(env.webdist, ENTRY).isFile() && isCurrent(ctx, env.webdist);
    }

    private static boolean isCurrent(Context ctx, File dir) {
        File marker = new File(dir, MARKER);
        return marker.isFile() && new File(dir, ENTRY).isFile()
                && String.valueOf(versionCode(ctx)).equals(readString(marker));
    }

    private static int versionCode(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return info.versionCode;
        } catch (Exception e) {
            return -1;
        }
    }

    private static void copyDir(AssetManager am, String path, File dest) throws IOException {
        String[] children = am.list(path);
        if (children == null || children.length == 0) {
            // A file: AssetManager.list() returns an empty array for both an
            // empty directory and a regular asset, so try to open it.
            copyFile(am, path, dest);
            return;
        }
        Env.mkdirs(dest);
        for (String child : children) {
            copyDir(am, path + "/" + child, new File(dest, child));
        }
    }

    private static void copyFile(AssetManager am, String path, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null) Env.mkdirs(parent);
        try (InputStream in = am.open(path);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    private static String readString(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] data = new byte[(int) Math.min(f.length(), 4096)];
            int off = 0;
            int n;
            while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) {
                off += n;
            }
            return new String(data, 0, off, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeString(File f, String value) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File kid : kids) deleteRecursive(kid);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}

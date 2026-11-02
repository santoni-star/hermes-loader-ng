package com.hermes.loader;

import android.content.Context;

import com.hermes.loader.bootstrap.GuestCommand;
import com.hermes.loader.bootstrap.WebAssets;
import com.hermes.loader.bootstrap.WebAuth;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The dashboard that runs <em>next to</em> a console session.
 *
 * <p>Hermes has one gateway daemon and any number of clients: the TUI and the
 * dashboard are both clients of it, and the dashboard does not need the console
 * to stop. Switching to WEB therefore starts a second, independent chroot-ng
 * instance ({@link GuestCommand#MODE_WEB_AUX}) instead of ending the session:
 * the agent keeps its state, the gateway keeps running, and TERM brings the
 * console back exactly where it was.
 *
 * <p>That instance is not a {@link Session}: it never reads the keyboard, so it
 * needs no PTY, and it must not be mistaken for "the" session (START stays
 * available, STOP stops both). Its output goes to
 * {@code ~/.hermes/cache/loader/webaux.log} instead of the terminal.
 */
public final class AuxDashboard {

    public interface Listener {
        /** Called on a background thread when the dashboard process ends. */
        void onExit(int code);
    }

    private static final AuxDashboard INSTANCE = new AuxDashboard();
    private static final int TAIL_LIMIT = 4000;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final StringBuilder tail = new StringBuilder();

    private Process process;
    private boolean stopping;
    private boolean ready;
    private int lastExit = -1;
    private long startedAt;
    private long startedElapsedRealtime;

    private AuxDashboard() {
    }

    public static AuxDashboard get() {
        return INSTANCE;
    }

    /**
     * Where the dashboard writes. The console belongs to the agent, so this is
     * the only output in the app that the terminal never shows; the LOG button
     * reads it from here.
     */
    public static File logFile(Env env) {
        return new File(new File(env.hermesHome, "cache/loader"), "webaux.log");
    }

    /**
     * The last {@code maxChars} characters of the log, for the LOG dialog.
     * Reading a file is disk I/O, so call it off the UI thread.
     */
    public static String readLog(Env env, int maxChars) throws IOException {
        File file = logFile(env);
        if (!file.exists()) return "";
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            long length = raf.length();
            int size = (int) Math.min(length, maxChars);
            byte[] buf = new byte[size];
            raf.seek(length - size);
            raf.readFully(buf);
            // Cutting at a byte boundary can leave a partial UTF-8 sequence at
            // the front; it renders as one replacement character, harmless.
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public synchronized boolean running() {
        return process != null;
    }

    public synchronized int lastExitCode() {
        return lastExit;
    }

    public synchronized long startedAt() {
        return startedAt;
    }

    public synchronized long startedElapsedRealtime() {
        return startedElapsedRealtime;
    }

    /**
     * The last few kilobytes the dashboard printed. Only used to explain a
     * failure in a toast; the complete log stays in the file.
     */
    public synchronized String tail() {
        return tail.toString();
    }

    /** True after this process reported that its HTTP server has bound. */
    public synchronized boolean ready() {
        return process != null && ready;
    }

    /** The last non-empty line, which is usually the reason a start failed. */
    public synchronized String lastLine() {
        String[] lines = tail.toString().split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].replaceAll("\u001b\\[[0-9;]*[A-Za-z]", "").trim();
            if (!line.isEmpty()) return line;
        }
        return "";
    }

    /**
     * Starts the dashboard. Blocking (assets, command line, process spawn), so
     * call it from a worker thread.
     */
    public synchronized void start(Context ctx, Prefs prefs) throws Exception {
        if (stopping) {
            throw new IllegalStateException("Dashboard is stopping");
        }
        if (process != null) return;

        Env env = Env.get(ctx);
        env.ensure();
        // Normally synced by the console's own start; a dashboard opened before
        // the first console start still needs the SPA on disk.
        WebAssets.sync(ctx, env);
        WebAuth.write(env, prefs);

        String mode = Session.get().gatewayReady()
                ? GuestCommand.MODE_WEB_AUX_READY : GuestCommand.MODE_WEB_AUX;
        GuestCommand.Spec spec = GuestCommand.build(env, prefs,
                mode, false, prefs.columns(), prefs.rows());

        File log = logFile(env);
        Env.mkdirs(log.getParentFile());

        ProcessBuilder pb = new ProcessBuilder(spec.argv);
        pb.directory(env.files);
        pb.redirectErrorStream(true);
        Map<String, String> penv = pb.environment();
        penv.clear();
        penv.putAll(spec.env);

        tail.setLength(0);
        ready = false;
        appendSeparator(log);

        Process p = pb.start();
        process = p;
        lastExit = -1;
        startedAt = System.currentTimeMillis();
        startedElapsedRealtime = android.os.SystemClock.elapsedRealtime();

        Thread pump = new Thread(() -> pump(p, log), "dashboard-session");
        pump.setDaemon(true);
        pump.start();
    }

    /** Ends the dashboard and waits for it; the console session is untouched. */
    public void stop() {
        Process p;
        synchronized (this) {
            while (stopping) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            stopping = true;
            p = process;
        }
        try {
            if (p == null) {
                Session.killDashboards();
                return;
            }
            p.destroy();
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline && alive(p)) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // The tracer can exit while its Python child survives. Do not let
            // a later start attach to that server with stale access settings.
            Session.killDashboards();
            if (alive(p)) Session.killForcibly(p);

            long exitDeadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < exitDeadline) {
                synchronized (this) {
                    if (process != p) break;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            synchronized (this) {
                stopping = false;
                notifyAll();
            }
        }
    }

    private void pump(Process p, File log) {
        try (InputStream in = p.getInputStream();
             OutputStream out = new FileOutputStream(log, true)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
                synchronized (this) {
                    tail.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    if (!ready && tail.indexOf("HERMES_DASHBOARD_READY") >= 0) {
                        ready = true;
                    }
                    int overflow = tail.length() - TAIL_LIMIT;
                    if (overflow > 0) tail.delete(0, overflow);
                }
            }
        } catch (IOException ignored) {
            // The process is going away; the exit code below says how.
        }

        int code = -1;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            if (process == p) {
                process = null;
                lastExit = code;
            }
        }
        for (Listener l : listeners) {
            l.onExit(code);
        }
    }

    private static void appendSeparator(File log) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date());
        try (OutputStream out = new FileOutputStream(log, true)) {
            out.write(("\n===== " + stamp + " webaux =====\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // A log we cannot write is not a reason to refuse to start.
        }
    }

    private static boolean alive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }
}

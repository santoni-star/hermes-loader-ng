package com.hermes.loader;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The single long-running guest process (PRoot -> bash -> Hermes).
 *
 * <p>Lives outside the Activity on purpose: the foreground service keeps it
 * alive while the app is in the background, and a re-created Activity can
 * re-attach and replay the buffered output.
 */
public final class Session {

    public interface Listener {
        void onOutput(String chunk);

        void onExit(int code);
    }

    private static final Session INSTANCE = new Session();
    private static final int BACKLOG_LIMIT = 200_000;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final StringBuilder backlog = new StringBuilder();

    private Process process;
    private OutputStream stdin;
    private String mode = "";
    private String lastMode = "";
    private boolean pty;
    private boolean stopRequested;
    private int lastExit = -1;

    private Session() {
    }

    public static Session get() {
        return INSTANCE;
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

    public synchronized String mode() {
        return mode;
    }

    public synchronized boolean pty() {
        return pty;
    }

    /** The mode of the session that just ended (for diagnostics after it is gone). */
    public synchronized String lastMode() {
        return lastMode;
    }

    public synchronized int lastExitCode() {
        return lastExit;
    }

    public synchronized String backlog() {
        return backlog.toString();
    }

    /**
     * True once the guest printed {@code text}. Used to wait for a boot stage
     * (for example the storage sweep in {@code init.sh}, which must be over
     * before a dashboard is started next to the console).
     */
    public synchronized boolean printed(String text) {
        return backlog.indexOf(text) >= 0;
    }

    /** True only while this live console has already observed its gateway start. */
    public synchronized boolean gatewayReady() {
        return process != null && backlog.indexOf("Gateway ready in") >= 0;
    }

    public synchronized void clearBacklog() {
        backlog.setLength(0);
    }

    public synchronized void start(String mode, boolean pty, List<String> argv,
                                   Map<String, String> env, File cwd) throws IOException {
        if (running()) {
            throw new IllegalStateException("A session is already running");
        }
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(cwd);
        pb.redirectErrorStream(true);
        Map<String, String> penv = pb.environment();
        penv.clear();
        penv.putAll(env);

        process = pb.start();
        stdin = process.getOutputStream();
        this.mode = mode;
        this.pty = pty;
        this.stopRequested = false;
        this.lastExit = -1;
        backlog.setLength(0);

        Thread pump = new Thread(this::pump, "hermes-session");
        pump.setDaemon(true);
        pump.start();
    }

    private void pump() {
        Process p = process;
        int code = -1;
        try (Reader reader = new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) != -1) {
                String chunk = new String(buf, 0, n);
                synchronized (this) {
                    backlog.append(chunk);
                    int overflow = backlog.length() - BACKLOG_LIMIT;
                    if (overflow > 0) {
                        backlog.delete(0, overflow);
                    }
                }
                for (Listener l : listeners) {
                    l.onOutput(chunk);
                }
            }
            code = p.waitFor();
        } catch (IOException e) {
            boolean expectedStop;
            synchronized (this) {
                expectedStop = stopRequested;
            }
            if (expectedStop) {
                // Closing a process stream during a user-requested stop can
                // interrupt the reader. It is an expected shutdown, not a
                // guest error to print into the terminal.
                code = 0;
            } else {
                for (Listener l : listeners) {
                    l.onOutput("\n[loader] stream error: " + e.getMessage() + "\n");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                if (stopRequested) code = 0;
                lastExit = code;
                lastMode = mode;
                process = null;
                stdin = null;
                mode = "";
                stopRequested = false;
            }
            for (Listener l : listeners) {
                l.onExit(code);
            }
        }
    }

    /** Sends raw text to the guest (no newline is appended). */
    public void write(String data) {
        OutputStream out;
        synchronized (this) {
            out = stdin;
        }
        if (out == null) return;
        try {
            out.write(data.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
            // The session is going away; the exit callback reports it.
        }
    }

    /** Sends a whole line, using the line ending the guest terminal expects. */
    public void submit(String line) {
        write(line + (pty ? "\r" : "\n"));
    }

    /**
     * Ends the session and everything it started.
     *
     * <p>The tracer gets SIGTERM first, because PRoot takes its tracees down
     * with it only when it leaves on its own; the sweep afterwards removes the
     * daemons that outlived it.
     */
    public void stop() {
        Process p;
        synchronized (this) {
            p = process;
            if (p != null) stopRequested = true;
        }
        if (p == null) return;

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
        if (alive(p)) killForcibly(p);
        killLeftovers();
    }

    /**
     * Kills dashboard servers only, leaving the gateway alone.
     *
     * <p>Used when the dashboard is stopped while a console session keeps
     * running: the gateway is that session's daemon.
     */
    public static void killDashboards() {
        sweep("[h]ermes dashboard", "[h]ermes_cli.*dashboard", "[h]ermes_cli.*web_server");
    }

    /**
     * Asks the guest processes to leave, waits, and only then forces them out.
     *
     * <p>SIGKILL was the whole story until 1.6.3, and it left the gateway
     * daemon behind with {@code gateway.lock} and {@code gateway_state.json}
     * pointing at a pid that no longer answers. The next start then saw
     * "gateway already running (PID …)" and died with
     * {@code Gateway connection failed; no local fallback}, while
     * {@code hermes gateway stop} at the same moment said "No gateway running".
     * SIGTERM lets the daemon remove its own locks, so KILL is only the
     * fallback for a process that ignores it.
     */
    private static void sweep(String... patterns) {
        StringBuilder term = new StringBuilder();
        StringBuilder kill = new StringBuilder();
        for (String pattern : patterns) {
            // The bracketed first letter keeps pkill from matching this very
            // `sh -c` command line, which contains the pattern as text.
            term.append("pkill -TERM -f '").append(pattern).append("' 2>/dev/null; ");
            kill.append("pkill -KILL -f '").append(pattern).append("' 2>/dev/null; ");
        }
        // Half a second is enough for a daemon to run its atexit hooks and is
        // paid only by STOP, never by START (Launcher sweeps off the UI thread).
        String cmd = term + "sleep 0.5; " + kill + "true";
        try {
            new ProcessBuilder("sh", "-c", cmd).start().waitFor();
        } catch (Exception ignored) {
            // A missing pkill or a denied signal is not fatal.
        }
    }

    /**
     * Kills guest processes that survived a session - the dashboard server
     * (which keeps port 9119 and its rendezvous record, so a later
     * {@code hermes dashboard} would only attach to it and exit without ever
     * serving) and the gateway daemon behind every session (which keeps the
     * control socket and profile lock, and keeps running the code from before
     * an update). Android kills the app (not its children), and PRoot's
     * {@code --kill-on-exit} does not run when the tracer is SIGKILLed.
     *
     * <p>Best effort: signals from the same uid reach the guest, but if
     * {@code pkill} is unavailable, {@code init.sh} sweeps the same processes
     * from inside the guest before it starts a server.
     */
    public static void killLeftovers() {
        sweep("[h]ermes dashboard", "[h]ermes_cli.*dashboard", "[h]ermes_cli.*web_server",
                "[h]ermes gateway", "[h]ermes_cli.*gateway run", "[g]ateway[.]run", "[c]hroot-ng");
    }

    /** {@code Process.isAlive()} is API 26, so probe the exit value instead. */
    private static boolean alive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    static void killForcibly(Process p) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            p.destroyForcibly();
            return;
        }
        // API 24-25 have no Process.destroyForcibly(); SIGKILL the tracee tree.
        try {
            String pid = String.valueOf(pidOf(p));
            if ("-1".equals(pid)) return;
            new ProcessBuilder("sh", "-c", "kill -9 " + pid + " 2>/dev/null").start().waitFor();
        } catch (Exception ignored) {
            // Best effort: PRoot was started with --kill-on-exit.
        }
    }

    /** libcore keeps the pid in a private field on every API level. */
    private static int pidOf(Process p) {
        try {
            java.lang.reflect.Field f = Process.class.getDeclaredField("pid");
            f.setAccessible(true);
            return ((Number) f.get(p)).intValue();
        } catch (Exception e) {
            return -1;
        }
    }
}

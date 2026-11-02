package com.hermes.loader;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Copies a SAF tree in and out of {@code storage/android/<label>}.
 *
 * <p>Why a copy and not a mount: the guest is a PRoot container that rewrites
 * path syscalls. A SAF tree is a {@code content://} provider with no filesystem
 * path, so there is nothing to bind. Android only exposes the real path behind
 * a tree URI with the all-files permission, which this build deliberately does
 * not request (see README §7).
 *
 * <p>Every transfer records what was copied (size + mtime for both sides) in
 * {@code storage/sync/<label>.json}. That record is what makes the next
 * transfer non-destructive: a file that changed on the phone *and* in the
 * container is never silently overwritten; the incoming version is written
 * next to it with a suffix and counted as a conflict.
 *
 * <p>Limitations are inherent to SAF and are documented for the user: no
 * executable bits, no symlinks, no empty-directory tracking, and every write
 * is a provider round-trip, so big trees are slow.
 */
public final class Mirror {

    public interface Sink {
        /** Called once per file; {@code files} is the running count. */
        void step(String path, int files, long bytes);

        boolean cancelled();
    }

    public static final class Report {
        public int created;
        public int replaced;
        public int deleted;
        public int conflicts;
        public int skipped;
        public int failed;
        public long bytes;
        public boolean cancelled;
        public final List<String> problems = new ArrayList<>();

        void problem(String text) {
            if (problems.size() < 25) problems.add(text);
            else if (problems.size() == 25) problems.add("…");
        }

        int touched() {
            return created + replaced + deleted + conflicts + skipped;
        }
    }

    private final Context ctx;
    private final SafTree tree;
    private final Prefs.Root root;
    private final File dir;
    private final File stateFile;
    private final Sink sink;
    private final Report report = new Report();
    private final SyncState state;
    private final Map<String, String> dirs = new HashMap<>();

    private Mirror(Context ctx, Prefs.Root root, Sink sink) {
        this.ctx = ctx.getApplicationContext();
        Env env = Env.get(ctx);
        this.root = root;
        this.tree = new SafTree(this.ctx, Uri.parse(root.uri));
        this.dir = env.mirrorDir(root.label);
        this.stateFile = env.syncState(root.label);
        this.state = SyncState.load(stateFile, root.uri);
        this.sink = sink;
    }

    /** Device → container. Existing local edits are preserved as conflicts. */
    public static Report importFromDevice(Context ctx, Prefs.Root root, Sink sink)
            throws IOException {
        Mirror m = new Mirror(ctx, root, sink);
        Env.mkdirs(m.dir);
        try {
            m.walkRemote(m.tree.rootId(), "", m.dir);
        } finally {
            // A provider can fail in ways that are not IOException (revoked
            // grant, dead process). Keeping the manifest means the work already
            // done is not repeated next time.
            m.saveQuietly();
        }
        return m.report;
    }

    /** Container → device. Files deleted locally are deleted on the device. */
    public static Report exportToDevice(Context ctx, Prefs.Root root, Sink sink)
            throws IOException {
        Mirror m = new Mirror(ctx, root, sink);
        if (!m.dir.isDirectory()) {
            throw new IOException("no local copy yet — import this folder first");
        }
        Map<String, SafTree.Node> remote = new HashMap<>();
        try {
            m.indexRemote(m.tree.rootId(), "", remote);
            m.walkLocal(m.dir, "", remote);
            m.deleteRemoved(remote);
        } finally {
            m.saveQuietly();
        }
        return m.report;
    }

    private void saveQuietly() {
        try {
            state.save(stateFile);
        } catch (IOException e) {
            report.problem("sync state not saved: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ common

    private boolean stop() {
        if (sink != null && sink.cancelled()) report.cancelled = true;
        return report.cancelled;
    }

    private void progress(String path) {
        if (sink != null) sink.step(path, report.touched(), report.bytes);
    }

    private static String join(String rel, String name) {
        return rel.isEmpty() ? name : rel + "/" + name;
    }

    private static String parentOf(String rel) {
        int slash = rel.lastIndexOf('/');
        return slash < 0 ? "" : rel.substring(0, slash);
    }

    private static String nameOf(String rel) {
        int slash = rel.lastIndexOf('/');
        return slash < 0 ? rel : rel.substring(slash + 1);
    }

    private static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
            String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    /** "report.txt" → "report (from phone).txt" */
    private static String conflictName(String name, String suffix) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        return base + " (" + suffix + ")" + ext;
    }

    // ------------------------------------------------------------------ import

    private void walkRemote(String docId, String rel, File target) {
        for (SafTree.Node n : tree.children(docId)) {
            if (stop()) return;
            String path = join(rel, n.name);
            if (n.dir) {
                File sub = new File(target, n.name);
                try {
                    Env.mkdirs(sub);
                } catch (IOException e) {
                    report.failed++;
                    report.problem("directory " + path + ": " + e.getMessage());
                    continue;
                }
                walkRemote(n.docId, path, sub);
            } else {
                importFile(n, path, target);
            }
        }
    }

    private void importFile(SafTree.Node remote, String path, File target) {
        File local = new File(target, remote.name);
        SyncState.Rec rec = state.get(path);
        boolean exists = local.isFile();
        // A provider may omit the size (-1). Comparing -1 with a recorded value
        // would mark an untouched file as changed on every import.
        boolean remoteChanged = rec == null
                || rec.rm != remote.modified
                || (remote.size >= 0 && rec.rs != remote.size);
        boolean localChanged = !exists || rec == null
                || rec.ls != local.length() || rec.lm != local.lastModified();

        progress(path);

        if (!exists) {
            try {
                copyFromDevice(remote, local);
                report.created++;
            } catch (IOException e) {
                report.failed++;
                report.problem(path + ": " + e.getMessage());
                return;
            }
        } else if (remoteChanged && localChanged) {
            // Both sides moved: keep the local file, park the device version
            // next to it. Losing an edit is worse than a duplicate.
            File alt = new File(target, conflictName(remote.name, "from phone"));
            try {
                copyFromDevice(remote, alt);
                report.conflicts++;
                report.problem(path + " → " + alt.getName());
                // Mark the device version as seen, otherwise every later import
                // would park another copy of the same unchanged conflict.
                state.put(path, new SyncState.Rec(
                        local.length(), local.lastModified(), remote.size, remote.modified));
            } catch (IOException e) {
                report.failed++;
                report.problem(path + ": " + e.getMessage());
                return;
            }
        } else if (remoteChanged) {
            try {
                copyFromDevice(remote, local);
                report.replaced++;
            } catch (IOException e) {
                report.failed++;
                report.problem(path + ": " + e.getMessage());
                return;
            }
        } else {
            report.skipped++;
            return;
        }

        state.put(path, new SyncState.Rec(
                local.length(), local.lastModified(), remote.size, remote.modified));
    }

    private void copyFromDevice(SafTree.Node remote, File dest) throws IOException {
        InputStream in = tree.open(remote.docId);
        OutputStream out = new FileOutputStream(dest);
        try {
            report.bytes += pump(in, out);
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
        if (remote.modified > 0) {
            // Keep the phone's timestamp so tools like make see the real age,
            // and so the next import compares like with like.
            if (!dest.setLastModified(remote.modified)) {
                // Some providers report a time the filesystem cannot hold.
            }
        }
    }

    // ------------------------------------------------------------------ export

    private void indexRemote(String docId, String rel, Map<String, SafTree.Node> files) {
        dirs.put(rel, docId);
        for (SafTree.Node n : tree.children(docId)) {
            if (stop()) return;
            String path = join(rel, n.name);
            if (n.dir) {
                indexRemote(n.docId, path, files);
            } else {
                files.put(path, n);
            }
        }
    }

    private void walkLocal(File current, String rel, Map<String, SafTree.Node> remote) {
        File[] kids = current.listFiles();
        if (kids == null) return;
        Arrays.sort(kids, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : kids) {
            if (stop()) return;
            String path = join(rel, f.getName());
            if (isSymlink(f)) {
                report.skipped++;
                report.problem(path + ": symbolic links are not copied");
                continue;
            }
            if (f.isDirectory()) {
                walkLocal(f, path, remote);
            } else if (f.isFile()) {
                exportFile(f, path, remote);
            }
        }
    }

    private void exportFile(File local, String path, Map<String, SafTree.Node> remote) {
        SafTree.Node rn = remote.get(path);
        SyncState.Rec rec = state.get(path);
        boolean localChanged = rec == null
                || rec.ls != local.length() || rec.lm != local.lastModified();
        boolean remoteChanged = rn != null
                && (rec == null || rec.rm != rn.modified
                || (rn.size >= 0 && rec.rs != rn.size));

        progress(path);

        try {
            if (rn == null) {
                if (rec != null && !localChanged) {
                    // The phone copy was deleted and the local one was not
                    // touched: respect the deletion instead of resurrecting it.
                    state.remove(path);
                    report.skipped++;
                    report.problem(path + ": deleted on the phone — not re-uploaded");
                    return;
                }
                writeFile(path, null, local);
                report.created++;
            } else if (rec == null) {
                // No history for this path: identical files are the same file,
                // anything else is unsafe to overwrite blindly.
                if ((rn.size < 0 || rn.size == local.length())
                        && rn.modified == local.lastModified()) {
                    report.skipped++;
                    state.put(path, new SyncState.Rec(
                            local.length(), local.lastModified(), rn.size, rn.modified));
                    return;
                }
                String alt = conflictName(nameOf(path), "from app");
                String altPath = join(parentOf(path), alt);
                writeFile(altPath, null, local);
                report.conflicts++;
                report.problem(path + " → " + alt);
                // Record the device version as the current one for this path,
                // otherwise every later export would create another copy.
                state.put(path, new SyncState.Rec(
                        local.length(), local.lastModified(), rn.size, rn.modified));
                return;
            } else if (localChanged && remoteChanged) {
                String alt = conflictName(nameOf(path), "from app");
                String altPath = join(parentOf(path), alt);
                writeFile(altPath, null, local);
                report.conflicts++;
                report.problem(path + " → " + alt);
                state.put(path, new SyncState.Rec(
                        local.length(), local.lastModified(), rn.size, rn.modified));
                return;
            } else if (localChanged) {
                writeFile(path, rn, local);
                report.replaced++;
            } else {
                report.skipped++;
                return;
            }
        } catch (IOException e) {
            report.failed++;
            report.problem(path + ": " + e.getMessage());
        }
    }

    private void writeFile(String path, SafTree.Node existing, File local) throws IOException {
        String parentDoc = ensureDir(parentOf(path));
        String name = nameOf(path);

        SafTree.Node after = null;
        if (existing != null) {
            try {
                copyTo(tree.truncate(existing.docId), local);
                after = tree.stat(existing.docId);
            } catch (IOException e) {
                // Provider cannot truncate (Drive and friends). Recreating the
                // document is the only portable way to replace its contents.
                try {
                    tree.delete(existing.docId);
                } catch (IOException ignored) {
                    // If the delete failed, the create below will report.
                }
                existing = null;
            }
        }
        if (existing == null) {
            String docId = tree.create(parentDoc, name, mimeOf(name));
            copyTo(tree.truncate(docId), local);
            after = tree.stat(docId);
        }
        if (after == null) {
            after = new SafTree.Node("", name, false, local.length(), local.lastModified());
        }
        // Some providers report 0 / -1 until the next sync. Recording that
        // verbatim would turn the next local edit into a pointless conflict.
        long remoteSize = after.size <= 0 && local.length() > 0 ? local.length() : after.size;
        long remoteTime = after.modified <= 0 ? local.lastModified() : after.modified;
        state.put(path, new SyncState.Rec(
                local.length(), local.lastModified(), remoteSize, remoteTime));
    }

    private String ensureDir(String rel) throws IOException {
        if (rel.isEmpty()) return tree.rootId();
        String known = dirs.get(rel);
        if (known != null) return known;
        String parent = ensureDir(parentOf(rel));
        String name = nameOf(rel);
        for (SafTree.Node n : tree.children(parent)) {
            if (n.dir && n.name.equalsIgnoreCase(name)) {
                dirs.put(rel, n.docId);
                return n.docId;
            }
        }
        String docId = tree.create(parent, name, DocumentsContract.Document.MIME_TYPE_DIR);
        dirs.put(rel, docId);
        return docId;
    }

    private void copyTo(OutputStream out, File src) throws IOException {
        InputStream in = new FileInputStream(src);
        try {
            report.bytes += pump(in, out);
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    /**
     * Device files that are gone from the container are removed from the
     * device too — but only when the device copy is still the one we copied
     * out. A file edited on the phone since then is kept and reported.
     */
    private void deleteRemoved(Map<String, SafTree.Node> remote) {
        for (String path : new ArrayList<>(state.paths())) {
            if (stop()) return;
            if (new File(dir, path).isFile()) continue;
            SafTree.Node rn = remote.get(path);
            SyncState.Rec rec = state.get(path);
            if (rn == null) {
                state.remove(path);
                continue;
            }
            boolean remoteChanged = rec == null
                    || rec.rm != rn.modified
                    || (rn.size >= 0 && rec.rs != rn.size);
            if (remoteChanged) {
                report.skipped++;
                report.problem(path + ": kept on the phone (changed there)");
                continue;
            }
            try {
                tree.delete(rn.docId);
                state.remove(path);
                report.deleted++;
                progress(path);
            } catch (IOException e) {
                report.failed++;
                report.problem(path + ": " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static long pump(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            total += n;
        }
        return total;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // nothing useful to do here
        }
    }

    /** {@code File.isFile()} follows links; exporting a link target is wrong. */
    private static boolean isSymlink(File f) {
        try {
            android.system.StructStat st =
                    android.system.Os.lstat(f.getAbsolutePath());
            return android.system.OsConstants.S_ISLNK(st.st_mode);
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------- sync state

    /** Per-path record of what the last transfer copied, on both sides. */
    static final class SyncState {
        static final class Rec {
            final long ls;
            final long lm;
            final long rs;
            final long rm;

            Rec(long ls, long lm, long rs, long rm) {
                this.ls = ls;
                this.lm = lm;
                this.rs = rs;
                this.rm = rm;
            }
        }

        private final String uri;
        private final Map<String, Rec> files = new HashMap<>();

        private SyncState(String uri) {
            this.uri = uri;
        }

        static SyncState load(File file, String uri) {
            SyncState s = new SyncState(uri);
            if (!file.isFile()) return s;
            try {
                String text = new String(readAll(file), java.nio.charset.StandardCharsets.UTF_8);
                JSONObject o = new JSONObject(text);
                JSONObject entries = o.optJSONObject("files");
                if (entries != null) {
                    for (Iterator<String> it = entries.keys(); it.hasNext(); ) {
                        String path = it.next();
                        JSONObject e = entries.optJSONObject(path);
                        if (e == null) continue;
                        s.files.put(path, new Rec(
                                e.optLong("ls"), e.optLong("lm"),
                                e.optLong("rs"), e.optLong("rm")));
                    }
                }
            } catch (Exception ignored) {
                // A corrupt manifest only costs one round of conflict copies.
            }
            return s;
        }

        Rec get(String path) {
            return files.get(path);
        }

        void put(String path, Rec rec) {
            files.put(path, rec);
        }

        void remove(String path) {
            files.remove(path);
        }

        List<String> paths() {
            return new ArrayList<>(files.keySet());
        }

        void save(File file) throws IOException {
            JSONObject o = new JSONObject();
            try {
                o.put("uri", uri);
                JSONObject entries = new JSONObject();
                for (Map.Entry<String, Rec> e : files.entrySet()) {
                    JSONObject r = new JSONObject();
                    r.put("ls", e.getValue().ls);
                    r.put("lm", e.getValue().lm);
                    r.put("rs", e.getValue().rs);
                    r.put("rm", e.getValue().rm);
                    entries.put(e.getKey(), r);
                }
                o.put("files", entries);
            } catch (JSONException e) {
                throw new IOException("cannot encode sync state", e);
            }
            File parent = file.getParentFile();
            if (parent != null) Env.mkdirs(parent);
            File tmp = new File(file.getPath() + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file)) {
                // Windows-style replace semantics do not apply here, but a
                // rename can still fail if the old file is locked.
                if (!file.delete() || !tmp.renameTo(file)) {
                    throw new IOException("cannot write " + file);
                }
            }
        }

        private static byte[] readAll(File file) throws IOException {
            try (InputStream in = new FileInputStream(file)) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                pump(in, out);
                return out.toByteArray();
            }
        }
    }
}

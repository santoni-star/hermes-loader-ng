package com.hermes.loader;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The parts of the Storage Access Framework this app needs, written directly
 * against {@link DocumentsContract}.
 *
 * <p>There is no {@code androidx.documentfile} dependency: the project is
 * framework-only, and one cursor per directory is also much faster than
 * {@code DocumentFile.listFiles()}, which issues a query per child.
 *
 * <p>A SAF tree is a {@code content://} provider. Linux inside PRoot cannot
 * mount it — PRoot rewrites paths, it does not speak ContentProvider — so the
 * tree is copied in and out of {@code storage/android/<label>} instead. See
 * {@link Mirror}.
 */
public final class SafTree {

    /** One directory entry, as reported by the provider. */
    public static final class Node {
        public final String docId;
        public final String name;
        public final boolean dir;
        /** Bytes, or -1 when the provider does not report a size. */
        public final long size;
        /** Milliseconds, or 0 when the provider does not report a time. */
        public final long modified;

        Node(String docId, String name, boolean dir, long size, long modified) {
            this.docId = docId;
            this.name = name;
            this.dir = dir;
            this.size = size;
            this.modified = modified;
        }
    }

    private static final String[] PROJECTION = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    };

    private final Context ctx;
    private final Uri treeUri;
    private final String rootId;

    public SafTree(Context ctx, Uri treeUri) {
        this.ctx = ctx.getApplicationContext();
        this.treeUri = treeUri;
        this.rootId = DocumentsContract.getTreeDocumentId(treeUri);
    }

    public String rootId() {
        return rootId;
    }

    public Uri uri(String docId) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
    }

    /** Children of a directory, sorted by name; empty when it cannot be read. */
    public List<Node> children(String parentDocId) {
        List<Node> out = new ArrayList<>();
        ContentResolver cr = ctx.getContentResolver();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        Cursor c = null;
        try {
            c = cr.query(children, PROJECTION, null, null, null);
            if (c == null) return out;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (id == null || name == null) continue;
                long size = c.isNull(3) ? -1 : c.getLong(3);
                long modified = c.isNull(4) ? 0 : c.getLong(4);
                boolean dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                out.add(new Node(id, name, dir, size, modified));
            }
        } catch (RuntimeException e) {
            // A provider can be gone (drive unmounted, app uninstalled). An
            // empty listing is handled by the caller as "nothing to copy".
            return out;
        } finally {
            if (c != null) c.close();
        }
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /** Re-reads one document's metadata; {@code null} when it is gone. */
    public Node stat(String docId) {
        ContentResolver cr = ctx.getContentResolver();
        Cursor c = null;
        try {
            c = cr.query(uri(docId), PROJECTION, null, null, null);
            if (c == null || !c.moveToFirst()) return null;
            String id = c.getString(0);
            String name = c.getString(1);
            String mime = c.getString(2);
            long size = c.isNull(3) ? -1 : c.getLong(3);
            long modified = c.isNull(4) ? 0 : c.getLong(4);
            return new Node(id, name, DocumentsContract.Document.MIME_TYPE_DIR.equals(mime),
                    size, modified);
        } catch (RuntimeException e) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    public InputStream open(String docId) throws FileNotFoundException {
        InputStream in = ctx.getContentResolver().openInputStream(uri(docId));
        if (in == null) throw new FileNotFoundException("provider returned no stream for " + docId);
        return in;
    }

    /**
     * Creates a document. When a file with that name already exists the
     * provider would silently pick "name (1).txt", so callers pass the
     * existing document id to {@link #truncate} instead.
     */
    public String create(String parentDocId, String name, String mime) throws IOException {
        Uri created;
        try {
            created = DocumentsContract.createDocument(
                    ctx.getContentResolver(), uri(parentDocId), mime, name);
        } catch (RuntimeException e) {
            throw new IOException("cannot create " + name + ": " + e.getMessage(), e);
        }
        if (created == null) throw new IOException("provider refused to create " + name);
        return DocumentsContract.getDocumentId(created);
    }

    /** Opens an existing document for writing, truncating it. */
    public OutputStream truncate(String docId) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        try {
            OutputStream out = cr.openOutputStream(uri(docId), "wt");
            if (out == null) throw new IOException("provider returned no stream");
            return out;
        } catch (FileNotFoundException | IllegalArgumentException | UnsupportedOperationException e) {
            // Not every provider implements "wt". Recreating the document
            // keeps the data correct at the cost of its document identity.
            throw new IOException("cannot truncate " + docId, e);
        }
    }

    public void delete(String docId) throws IOException {
        try {
            if (!DocumentsContract.deleteDocument(ctx.getContentResolver(), uri(docId))) {
                throw new IOException("provider refused to delete " + docId);
            }
        } catch (FileNotFoundException e) {
            // Already gone: treat as success so a retried export converges.
        }
    }

    /** Display name of the tree itself, used as the default folder label. */
    public static String displayName(Context ctx, Uri treeUri) {
        Cursor c = null;
        try {
            ContentResolver cr = ctx.getContentResolver();
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            c = cr.query(doc, new String[]{
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null && !name.trim().isEmpty()) return name.trim();
            }
        } catch (RuntimeException ignored) {
            // fall through to the URI-based guess
        } finally {
            if (c != null) c.close();
        }
        String last = treeUri.getLastPathSegment();
        if (last != null) {
            int colon = last.indexOf(':');
            if (colon >= 0 && colon + 1 < last.length()) last = last.substring(colon + 1);
            int slash = last.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < last.length()) last = last.substring(slash + 1);
            if (!last.isEmpty()) return last;
        }
        return "Phone folder";
    }
}

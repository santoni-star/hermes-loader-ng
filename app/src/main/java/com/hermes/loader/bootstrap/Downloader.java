package com.hermes.loader.bootstrap;

import com.hermes.loader.Env;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Tiny HTTPS downloader with resume support and progress callbacks. */
public final class Downloader {

    public interface Progress {
        void onProgress(long done, long total);
    }

    private static final String UA = "HermesLoader/1.0 (Android)";
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private Downloader() {
    }

    public static String fetchString(String url) throws IOException {
        return fetchString(url, null);
    }

    public static String fetchString(String url, Map<String, String> headers) throws IOException {
        HttpURLConnection c = open(url, headers);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out, null, -1, 0L);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    public static File fetch(RemoteArchive archive, File dest, Progress progress) throws IOException {
        return fetch(archive.url, archive.headers, dest, progress);
    }

    /**
     * Downloads one archive and prints the progress on a single, self-rewriting
     * line. Shared by the rootfs and the prebuilt-bundle installers so both show
     * the same shape of output.
     */
    public static void download(RemoteArchive archive, File dest, Log log) throws IOException {
        final long[] lastPct = {-1};
        fetch(archive, dest, (done, total) -> {
            String text;
            if (total > 0) {
                int pct = (int) (done * 100 / total);
                if (pct == lastPct[0]) return;
                lastPct[0] = pct;
                text = String.format(java.util.Locale.US,
                        "\r[NET] %3d%%  %s / %s", pct, mb(done), mb(total));
            } else {
                text = String.format(java.util.Locale.US, "\r[NET] %s", mb(done));
            }
            log.raw(text);
        });
        log.line("");
    }

    public static String mb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    /**
     * Downloads {@code url} into {@code dest}. A partially downloaded file is
     * kept as {@code dest.part} and resumed with a Range request on retry.
     *
     * <p>Redirects are followed manually: the Docker registry answers blob
     * requests with a 307 to a presigned CDN URL, and forwarding our bearer
     * token there makes S3/Cloudflare reject the request.
     */
    public static File fetch(String url, Map<String, String> headers, File dest, Progress progress)
            throws IOException {
        File part = new File(dest.getPath() + ".part");
        File parent = dest.getParentFile();
        if (parent != null) {
            Env.mkdirs(parent);
        }

        long already = part.isFile() ? part.length() : 0L;
        HttpURLConnection c = null;
        try {
            String target = url;
            Map<String, String> hdrs = headers;
            int hops = 0;
            int code;
            while (true) {
                c = open(target, hdrs);
                c.setInstanceFollowRedirects(false);
                if (already > 0) {
                    c.setRequestProperty("Range", "bytes=" + already + "-");
                }
                code = c.getResponseCode();
                if (isRedirect(code) && hops++ < 5) {
                    String location = c.getHeaderField("Location");
                    if (location == null) break;
                    target = new URL(new URL(target), location).toString();
                    c.disconnect();
                    hdrs = null; // never leak credentials to the storage backend
                    continue;
                }
                break;
            }

            if (code == 416) {
                // The server considers our .part complete.
                if (!part.renameTo(dest)) throw new IOException("Cannot rename " + part);
                return dest;
            }
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("HTTP " + code + " for " + url);
            }

            boolean resume = code == HttpURLConnection.HTTP_PARTIAL;
            if (!resume) {
                already = 0L;
            }
            long total = c.getContentLengthLong();
            if (total > 0) {
                total += already;
            }

            try (InputStream in = new BufferedInputStream(c.getInputStream(), 64 * 1024);
                 OutputStream out = new FileOutputStream(part, resume)) {
                copy(in, out, progress, total, already);
            }

            if (!part.renameTo(dest)) {
                throw new IOException("Cannot rename " + part + " -> " + dest);
            }
            return dest;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    private static void copy(InputStream in, OutputStream out, Progress cb, long total, long start)
            throws IOException {
        byte[] buf = new byte[64 * 1024];
        long done = start;
        long last = 0L;
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            done += n;
            long now = System.currentTimeMillis();
            if (cb != null && (now - last > 200 || done == total)) {
                last = now;
                cb.onProgress(done, total);
            }
        }
        out.flush();
        if (cb != null) {
            cb.onProgress(done, total);
        }
    }

    private static HttpURLConnection open(String url, Map<String, String> headers) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "*/*");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                c.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        return c;
    }
}

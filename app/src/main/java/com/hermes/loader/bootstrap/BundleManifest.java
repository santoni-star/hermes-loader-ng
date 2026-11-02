package com.hermes.loader.bootstrap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The {@code manifest.json} published next to a prebuilt bundle.
 *
 * <pre>
 * {
 *   "schema": 1,
 *   "id": "2026-10-28.1",
 *   "agent_commit": "dd2ae84a",
 *   "python": "3.14.0",
 *   "layers": [
 *     {"id": "rootfs", "target": "rootfs", "abi": "arm64-v8a",
 *      "url": "https://…/rootfs-arm64-v8a.tar.xz", "sha256": "…",
 *      "bytes": 240000000, "unpacked": 900000000, "compression": "xz"},
 *     {"id": "agent",  "target": "agent",  "abi": "arm64-v8a",
 *      "url": "https://…/agent-arm64-v8a.tar.xz", "sha256": "…",
 *      "bytes": 260000000, "unpacked": 900000000}
 *   ]
 * }
 * </pre>
 *
 * <p>Layers are optional: a bundle with only {@code rootfs} installs the base
 * system and lets the normal setup finish the agent on the phone. Layers whose
 * {@code abi} does not match this device are ignored, so one manifest can serve
 * arm64, armhf and x86_64 builds.
 */
public final class BundleManifest {

    public static final String TARGET_ROOTFS = "rootfs";
    public static final String TARGET_AGENT = "agent";

    public static final class Layer {
        public final String id;
        public final String target;
        public final String url;
        public final String sha256;
        public final long bytes;
        public final long unpacked;
        /** true for .tar.gz, false for .tar.xz */
        public final boolean gzip;

        Layer(String id, String target, String url, String sha256, long bytes,
              long unpacked, boolean gzip) {
            this.id = id;
            this.target = target;
            this.url = url;
            this.sha256 = sha256;
            this.bytes = bytes;
            this.unpacked = unpacked;
            this.gzip = gzip;
        }

        /** Human-readable size for the log, e.g. {@code 240.3 MB}. */
        public String sizeText() {
            return bytes > 0 ? Downloader.mb(bytes) : "unknown size";
        }
    }

    public final String id;
    public final String agentCommit;
    public final String python;
    public final List<Layer> layers;

    private BundleManifest(String id, String agentCommit, String python, List<Layer> layers) {
        this.id = id;
        this.agentCommit = agentCommit;
        this.python = python;
        this.layers = Collections.unmodifiableList(layers);
    }

    /**
     * @param abi value of {@link com.hermes.loader.Env#abiDir()}; layers that
     *            declare a different ABI are dropped.
     */
    public static BundleManifest parse(String text, String abi) throws Exception {
        JSONObject root = new JSONObject(text);
        int schema = root.optInt("schema", 1);
        if (schema > 1) {
            throw new IllegalStateException("The bundle manifest needs a newer app (schema "
                    + schema + ").");
        }

        JSONArray array = root.optJSONArray("layers");
        if (array == null || array.length() == 0) {
            throw new IllegalStateException("The bundle manifest lists no layers.");
        }

        List<Layer> kept = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) continue;

            String layerAbi = o.optString("abi", "");
            if (!layerAbi.isEmpty() && abi != null && !layerAbi.equals(abi)) {
                continue;
            }

            String url = o.optString("url", "");
            String target = o.optString("target", "");
            if (url.isEmpty() || target.isEmpty()) {
                continue;
            }

            String compression = o.optString("compression", "").toLowerCase(java.util.Locale.ROOT);
            String name = url.toLowerCase(java.util.Locale.ROOT);
            boolean gzip = compression.equals("gzip") || compression.equals("gz")
                    || (compression.isEmpty()
                    && (name.endsWith(".tar.gz") || name.endsWith(".tgz")));

            kept.add(new Layer(
                    o.optString("id", target),
                    target,
                    url,
                    o.optString("sha256", ""),
                    o.optLong("bytes", 0L),
                    o.optLong("unpacked", 0L),
                    gzip));
        }

        if (kept.isEmpty()) {
            throw new IllegalStateException("This bundle has no layer for " + abi
                    + ". Use the normal installer instead.");
        }

        return new BundleManifest(
                root.optString("id", "bundle"),
                root.optString("agent_commit", ""),
                root.optString("python", ""),
                kept);
    }

    /** The layer for {@code target}, or null when the bundle does not ship one. */
    public Layer layer(String target) {
        for (Layer l : layers) {
            if (l.target.equals(target)) return l;
        }
        return null;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(id);
        if (!agentCommit.isEmpty()) {
            sb.append(", agent ").append(shortCommit());
        }
        if (!python.isEmpty()) {
            sb.append(", Python ").append(python);
        }
        return sb.toString();
    }

    public String shortCommit() {
        return agentCommit.length() > 10 ? agentCommit.substring(0, 10) : agentCommit;
    }
}

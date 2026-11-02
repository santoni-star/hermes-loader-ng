package com.hermes.loader.bootstrap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pulls {@code debian:trixie-slim} straight from the Docker Hub registry.
 *
 * <p>This is the smallest Debian userland that is still a complete apt system
 * (~27 MB compressed, ~75 MB on disk) and it is what the specification asks
 * for, so it is the primary source. The OCI layer tarball is a plain
 * {@code .tar.gz}, and its digest lets us verify the download.
 */
public final class DockerHub {

    private static final String REGISTRY = "https://registry-1.docker.io/v2/library/debian";
    private static final String AUTH = "https://auth.docker.io/token"
            + "?service=registry.docker.io&scope=repository:library/debian:pull";
    private static final String TAG = "trixie-slim";

    private DockerHub() {
    }

    public static List<RemoteArchive> resolve(String debianArch) throws Exception {
        String token = token();
        Map<String, String> auth = new LinkedHashMap<>();
        auth.put("Authorization", "Bearer " + token);

        JSONObject index = getJson(REGISTRY + "/manifests/" + TAG, auth,
                "application/vnd.oci.image.index.v1+json,"
                        + "application/vnd.docker.distribution.manifest.list.v2+json");

        String digest = pickManifest(index, debianArch);
        if (digest == null) {
            throw new IllegalStateException("No image for arch " + debianArch);
        }

        JSONObject manifest = getJson(REGISTRY + "/manifests/" + digest, auth,
                "application/vnd.oci.image.manifest.v1+json,"
                        + "application/vnd.docker.distribution.manifest.v2+json");

        JSONArray layers = manifest.getJSONArray("layers");
        List<RemoteArchive> parts = new ArrayList<>();
        for (int i = 0; i < layers.length(); i++) {
            JSONObject layer = layers.getJSONObject(i);
            String layerDigest = layer.getString("digest");
            parts.add(new RemoteArchive(
                    REGISTRY + "/blobs/" + layerDigest,
                    true,
                    auth,
                    layerDigest.startsWith("sha256:") ? layerDigest.substring(7) : null,
                    "debian:trixie-slim layer " + (i + 1) + "/" + layers.length()));
        }
        if (parts.isEmpty()) {
            throw new IllegalStateException("Image has no layers");
        }
        return parts;
    }

    private static String pickManifest(JSONObject index, String debianArch) throws Exception {
        String wantedArch;
        String wantedVariant;
        switch (debianArch) {
            case "arm64":
                wantedArch = "arm64";
                wantedVariant = "v8";
                break;
            case "armhf":
                wantedArch = "arm";
                wantedVariant = "v7";
                break;
            case "amd64":
                wantedArch = "amd64";
                wantedVariant = "";
                break;
            case "i386":
                wantedArch = "386";
                wantedVariant = "";
                break;
            default:
                throw new IllegalArgumentException("Unsupported Debian port: " + debianArch);
        }

        JSONArray manifests = index.getJSONArray("manifests");
        String fallback = null;
        for (int i = 0; i < manifests.length(); i++) {
            JSONObject m = manifests.getJSONObject(i);
            JSONObject platform = m.optJSONObject("platform");
            if (platform == null) continue;
            String arch = platform.optString("architecture");
            String variant = platform.optString("variant");
            if (!wantedArch.equals(arch)) continue;
            if (wantedVariant.isEmpty() || wantedVariant.equals(variant)) {
                return m.getString("digest");
            }
            if (fallback == null) {
                fallback = m.getString("digest");
            }
        }
        return fallback;
    }

    private static String token() throws Exception {
        JSONObject o = getJson(AUTH, null, null);
        String token = o.optString("token", "");
        if (token.isEmpty()) token = o.optString("access_token", "");
        if (token.isEmpty()) throw new IllegalStateException("Docker Hub returned no token");
        return token;
    }

    private static JSONObject getJson(String url, Map<String, String> headers, String accept)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(30_000);
        c.setReadTimeout(60_000);
        c.setRequestProperty("User-Agent", "HermesLoader/1.0");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                c.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        if (accept != null) {
            c.setRequestProperty("Accept", accept);
        }
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("Registry HTTP " + code + " for " + url);
            }
            try (InputStream in = new BufferedInputStream(c.getInputStream())) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            c.disconnect();
        }
    }
}

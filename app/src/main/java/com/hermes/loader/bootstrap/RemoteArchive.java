package com.hermes.loader.bootstrap;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One downloadable rootfs part plus the metadata needed to verify it. */
public final class RemoteArchive {

    public final String url;
    /** true for .tar.gz, false for .tar.xz */
    public final boolean gzip;
    public final Map<String, String> headers;
    /** Lowercase hex sha256 of the archive, or null when unknown. */
    public final String sha256;
    /** Lowercase hex sha512 of the archive, or null when unknown. */
    public final String sha512;
    public final String label;

    public RemoteArchive(String url, boolean gzip, Map<String, String> headers,
                         String sha256, String label) {
        this(url, gzip, headers, sha256, null, label);
    }

    public RemoteArchive(String url, boolean gzip, Map<String, String> headers,
                         String sha256, String sha512, String label) {
        this.url = url;
        this.gzip = gzip;
        this.headers = headers == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.sha256 = sha256;
        this.sha512 = sha512;
        this.label = label;
    }
}

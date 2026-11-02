package com.hermes.loader.bootstrap;

import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chooses where the Debian rootfs comes from.
 *
 * <ol>
 *   <li>a URL from settings, if the user set one;</li>
 *   <li>{@code debian:trixie-slim} via Docker Hub (smallest, digest-verified);</li>
 *   <li>the Linux Containers image index (complete, bigger);</li>
 *   <li>a pinned Linux Containers build.</li>
 * </ol>
 */
public final class RootfsSource {

    private static final String LXC = "https://images.linuxcontainers.org/images/debian/trixie/";
    private static final Pattern DATE_DIR =
            Pattern.compile("href=\"(\\d{8}_\\d{2}(?:%3A|:)\\d{2})/\"");
    private static final String PINNED_DATE = "20261030_05%3A24";

    private RootfsSource() {
    }

    public static List<RemoteArchive> resolve(String debianArch, String override, Log log) {
        if (override != null && !override.trim().isEmpty()) {
            String url = override.trim();
            log.line("[NET] Using custom rootfs URL from settings.");
            return Collections.singletonList(new RemoteArchive(
                    url, !url.toLowerCase(java.util.Locale.ROOT).endsWith(".xz"), null, null,
                    "custom rootfs"));
        }

        try {
            List<RemoteArchive> parts = DockerHub.resolve(debianArch);
            log.line("[NET] Source: official Debian image (library/debian, built and published"
                    + " by the Debian project) — " + debianArch + ".");
            return parts;
        } catch (Exception e) {
            log.line("[NET] Docker Hub unavailable (" + e.getMessage() + ").");
        }

        try {
            String url = latestLxc(debianArch);
            log.line("[NET] Source: linuxcontainers.org Debian (" + debianArch + ").");
            return Collections.singletonList(new RemoteArchive(
                    url, false, null, null, "linuxcontainers rootfs"));
        } catch (Exception e) {
            log.line("[NET] Image index unavailable (" + e.getMessage() + "), using pinned build.");
        }

        return Collections.singletonList(new RemoteArchive(
                LXC + dirFor(debianArch) + "/default/" + PINNED_DATE + "/rootfs.tar.xz",
                false, null, null, "linuxcontainers rootfs (pinned)"));
    }

    private static String latestLxc(String debianArch) throws Exception {
        String index = LXC + dirFor(debianArch) + "/default/";
        String html = Downloader.fetchString(index);
        Matcher m = DATE_DIR.matcher(html);
        String best = null;
        while (m.find()) {
            String token = m.group(1);
            if (best == null || token.compareTo(best) > 0) {
                best = token;
            }
        }
        if (best == null) {
            throw new IllegalStateException("no dated images in index");
        }
        return index + best + "/rootfs.tar.xz";
    }

    private static String dirFor(String debianArch) {
        switch (debianArch) {
            case "arm64":
            case "armhf":
            case "amd64":
            case "i386":
                return debianArch;
            default:
                throw new IllegalArgumentException("Unsupported Debian port: " + debianArch);
        }
    }
}

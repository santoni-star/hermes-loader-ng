package com.hermes.loader.bootstrap;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;

import com.hermes.loader.Env;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the network files the guest needs. Android has no /etc/resolv.conf, so
 * the resolver configuration is copied from the active {@link LinkProperties}
 * (with public resolvers as a fallback) and bind-mounted into the container.
 */
public final class NetFiles {

    private NetFiles() {
    }

    public static void write(Context ctx, Env env) throws IOException {
        Env.mkdirs(env.storageEtc);

        List<String> dns = dnsServers(ctx);
        if (dns.isEmpty()) {
            dns.add("8.8.8.8");
            dns.add("1.1.1.1");
        }

        StringBuilder resolv = new StringBuilder();
        int count = 0;
        for (String server : dns) {
            if (count++ >= 3) break;
            resolv.append("nameserver ").append(server).append('\n');
        }
        resolv.append("options timeout:2 attempts:2\n");

        write(new File(env.storageEtc, "resolv.conf"), resolv.toString());
        write(new File(env.storageEtc, "hosts"),
                "127.0.0.1 localhost hermes\n"
                        + "::1 localhost ip6-localhost\n");
        write(new File(env.storageEtc, "hostname"), "hermes\n");

        // Fresh guest-side defaults, used when storage/ is wiped but the
        // rootfs survived.
        File rootfsEtc = new File(env.rootfs, "etc");
        if (rootfsEtc.isDirectory()) {
            write(new File(rootfsEtc, "resolv.conf"), resolv.toString());
            write(new File(rootfsEtc, "hosts"), "127.0.0.1 localhost hermes\n");
        }
    }

    private static List<String> dnsServers(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return out;
            Network network = cm.getActiveNetwork();
            if (network == null) return out;
            LinkProperties lp = cm.getLinkProperties(network);
            if (lp == null) return out;
            for (InetAddress addr : lp.getDnsServers()) {
                if (addr == null || addr.isLoopbackAddress()) continue;
                if (addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) continue;
                String host = addr.getHostAddress();
                int zone = host.indexOf('%');
                if (zone > 0) host = host.substring(0, zone);
                if (host.isEmpty() || out.contains(host)) continue;
                // IPv4 first: some guest resolvers trip over bare IPv6.
                if (addr instanceof java.net.Inet4Address) {
                    out.add(0, host);
                } else {
                    out.add(host);
                }
            }
        } catch (RuntimeException ignored) {
            // Fall back to public resolvers.
        }
        return out;
    }

    private static void write(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) Env.mkdirs(parent);
        // Drop a squatter (often a symlink) so the write cannot escape the tree.
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}

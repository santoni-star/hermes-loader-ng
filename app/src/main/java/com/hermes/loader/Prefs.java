package com.hermes.loader;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** User-tweakable settings that live outside the Linux filesystem. */
public final class Prefs {

    /** One SAF folder the user added, with the label it gets in the container. */
    public static final class Root {
        public final String uri;
        public final String label;

        public Root(String uri, String label) {
            this.uri = uri;
            this.label = label;
        }
    }

    public static final String DEFAULT_REPO = "https://github.com/NousResearch/hermes-agent.git";
    public static final String DEFAULT_BRANCH = "main";

    private static final String FILE = "loader";
    private static final String K_REPO = "repo";
    private static final String K_BRANCH = "branch";
    private static final String K_ROOTFS_URL = "rootfs_url";
    private static final String K_PROFILE = "profile";
    private static final String K_PTY = "pty";
    private static final String K_COLUMNS = "columns";
    private static final String K_ROWS = "rows";
    private static final String K_COMPAT = "compat";
    private static final String K_FONT = "font_scale";
    private static final String K_EXTRA_KEYS = "extra_keys";
    private static final String K_ROOTS = "storage_roots";
    private static final String K_BUNDLE = "use_bundle";
    private static final String K_BUNDLE_URL = "bundle_url";
    private static final String K_PREWARM = "gateway_prewarm";
    private static final String K_WEB_ONLY = "web_only";
    private static final String K_WEB_NETWORK = "web_network";
    private static final String K_WEB_USER = "web_user";
    private static final String K_WEB_PASSWORD = "web_password";
    private static final String K_WEB_SECRET = "web_secret";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String repo() {
        return sp.getString(K_REPO, DEFAULT_REPO);
    }

    public void repo(String v) {
        sp.edit().putString(K_REPO, v).apply();
    }

    public String branch() {
        return sp.getString(K_BRANCH, DEFAULT_BRANCH);
    }

    public void branch(String v) {
        sp.edit().putString(K_BRANCH, v == null || v.trim().isEmpty()
                ? DEFAULT_BRANCH : v.trim()).apply();
    }

    /** "core", "all" or "termux" — becomes the pip extra used for the agent. */
    public String profile() {
        String v = sp.getString(K_PROFILE, "all");
        return v == null || v.isEmpty() ? "all" : v;
    }

    public void profile(String v) {
        sp.edit().putString(K_PROFILE, v).apply();
    }

    public String rootfsUrl() {
        return sp.getString(K_ROOTFS_URL, "");
    }

    public void rootfsUrl(String v) {
        sp.edit().putString(K_ROOTFS_URL, v == null ? "" : v.trim()).apply();
    }

    public boolean pty() {
        return sp.getBoolean(K_PTY, true);
    }

    public void pty(boolean v) {
        sp.edit().putBoolean(K_PTY, v).apply();
    }

    /**
     * Install from a prebuilt bundle when possible. On by default: it turns the
     * ten-minute apt/pip phase into a download. Turning it off restores the
     * classic installer, which is also the automatic fallback on any error.
     */
    public boolean useBundle() {
        return sp.getBoolean(K_BUNDLE, true);
    }

    public void useBundle(boolean v) {
        sp.edit().putBoolean(K_BUNDLE, v).apply();
    }

    /** Overrides the published bundle manifest; empty means "use the default". */
    public String bundleUrl() {
        return sp.getString(K_BUNDLE_URL, "");
    }

    public void bundleUrl(String v) {
        sp.edit().putString(K_BUNDLE_URL, v == null ? "" : v.trim()).apply();
    }

    /**
     * Wait for the gateway before the console starts (since 1.6.3), instead of
     * letting Hermes start it and give up. The console's first act is a
     * synchronous gateway session open with an ~80 s deadline; the daemon needs
     * 60-110 s inside the container on a phone, so the console used to lose that
     * race and exit with "Gateway connection failed; no local fallback". Off is
     * only useful to reproduce the old behaviour.
     *
     * <p>The preference key and the environment variable keep their old
     * {@code prewarm} names: renaming either would only add a way for an older
     * copy of the guest scripts to disagree with a newer app.
     */
    public boolean gatewayPrewarm() {
        return sp.getBoolean(K_PREWARM, true);
    }

    public void gatewayPrewarm(boolean v) {
        sp.edit().putBoolean(K_PREWARM, v).apply();
    }

    /** Start a cold WEB session without the interactive console to save RAM. */
    public boolean webOnly() {
        return sp.getBoolean(K_WEB_ONLY, false);
    }

    public void webOnly(boolean v) {
        sp.edit().putBoolean(K_WEB_ONLY, v).apply();
    }

    // ------------------------------------------------------------ dashboard

    /**
     * Serve the dashboard on every interface instead of loopback only, so a
     * laptop on the same network (or on Tailscale) can open it. Hermes refuses
     * a non-loopback bind without an auth provider, so this only works together
     * with {@link #webUser()} and {@link #webPassword()}.
     */
    public boolean webNetwork() {
        return sp.getBoolean(K_WEB_NETWORK, false);
    }

    public void webNetwork(boolean v) {
        sp.edit().putBoolean(K_WEB_NETWORK, v).apply();
    }

    public String webUser() {
        String v = sp.getString(K_WEB_USER, "");
        return v == null ? "" : v;
    }

    public void webUser(String v) {
        sp.edit().putString(K_WEB_USER, v == null ? "" : v.trim()).apply();
    }

    /** Kept in app-private preferences; never written into config.yaml. */
    public String webPassword() {
        String v = sp.getString(K_WEB_PASSWORD, "");
        return v == null ? "" : v;
    }

    public void webPassword(String v) {
        sp.edit().putString(K_WEB_PASSWORD, v == null ? "" : v).apply();
    }

    /**
     * Signing key for dashboard login cookies. Without one the agent generates
     * a random key per process, so every dashboard restart would log the user
     * out; one stable key keeps the login alive across restarts. Generated on
     * first use and never leaves the phone.
     */
    public String webSecret() {
        String v = sp.getString(K_WEB_SECRET, "");
        if (v != null && !v.isEmpty()) return v;
        byte[] raw = new byte[32];
        new java.security.SecureRandom().nextBytes(raw);
        v = android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP);
        sp.edit().putString(K_WEB_SECRET, v).apply();
        return v;
    }

    /**
     * Bind address for {@code hermes dashboard}, as {@code --host} wants it.
     * Without the login, a non-loopback bind is refused by Hermes itself, so a
     * half-configured setting falls back to loopback instead of a dead server.
     */
    public String webHost() {
        return webNetwork() && webAuthReady() ? "0.0.0.0" : "127.0.0.1";
    }

    /** True when the dashboard can safely be bound beyond loopback. */
    public boolean webAuthReady() {
        return !webUser().isEmpty() && webPassword().length() >= 8;
    }

    /**
     * Compatibility mode: start chroot-ng with {@code -R} (ahead-of-time rewriting).
     * Needed on a few Android 10+ kernels that dislike PRoot's seccomp
     * acceleration; noticeably slower, so it is off by default.
     */
    public boolean compatMode() {        return sp.getBoolean(K_COMPAT, false);
    }

    public void compatMode(boolean v) {
        sp.edit().putBoolean(K_COMPAT, v).apply();
    }

    public int columns() {
        return sp.getInt(K_COLUMNS, 80);
    }

    public int rows() {
        return sp.getInt(K_ROWS, 40);
    }

    public void terminalSize(int columns, int rows) {
        sp.edit().putInt(K_COLUMNS, columns).putInt(K_ROWS, rows).apply();
    }

    /** 0 = small, 1 = normal, 2 = large, 3 = extra large. */
    public int fontScale() {
        return Math.max(0, Math.min(3, sp.getInt(K_FONT, 1)));
    }

    public void fontScale(int v) {
        sp.edit().putInt(K_FONT, Math.max(0, Math.min(3, v))).apply();
    }

    /**
     * Show the header and the Termux-style two-row soft-key bar above the input
     * line. Off leaves a slim strip with the two toggles and hides the header,
     * so the console gets the whole screen — Termux's focus mode.
     */
    public boolean extraKeys() {
        return sp.getBoolean(K_EXTRA_KEYS, true);
    }

    public void extraKeys(boolean v) {
        sp.edit().putBoolean(K_EXTRA_KEYS, v).apply();
    }

    /** Point size used by the console for the current {@link #fontScale()}. */
    public float terminalFontSp() {
        switch (fontScale()) {
            case 0:
                return 11.5f;
            case 2:
                return 14.5f;
            case 3:
                return 16.5f;
            default:
                return 12.5f;
        }
    }

    /** Drops every setting, so the next start behaves like a fresh install. */
    public void reset() {
        sp.edit().clear().apply();
    }

    // ---------------------------------------------------------- phone folders

    /** Folders picked with {@code ACTION_OPEN_DOCUMENT_TREE}, in pick order. */
    public List<Root> roots() {
        List<Root> out = new ArrayList<>();
        String raw = sp.getString(K_ROOTS, "");
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                String uri = o.optString("uri", "");
                String label = o.optString("label", "");
                if (!uri.isEmpty() && !label.isEmpty()) out.add(new Root(uri, label));
            }
        } catch (JSONException ignored) {
            // A malformed list means "no folders"; the picker can add them again.
        }
        return out;
    }

    private void roots(List<Root> roots) {
        JSONArray array = new JSONArray();
        for (Root r : roots) {
            JSONObject o = new JSONObject();
            try {
                o.put("uri", r.uri);
                o.put("label", r.label);
            } catch (JSONException ignored) {
                continue;
            }
            array.put(o);
        }
        sp.edit().putString(K_ROOTS, array.toString()).apply();
    }

    /** Adds a folder, deriving a unique, shell-friendly label from {@code name}. */
    public Root addRoot(String uri, String name) {
        List<Root> existing = roots();
        String label = uniqueLabel(name, existing);
        existing.add(new Root(uri, label));
        roots(existing);
        return new Root(uri, label);
    }

    public void removeRoot(String uri) {
        List<Root> existing = roots();
        List<Root> kept = new ArrayList<>();
        for (Root r : existing) {
            if (!r.uri.equals(uri)) kept.add(r);
        }
        roots(kept);
    }

    /**
     * {@code "My Projects"} → {@code "My_Projects"}, {@code "Загрузки"} →
     * {@code "Загрузки"}. Paths end up in shell prompts and in the bind mount,
     * so spaces and quotes are replaced; letters and digits are left alone.
     */
    public static String uniqueLabel(String name, List<Root> taken) {
        String base = sanitizeLabel(name);
        String candidate = base;
        for (int n = 2; n < 100; n++) {
            boolean used = false;
            for (Root r : taken) {
                if (r.label.equalsIgnoreCase(candidate)) {
                    used = true;
                    break;
                }
            }
            if (!used) return candidate;
            candidate = base + "-" + n;
        }
        return base + "-" + System.currentTimeMillis();
    }

    private static String sanitizeLabel(String name) {
        StringBuilder sb = new StringBuilder();
        String text = name == null ? "" : name.trim();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append('_');
            }
        }
        String out = sb.toString();
        while (out.startsWith(".") || out.startsWith("-")) {
            out = out.substring(1);
        }
        if (out.length() > 40) out = out.substring(0, 40);
        return out.isEmpty() ? "folder" : out;
    }
}

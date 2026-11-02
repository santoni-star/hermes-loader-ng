package com.hermes.loader;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.hermes.loader.bootstrap.BundleInstaller;

import java.util.List;

/**
 * The settings screen.
 *
 * <p>Settings used to be a dialog with five free-text fields and no hints, which
 * made it impossible to tell what a valid value looked like. This version uses
 * sections, fixed choices for enumerations (profile, font size) and switches
 * with one-line explanations, validates on save, and keeps the destructive
 * maintenance actions in one clearly labelled block.
 */
public final class SettingsDialog {

    /** What the settings screen can trigger back in the activity. */
    public interface Actions {
        void reinstallRootfs();

        void wipeEverything();

        void updateAgent();

        void batterySettings();

        /** Re-applies the console font size without restarting the session. */
        void applyTerminalFont();

        /** Shows or hides the extra keys bar after the setting was saved. */
        void applyExtraKeys();

        /** The loopback URL the in-app pane uses; shown so it can be copied. */
        String dashboardUrl();

        /** Hands the dashboard URL to an installed browser. */
        void openDashboardInBrowser();

        void copyDashboardLink();

        /** One dashboard URL per line for this phone's LAN/Tailscale addresses. */
        String dashboardAddresses();

        /** Opens the system folder picker; the activity imports what was picked. */
        void addStorageFolder();

        void importFolder(Prefs.Root root);

        void exportFolder(Prefs.Root root);

        void removeFolder(Prefs.Root root);

        /** True when photo/video access is granted, fully or for selected items. */
        boolean mediaGranted();

        void grantMediaAccess();

        /** Restarts an active auxiliary dashboard after its access settings change. */
        boolean applyDashboardSettings();
    }

    private static final String[] PROFILE_IDS = {"all", "core", "termux"};
    private static final String[] PROFILE_TEXT = {
            "Full — all bundled tools (recommended)",
            "Core — smallest install",
            "Termux — Android-focused tools",
    };

    private static final String[] FONT_TEXT = {
            "Small", "Normal", "Large", "Extra large",
    };

    private SettingsDialog() {
    }

    public static void show(final Context ctx, final Prefs prefs, final Actions actions) {
        final LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF141B22);
        int pad = dp(ctx, 20);
        box.setPadding(pad, dp(ctx, 4), pad, dp(ctx, 6));

        // ------------------------------------------------------------- agent
        box.addView(section(ctx, "AGENT & INSTALL SOURCE"));

        final EditText repo = field(ctx, box, "Repository", prefs.repo(),
                Prefs.DEFAULT_REPO, InputType.TYPE_TEXT_VARIATION_URI);
        final EditText branch = field(ctx, box, "Branch", prefs.branch(),
                Prefs.DEFAULT_BRANCH, InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        box.addView(caption(ctx, "Choose what to install"));
        final RadioGroup profiles = new RadioGroup(ctx);
        profiles.setOrientation(RadioGroup.VERTICAL);
        final RadioButton[] profileButtons = new RadioButton[PROFILE_IDS.length];
        for (int i = 0; i < PROFILE_IDS.length; i++) {
            profileButtons[i] = radio(ctx, profiles, PROFILE_TEXT[i]);
            if (PROFILE_IDS[i].equals(prefs.profile())) {
                profileButtons[i].setChecked(true);
            }
        }
        box.addView(profiles);

        final Switch gatewayWait = toggle(ctx, box, "Wait for the gateway before START",
                "Recommended. Wait for the gateway to finish starting before opening the agent.",
                prefs.gatewayPrewarm());

        box.addView(section(ctx, "WEB STARTUP"));
        final Switch webOnly = toggle(ctx, box, "WEB-only when starting from idle",
                "Fast mode: starts the dashboard without the console or PTY. The WEB "
                        + "loading screen appears first; backend startup continues in the "
                        + "background. Use STOP, then START AGENT to open a prompt.",
                prefs.webOnly());

        // ------------------------------------------------------------- linux
        box.addView(section(ctx, "TERMINAL & COMPATIBILITY"));

        final Switch pty = toggle(ctx, box, "Emulate a real terminal (PTY)",
                "Provides interactive terminal input. Turn off only if the console is blank.",
                prefs.pty());
        final Switch compat = toggle(ctx, box, "chroot-ng compatibility mode",
                "Use only if this device reports “Function not implemented”.",
                prefs.compatMode());
        final EditText rootfs = field(ctx, box, "Custom rootfs URL (optional)",
                prefs.rootfsUrl(), "https://…/rootfs.tar.xz",
                InputType.TYPE_TEXT_VARIATION_URI);

        // ------------------------------------------------------ quick install
        box.addView(section(ctx, "INSTALL SPEED"));
        box.addView(caption(ctx, "Download a prepared Linux image instead of installing "
                + "packages on the phone. The download is large; Wi-Fi is recommended."));

        final Switch bundle = toggle(ctx, box, "Use a prebuilt bundle",
                "Recommended. Faster setup; your personal storage is preserved.",
                prefs.useBundle());
        final EditText bundleUrl = field(ctx, box, "Bundle manifest URL (optional)",
                prefs.bundleUrl(), BundleInstaller.DEFAULT_MANIFEST_URL,
                InputType.TYPE_TEXT_VARIATION_URI);

        // ---------------------------------------------------------- terminal
        box.addView(section(ctx, "DISPLAY"));

        box.addView(caption(ctx, "Font size"));
        final RadioGroup fonts = new RadioGroup(ctx);
        fonts.setOrientation(RadioGroup.VERTICAL);
        final RadioButton[] fontButtons = new RadioButton[FONT_TEXT.length];
        for (int i = 0; i < FONT_TEXT.length; i++) {
            fontButtons[i] = radio(ctx, fonts, FONT_TEXT[i]);
            if (i == prefs.fontScale()) {
                fontButtons[i].setChecked(true);
            }
        }
        box.addView(fonts);

        final Switch focus = toggle(ctx, box, "Focus mode",
                "Hide the header and extra keys for more terminal space. Tap ≡ to restore them.",
                !prefs.extraKeys());

        final AlertDialog[] dialog = new AlertDialog[1];

        // --------------------------------------------------------- dashboard
        box.addView(section(ctx, "WEB DASHBOARD"));

        box.addView(caption(ctx, "WEB opens the dashboard beside your agent. TERM returns "
                + "to the same live console."));

        final Switch network = toggle(ctx, box, "Reachable from other devices",
                "Allow access from your local network. A username and password are required.",
                prefs.webNetwork());

        final EditText webUser = field(ctx, box, "Dashboard login (username)",
                prefs.webUser(), "hermes",
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final EditText webPass = field(ctx, box, "Dashboard login (password)",
                prefs.webPassword(), "at least 8 characters",
                InputType.TYPE_TEXT_VARIATION_PASSWORD
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        TextView link = summary(ctx, actions.dashboardUrl());
        link.setTextColor(0xFF7BE0C3);
        link.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        link.setTypeface(Typeface.MONOSPACE);
        link.setTextIsSelectable(true);
        link.setPadding(0, dp(ctx, 6), 0, dp(ctx, 6));
        box.addView(link);

        String addresses = actions.dashboardAddresses();
        if (!addresses.isEmpty()) {
            TextView other = summary(ctx, "From another device:\n" + addresses);
            other.setTextColor(0xFF9FC49F);
            other.setTypeface(Typeface.MONOSPACE);
            other.setTextIsSelectable(true);
            box.addView(other);
        }

        LinearLayout dashButtons = new LinearLayout(ctx);
        dashButtons.setOrientation(LinearLayout.HORIZONTAL);
        dashButtons.addView(smallButton(ctx, "Open in browser",
                v -> close(dialog, actions::openDashboardInBrowser)));
        // Copying is a non-destructive action: keep the dialog open.
        dashButtons.addView(smallButton(ctx, "Copy link",
                v -> actions.copyDashboardLink()));
        box.addView(dashButtons);

        // ------------------------------------------------------- phone storage
        box.addView(section(ctx, "PHONE FILES & MEDIA"));

        box.addView(caption(ctx, "Add folders to work with them in Linux. Import copies "
                + "files in; Export copies your changes back."));

        List<Prefs.Root> roots = prefs.roots();
        if (roots.isEmpty()) {
            box.addView(summary(ctx, "No folders yet — add one below."));
        } else {
            for (Prefs.Root root : roots) {
                folderRow(ctx, box, root, dialog, actions);
            }
        }

        actionRow(ctx, box, "Add a phone folder…", v -> {
            close(dialog, actions::addStorageFolder);
        });

        boolean media = actions.mediaGranted();
        box.addView(caption(ctx, "Photos and videos"));
        box.addView(summary(ctx, media
                ? "Access granted. Used for gallery uploads from the dashboard. "
                  + "It does not add any path inside Linux."
                : "Not granted. The dashboard can still pick files through the system "
                  + "picker; this grant is only needed for direct gallery access."));
        actionRow(ctx, box, media ? "Media access granted" : "Allow photo and video access", v -> {
            if (actions.mediaGranted()) {
                Toast.makeText(ctx, "Already granted.", Toast.LENGTH_SHORT).show();
            } else {
                close(dialog, actions::grantMediaAccess);
            }
        });

        // ----------------------------------------------------------- updates
        box.addView(section(ctx, "UPDATES"));

        box.addView(caption(ctx, "Updates are manual. START never checks for updates."));
        actionRow(ctx, box, "Update the agent now",
                v -> close(dialog, actions::updateAgent));

        // ------------------------------------------------------- maintenance
        box.addView(section(ctx, "MAINTENANCE"));
        box.addView(caption(ctx, "These actions can remove installed files or personal data."));

        actionRow(ctx, box, "Reinstall Linux rootfs",
                v -> close(dialog, actions::reinstallRootfs));
        actionRow(ctx, box, "Wipe everything (incl. storage/)",
                v -> close(dialog, actions::wipeEverything));
        actionRow(ctx, box, "Battery optimization settings",
                v -> close(dialog, actions::batterySettings));

        ScrollView scroll = new ScrollView(ctx);
        scroll.setBackgroundColor(0xFF141B22);
        scroll.addView(box);

        dialog[0] = new AlertDialog.Builder(ctx)
                .setTitle("Settings")
                .setView(scroll)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Defaults", null)
                .create();

        dialog[0].setOnShowListener(d -> {
            if (dialog[0].getWindow() != null) {
                GradientDrawable panel = new GradientDrawable();
                panel.setColor(0xFF141B22);
                panel.setCornerRadius(dp(ctx, 18));
                panel.setStroke(dp(ctx, 1), 0xFF26343B);
                dialog[0].getWindow().setBackgroundDrawable(panel);
                dialog[0].getWindow().setDimAmount(0.72f);
            }
            dialog[0].getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xFF7BE0C3);
            dialog[0].getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(0xFFB9C9C9);
            dialog[0].getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(0xFF7BE0C3);
            dialog[0].getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                boolean bootChanged = !repo.getText().toString().trim().equals(prefs.repo())
                        || !branch.getText().toString().trim().equals(prefs.branch())
                        || !profileOf(profileButtons).equals(prefs.profile())
                        || gatewayWait.isChecked() != prefs.gatewayPrewarm()
                        || webOnly.isChecked() != prefs.webOnly()
                        || pty.isChecked() != prefs.pty()
                        || compat.isChecked() != prefs.compatMode();
                boolean installChanged = !rootfs.getText().toString().trim().equals(prefs.rootfsUrl())
                        || bundle.isChecked() != prefs.useBundle()
                        || !bundleUrl.getText().toString().trim().equals(prefs.bundleUrl());
                boolean dashboardChanged = network.isChecked() != prefs.webNetwork()
                        || !webUser.getText().toString().trim().equals(prefs.webUser())
                        || !webPass.getText().toString().equals(prefs.webPassword());
                if (save(ctx, prefs, repo, branch, profileButtons, gatewayWait, pty, compat,
                        webOnly, rootfs, bundle, bundleUrl, network, webUser, webPass,
                        fontButtons, focus)) {
                    actions.applyTerminalFont();
                    actions.applyExtraKeys();
                    boolean restartingDashboard = dashboardChanged
                            && actions.applyDashboardSettings();
                    Toast.makeText(ctx, saveNotice(bootChanged, installChanged,
                                    dashboardChanged, restartingDashboard), Toast.LENGTH_LONG)
                            .show();
                    dialog[0].dismiss();
                }
            });
            dialog[0].getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                repo.setText(Prefs.DEFAULT_REPO);
                branch.setText(Prefs.DEFAULT_BRANCH);
                profileButtons[0].setChecked(true);
                gatewayWait.setChecked(true);
                webOnly.setChecked(false);
                pty.setChecked(true);
                compat.setChecked(false);
                rootfs.setText("");
                bundle.setChecked(true);
                bundleUrl.setText("");
                network.setChecked(false);
                webUser.setText("");
                webPass.setText("");
                fontButtons[1].setChecked(true);
                focus.setChecked(false);
                Toast.makeText(ctx, "Defaults restored — press Save to apply.",
                        Toast.LENGTH_SHORT).show();
            });
        });

        dialog[0].show();
    }

    private static String saveNotice(boolean bootChanged, boolean installChanged,
                                     boolean dashboardChanged, boolean restartingDashboard) {
        StringBuilder message = new StringBuilder("Saved. Display changes apply now.");
        if (bootChanged) {
            message.append(" Agent startup settings apply the next time you start the agent.");
        }
        if (installChanged) {
            message.append(" Install source changes apply on the next install or rootfs reinstall.");
        }
        if (dashboardChanged) {
            message.append(restartingDashboard
                    ? " Restarting the active dashboard to apply access changes."
                    : " Dashboard access changes apply when the dashboard next starts.");
        }
        return message.toString();
    }

    private static boolean save(Context ctx, Prefs prefs, EditText repo, EditText branch,
                                RadioButton[] profiles, Switch gatewayWait, Switch pty, Switch compat,
                                Switch webOnly, EditText rootfs, Switch bundle, EditText bundleUrl,
                                Switch network, EditText webUser, EditText webPass,
                                RadioButton[] fonts, Switch focus) {
        String repoText = repo.getText().toString().trim();
        if (!validRepo(repoText)) {
            Toast.makeText(ctx, "Repository must start with https://, ssh:// or git@",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        String branchText = branch.getText().toString().trim();
        if (branchText.isEmpty() || branchText.contains(" ")) {
            Toast.makeText(ctx, "Branch name is not valid.", Toast.LENGTH_LONG).show();
            return false;
        }
        String url = rootfs.getText().toString().trim();
        if (!url.isEmpty() && !url.startsWith("https://") && !url.startsWith("http://")) {
            Toast.makeText(ctx, "Rootfs URL must be an http(s) link.", Toast.LENGTH_LONG).show();
            return false;
        }
        String bundleLink = bundleUrl.getText().toString().trim();
        if (!bundleLink.isEmpty()
                && !bundleLink.startsWith("https://") && !bundleLink.startsWith("http://")) {
            Toast.makeText(ctx, "Bundle manifest URL must be an http(s) link.",
                    Toast.LENGTH_LONG).show();
            return false;
        }

        String user = webUser.getText().toString().trim();
        String pass = webPass.getText().toString();
        if (network.isChecked()) {
            // Hermes refuses a non-loopback bind without an auth provider, so
            // saving this combination would only produce a dashboard that dies
            // at startup.
            if (user.isEmpty()) {
                Toast.makeText(ctx, "Set a dashboard username before opening it to the "
                        + "network.", Toast.LENGTH_LONG).show();
                return false;
            }
            if (pass.length() < 8) {
                Toast.makeText(ctx, "The dashboard password needs at least 8 characters.",
                        Toast.LENGTH_LONG).show();
                return false;
            }
        }

        prefs.repo(repoText);
        prefs.branch(branchText);
        prefs.profile(profileOf(profiles));
        prefs.gatewayPrewarm(gatewayWait.isChecked());
        prefs.webOnly(webOnly.isChecked());
        prefs.pty(pty.isChecked());
        prefs.compatMode(compat.isChecked());
        prefs.rootfsUrl(url);
        prefs.useBundle(bundle.isChecked());
        prefs.bundleUrl(bundleLink);
        prefs.webNetwork(network.isChecked());
        prefs.webUser(user);
        prefs.webPassword(pass);
        prefs.fontScale(fontOf(fonts));
        prefs.extraKeys(!focus.isChecked());
        return true;
    }

    private static String profileOf(RadioButton[] buttons) {
        for (int i = 0; i < buttons.length; i++) {
            if (buttons[i].isChecked()) {
                return PROFILE_IDS[i];
            }
        }
        return "all";
    }

    private static int fontOf(RadioButton[] buttons) {
        for (int i = 0; i < buttons.length; i++) {
            if (buttons[i].isChecked()) {
                return i;
            }
        }
        return 1;
    }

    private static boolean validRepo(String value) {
        return value.startsWith("https://")
                || value.startsWith("http://")
                || value.startsWith("ssh://")
                || value.startsWith("git@");
    }

    private static void close(AlertDialog[] dialog, Runnable action) {
        if (dialog[0] != null) {
            dialog[0].dismiss();
        }
        action.run();
    }

    // --------------------------------------------------------------- widgets

    private static TextView section(Context ctx, String title) {
        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextColor(0xFF7BE0C3);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tv.setLetterSpacing(0.12f);
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable badge = new GradientDrawable();
        badge.setColor(0x1C7BE0C3);
        badge.setCornerRadius(dp(ctx, 6));
        tv.setBackground(badge);
        tv.setPadding(dp(ctx, 10), dp(ctx, 7), dp(ctx, 10), dp(ctx, 7));
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) tv.getLayoutParams()).topMargin = dp(ctx, 14);
        return tv;
    }

    private static TextView caption(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFFB2C0C2);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(0, dp(ctx, 10), 0, dp(ctx, 2));
        return tv;
    }

    private static TextView title(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFFDFE9E8);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        return tv;
    }

    private static TextView summary(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFF819398);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setPadding(0, dp(ctx, 2), dp(ctx, 8), 0);
        return tv;
    }

    private static EditText field(Context ctx, LinearLayout box, String label,
                                  String value, String hint, int variation) {
        box.addView(caption(ctx, label));
        EditText e = new EditText(ctx);
        e.setText(value);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setTextColor(0xFFDFE9E8);
        e.setHintTextColor(0xFF819398);
        e.setInputType(InputType.TYPE_CLASS_TEXT | variation);
        e.setPadding(0, dp(ctx, 4), 0, dp(ctx, 6));
        box.addView(e);
        return e;
    }

    private static RadioButton radio(Context ctx, RadioGroup group, String text) {
        RadioButton b = new RadioButton(ctx);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTextColor(0xFFDFE9E8);
        b.setPadding(0, dp(ctx, 4), 0, dp(ctx, 4));
        group.addView(b);
        return b;
    }

    private static Switch toggle(Context ctx, LinearLayout box, String titleText,
                                 String summaryText, boolean checked) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(ctx, 8), 0, dp(ctx, 4));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(title(ctx, titleText));
        texts.addView(summary(ctx, summaryText));
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch s = new Switch(ctx);
        s.setChecked(checked);
        s.setShowText(false);
        row.addView(s);
        box.addView(row);
        return s;
    }

    /**
     * One folder row: name and guest path on top, and the three things that can
     * be done with it below. Buttons are equal-width so the row stays readable
     * on a narrow screen.
     */
    private static void folderRow(Context ctx, LinearLayout box, Prefs.Root root,
                                  AlertDialog[] dialog, Actions actions) {
        LinearLayout block = new LinearLayout(ctx);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(ctx, 10), 0, 0);

        block.addView(title(ctx, root.label));
        block.addView(summary(ctx, "/mnt/android/" + root.label));

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(smallButton(ctx, "Import",
                v -> close(dialog, () -> actions.importFolder(root))));
        buttons.addView(smallButton(ctx, "Export",
                v -> close(dialog, () -> actions.exportFolder(root))));
        buttons.addView(smallButton(ctx, "Remove",
                v -> close(dialog, () -> actions.removeFolder(root))));
        block.addView(buttons);

        box.addView(block);
    }

    private static Button smallButton(Context ctx, String text,
                                      View.OnClickListener listener) {
        Button b = new Button(ctx, null, 0, R.style.PanelButton);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setOnClickListener(listener);
        b.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private static void actionRow(Context ctx, LinearLayout box, String text,
                                  View.OnClickListener listener) {
        Button b = new Button(ctx, null, 0, R.style.PanelButton);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        box.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static int dp(Context ctx, int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }
}

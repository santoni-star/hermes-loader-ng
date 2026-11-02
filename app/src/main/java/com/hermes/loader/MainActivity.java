package com.hermes.loader;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.hermes.loader.bootstrap.BundleInstaller;
import com.hermes.loader.bootstrap.Log;
import com.hermes.loader.bootstrap.NetFiles;
import com.hermes.loader.bootstrap.GuestCommand;
import com.hermes.loader.bootstrap.NgInstaller;
import com.hermes.loader.bootstrap.RootfsInstaller;
import com.hermes.loader.bootstrap.Scripts;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Terminal UI + the few controls the loader needs. */
public class MainActivity extends Activity
        implements Session.Listener, Launcher.Ui, SettingsDialog.Actions {

    private static final int REQ_NOTIFICATIONS = 101;
    private static final int REQ_WEB_FILE = 102;
    private static final int REQ_TREE = 103;
    private static final int REQ_MEDIA = 104;
    private static final int COLOR_OK = 0xFF9BB9AF;
    private static final int COLOR_ACTIVE = 0xFF7BE0C3;
    private static final int COLOR_WORK = 0xFFE9BD78;
    private static final long OUTPUT_BATCH_MS = 16;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Object outputLock = new Object();
    private final StringBuilder pendingOutput = new StringBuilder();
    private final Runnable drainOutputTask = this::drainOutput;
    private boolean outputDrainScheduled;
    private volatile boolean disposed;

    private Prefs prefs;
    private TerminalView terminal;
    private EditText input;
    private TextView statusView;
    private TextView welcomeView;
    private Button startButton;
    private Button shellButton;
    private Button setupButton;
    private Button stopButton;
    private Button webButton;

    private LinearLayout terminalPane;
    private LinearLayout headerBox;
    private LinearLayout extraKeys;
    private LinearLayout extraKeysCompact;
    private View webExtraKeys;
    private Button ctrlKey;
    private Button altKey;
    /** Sticky one-shot modifiers, as in Termux. */
    private boolean ctrlArmed;
    private boolean altArmed;
    private FrameLayout webPane;
    private WebView web;
    private TextView webOverlay;
    private ValueCallback<Uri[]> webFileCallback;
    private int webProbe;
    private int webReloads;

    /** Cancels a pending "open dashboard in a browser" wait when a new one starts. */
    private volatile int browserProbe;

    /** First {@code http(s)} link the Nous device flow printed for this login. */
    private volatile String loginUrlOpened;

    /** A URL printed by the CLI, without the surrounding markup or punctuation. */
    private static final java.util.regex.Pattern CLI_URL =
            java.util.regex.Pattern.compile("https?://[^\\s\"'<>()\\[\\]]+");

    /**
     * True when the pane shows a dashboard that no session owns: {@code hermes
     * dashboard} exited because another instance was already serving the port.
     * STOP must still be able to shut it down.
     */
    private boolean attached;

    /** Tap-to-first-output timing; 0 until a session is requested. */
    private long sessionRequestedAt;
    private boolean firstOutputSeen = true;
    /** Tap-to-HTTP-ready timing for the next dashboard startup. */
    private long dashboardRequestedAt;
    /** Time the fast WEB shell became visible, separate from backend readiness. */
    private long fastUiReadyAt;

    /** Guards the "did the guest come up?" probe; written on the UI thread. */
    private volatile boolean busy;

    /**
     * The dashboard that runs next to the console. START/STOP keep working on
     * the session; this only feeds the status line and the WEB pane.
     */
    private final AuxDashboard.Listener auxListener = code -> ui.post(() -> {
        updateStatus();
        applyRunningState(Session.get().running());
        if (webPane == null || webPane.getVisibility() != View.VISIBLE) return;
        Thread t = new Thread(() -> {
            boolean up = dashboardUp();
            ui.post(() -> {
                if (up) {
                    attached = true;
                    updateStatus();
                    return;
                }
                // The auxiliary process has exited; stop its readiness loop
                // instead of polling for a server that can no longer appear.
                webProbe++;
                dashboardFailed();
                if (code != 0) {
                    String why = AuxDashboard.get().lastLine();
                    toast("The dashboard stopped (code " + code + ")."
                            + (why.isEmpty() ? "" : "\n" + why));
                }
            });
        }, "dashboard-aux-exit");
        t.setDaemon(true);
        t.start();
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        buildUi();

        Session.get().addListener(this);
        AuxDashboard.get().addListener(auxListener);
        if (Session.get().running()) {
            terminal.append(Session.get().backlog());
            welcomeView.setVisibility(View.GONE);
            applyRunningState(true);
        } else if (terminal.dump().trim().isEmpty()) {
            welcome();
        }
        maybeAskForNotifications();
    }

    @Override
    protected void onDestroy() {
        disposed = true;
        Session.get().removeListener(this);
        AuxDashboard.get().removeListener(auxListener);
        ui.removeCallbacks(drainOutputTask);
        synchronized (outputLock) {
            pendingOutput.setLength(0);
            outputDrainScheduled = false;
        }
        webProbe++;
        if (web != null) {
            try {
                webPane.removeView(web);
                web.destroy();
            } catch (RuntimeException ignored) {
                // The WebView may already be detached.
            }
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webPane != null && webPane.getVisibility() == View.VISIBLE) {
            if (web != null && web.canGoBack()) {
                web.goBack();
            } else {
                showTerminal();
            }
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_TREE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                addStorageRoot(data.getData());
            }
            return;
        }
        if (requestCode != REQ_WEB_FILE) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (webFileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) {
                    result[i] = clip.getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        webFileCallback.onReceiveValue(result);
        webFileCallback = null;
    }

    // ------------------------------------------------------------------- UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0C1015);
        root.setFitsSystemWindows(true);

        headerBox = new LinearLayout(this);
        headerBox.setOrientation(LinearLayout.VERTICAL);
        headerBox.addView(buildHeader());
        headerBox.addView(buildDivider());
        root.addView(headerBox);

        FrameLayout body = new FrameLayout(this);

        terminalPane = new LinearLayout(this);
        terminalPane.setOrientation(LinearLayout.VERTICAL);

        terminal = new TerminalView(this);
        terminal.setFontSp(prefs.terminalFontSp());
        FrameLayout console = new FrameLayout(this);
        console.addView(terminal, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        welcomeView = new TextView(this);
        welcomeView.setTextColor(0xFFB9C9C9);
        welcomeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        welcomeView.setTypeface(TerminalView.typeface(this));
        welcomeView.setLineSpacing(dp(2), 1f);
        welcomeView.setPadding(dp(14), dp(10), dp(14), dp(10));
        welcomeView.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        welcomeView.setVisibility(View.GONE);
        console.addView(welcomeView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        terminalPane.addView(console, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        terminalPane.addView(buildDivider());
        terminalPane.addView(buildExtraKeys());
        terminalPane.addView(buildInput());

        body.addView(terminalPane, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        body.addView(buildWebPane(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Initially show terminal, hide web pane.
        terminalPane.setVisibility(View.VISIBLE);
        webPane.setVisibility(View.GONE);

        setContentView(root);
        updateStatus();
    }

    /**
     * A clear primary action, then a compact row of secondary controls.
     */
    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(0xFF141B22);
        header.setPadding(dp(16), dp(10), dp(16), dp(10));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("HERMES");
        title.setTextColor(0xFFE5F0ED);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setLetterSpacing(0.08f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("LOCAL AI WORKSPACE");
        subtitle.setTextColor(0xFF819398);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        subtitle.setLetterSpacing(0.12f);
        brand.addView(subtitle);
        top.addView(brand, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statusView = new TextView(this);
        statusView.setTextColor(COLOR_OK);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        statusView.setSingleLine(true);
        statusView.setEllipsize(android.text.TextUtils.TruncateAt.START);
        statusView.setGravity(Gravity.END);
        statusView.setPadding(dp(10), dp(6), dp(10), dp(6));
        statusView.setBackground(statusBackground(COLOR_OK));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.rightMargin = dp(8);
        top.addView(statusView, statusLp);

        Button menu = new Button(this, null, 0, R.style.KeyButton);
        menu.setText("\u22ee");
        menu.setAllCaps(false);
        menu.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        menu.setContentDescription(getString(R.string.menu_title));
        menu.setOnClickListener(v -> showMenu());
        top.addView(menu, new LinearLayout.LayoutParams(dp(42), dp(38)));
        header.addView(top);

        LinearLayout primaryRow = new LinearLayout(this);
        primaryRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams primaryRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        primaryRowLp.topMargin = dp(12);
        startButton = actionButton(R.string.action_start_agent, R.style.PrimaryButton,
                v -> startMode(GuestCommand.MODE_BOOT_FAST));
        startButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        primaryRow.addView(startButton, new LinearLayout.LayoutParams(0, dp(46), 1f));
        webButton = actionButton(R.string.btn_web, R.style.KeyButton, v -> toggleWeb());
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(dp(92), dp(46));
        webLp.leftMargin = dp(8);
        primaryRow.addView(webButton, webLp);
        header.addView(primaryRow, primaryRowLp);

        LinearLayout secondaryRow = new LinearLayout(this);
        secondaryRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams secondaryRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        secondaryRowLp.topMargin = dp(8);
        shellButton = actionButton(R.string.btn_shell, R.style.KeyButton,
                v -> startMode(GuestCommand.MODE_SHELL));
        setupButton = actionButton(R.string.btn_setup, R.style.KeyButton,
                v -> startMode(GuestCommand.MODE_SETUP));
        Button logButton = actionButton(R.string.btn_log, R.style.KeyButton,
                v -> showDashboardLog());
        stopButton = actionButton(R.string.btn_stop, R.style.DangerButton,
                v -> stopSession());
        secondaryRow.addView(shellButton, actionParams(1f, true));
        secondaryRow.addView(setupButton, actionParams(1f, true));
        secondaryRow.addView(logButton, actionParams(1f, true));
        secondaryRow.addView(stopButton, actionParams(1f, false));
        header.addView(secondaryRow, secondaryRowLp);
        return header;
    }

    private GradientDrawable statusBackground(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor((color & 0x00FFFFFF) | 0x1C000000);
        shape.setCornerRadius(dp(20));
        return shape;
    }

    private Button actionButton(int textRes, int style, View.OnClickListener l) {
        Button b = new Button(this, null, 0, style);
        b.setText(textRes);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams actionParams(float weight, boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), weight);
        if (gap) {
            lp.rightMargin = dp(6);
        }
        return lp;
    }

    /**
     * Soft keys laid out the way Termux does it:
     *
     * <pre>
     *   ≡   ESC   /   HOME   ↑   END   PGUP
     *   ⌫   CTRL  ALT  ←     ↓   →    PGDN
     * </pre>
     *
     * <p>≡ collapses the header and the bar into one slim strip (the choice is
     * remembered); ⇤ in that strip shows or hides the soft keyboard (the same
     * action is in ⋮). ⌫ deletes the last character of the staging line, or
     * sends DEL to the guest when the line is empty — that is how a partial
     * line that is already in the shell can be erased. CTRL and ALT are sticky
     * one-shot modifiers: they light up while armed and change the next key
     * (xterm's {@code ESC [ 1 ; 5 A} form) or the next line typed into the
     * input field. Long-press CTRL sends Ctrl-C without arming anything.
     */
    private View buildExtraKeys() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF141B22);

        extraKeys = new LinearLayout(this);
        extraKeys.setOrientation(LinearLayout.VERTICAL);
        extraKeys.setPadding(dp(4), dp(4), dp(4), dp(2));

        LinearLayout first = keyRow();

        Button collapse = extraKey("\u2261");
        collapse.setContentDescription(getString(R.string.keys_hide));
        collapse.setOnClickListener(v -> toggleExtraKeysBar());
        first.addView(collapse, cellParams());

        Button esc = extraKey("ESC");
        esc.setOnClickListener(v -> sendRaw(altArmed ? "\u001b\u001b" : "\u001b"));
        first.addView(esc, cellParams());

        Button slash = extraKey("/");
        slash.setOnClickListener(v -> {
            char base = ctrlArmed ? (char) 0x1f : '/';
            sendRaw((altArmed ? "\u001b" : "") + base);
        });
        first.addView(slash, cellParams());

        first.addView(sendKey("HOME", "\u001b[H", "1", 'H'), cellParams());
        first.addView(sendKey("\u2191", "\u001b[A", "1", 'A'), cellParams());
        first.addView(sendKey("END", "\u001b[F", "1", 'F'), cellParams());
        first.addView(sendKey("PGUP", "\u001b[5~", "5", '~'), cellParams());
        extraKeys.addView(first);

        LinearLayout second = keyRow();

        // Backspace sits where Termux has its keyboard toggle, because deleting
        // a character already sent to the guest (a partial line left by a sticky
        // Ctrl/Alt, pasted text, a shell prompt) was impossible before.
        Button backspace = extraKey("\u232b");
        backspace.setContentDescription(getString(R.string.keys_backspace));
        backspace.setOnClickListener(v -> {
            // With a staging line in the input field the key behaves like the
            // soft keyboard's backspace: edit the line, do not touch the guest.
            if (!ctrlArmed && !altArmed && input != null) {
                String staged = input.getText().toString();
                if (!staged.isEmpty()) {
                    input.setText(staged.substring(0, staged.length() - 1));
                    input.setSelection(input.getText().length());
                    input.requestFocus();
                    return;
                }
            }
            // Otherwise send DEL (0x7f), which is what a terminal Backspace is.
            // Alt+Backspace deletes a word in readline, Ctrl+Backspace is BS.
            if (altArmed) {
                sendRaw("\u001b\u007f");
            } else if (ctrlArmed) {
                sendRaw("\u0008");
            } else {
                sendRaw("\u007f");
            }
        });
        second.addView(backspace, cellParams());

        ctrlKey = extraKey("CTRL");
        ctrlKey.setOnClickListener(v -> {
            ctrlArmed = !ctrlArmed;
            paintModifier(ctrlKey, ctrlArmed);
        });
        ctrlKey.setOnLongClickListener(v -> {
            if (!Session.get().running()) {
                toast("Press START (or SHELL) first.");
                return true;
            }
            sendRaw("\u0003");
            toast("Ctrl-C sent.");
            return true;
        });
        second.addView(ctrlKey, cellParams());

        altKey = extraKey("ALT");
        altKey.setOnClickListener(v -> {
            altArmed = !altArmed;
            paintModifier(altKey, altArmed);
        });
        second.addView(altKey, cellParams());

        second.addView(sendKey("\u2190", "\u001b[D", "1", 'D'), cellParams());
        second.addView(sendKey("\u2193", "\u001b[B", "1", 'B'), cellParams());
        second.addView(sendKey("\u2192", "\u001b[C", "1", 'C'), cellParams());
        second.addView(sendKey("PGDN", "\u001b[6~", "6", '~'), cellParams());
        extraKeys.addView(second);

        box.addView(extraKeys, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // What is left when the bar is collapsed: the two toggles, so it can
        // always be brought back without opening a menu.
        extraKeysCompact = new LinearLayout(this);
        extraKeysCompact.setOrientation(LinearLayout.HORIZONTAL);
        extraKeysCompact.setGravity(Gravity.CENTER_VERTICAL);
        extraKeysCompact.setPadding(dp(4), dp(4), dp(10), dp(4));

        Button expand = extraKey("\u2261");
        expand.setContentDescription(getString(R.string.keys_show));
        expand.setOnClickListener(v -> toggleExtraKeysBar());
        extraKeysCompact.addView(expand,
                new LinearLayout.LayoutParams(dp(52), dp(34)));

        Button keyboard2 = extraKey("\u21e4");
        keyboard2.setContentDescription(getString(R.string.keys_keyboard));
        keyboard2.setOnClickListener(v -> toggleSoftKeyboard());
        LinearLayout.LayoutParams kbLp =
                new LinearLayout.LayoutParams(dp(52), dp(34));
        kbLp.leftMargin = dp(4);
        extraKeysCompact.addView(keyboard2, kbLp);

        TextView hint = new TextView(this);
        hint.setText(R.string.keys_hidden);
        hint.setTextColor(0xFF819398);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setPadding(dp(10), 0, 0, 0);
        extraKeysCompact.addView(hint, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        box.addView(extraKeysCompact, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        refreshExtraKeys();
        return box;
    }

    /**
     * JavaScript interface for the extra keys panel in WebView.
     * Called from the native extra keys buttons to send keys to xterm.js.
     */
    public static class WebKeyInterface {
        private MainActivity activity;

        WebKeyInterface() {
        }

        void setActivity(MainActivity activity) {
            this.activity = activity;
        }

        /**
         * Sends a raw key sequence to the xterm.js terminal.
         * This is called from the native extra keys panel buttons.
         */
        @android.webkit.JavascriptInterface
        public void sendKey(String data) {
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    if (activity.web != null) {
                        // Escape for JavaScript string literal
                        String escaped = data
                                .replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r")
                                .replace("\t", "\\t");
                        activity.web.evaluateJavascript(
                                "if (window.term && window.term.input) window.term.input('" + escaped + "');",
                                null);
                    }
                });
            }
        }
    }

    private View buildWebExtraKeys() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF141B22);
        box.setPadding(dp(4), dp(4), dp(4), dp(2));

        LinearLayout first = keyRow();

        Button collapse = extraKey("\u2261");
        collapse.setContentDescription(getString(R.string.keys_hide));
        collapse.setOnClickListener(v -> toggleExtraKeysBar());
        first.addView(collapse, cellParams());

        Button esc = extraKey("ESC");
        esc.setOnClickListener(v -> sendWebKey(altArmed ? "\u001b\u001b" : "\u001b"));
        first.addView(esc, cellParams());

        Button slash = extraKey("/");
        slash.setOnClickListener(v -> {
            char base = ctrlArmed ? (char) 0x1f : '/';
            sendWebKey((altArmed ? "\u001b" : "") + base);
        });
        first.addView(slash, cellParams());

        first.addView(webSendKey("HOME", "\u001b[H", "1", 'H'), cellParams());
        first.addView(webSendKey("\u2191", "\u001b[A", "1", 'A'), cellParams());
        first.addView(webSendKey("END", "\u001b[F", "1", 'F'), cellParams());
        first.addView(webSendKey("PGUP", "\u001b[5~", "5", '~'), cellParams());
        box.addView(first);

        LinearLayout second = keyRow();

        Button backspace = extraKey("\u232b");
        backspace.setContentDescription(getString(R.string.keys_backspace));
        backspace.setOnClickListener(v -> {
            if (!ctrlArmed && !altArmed && input != null) {
                String staged = input.getText().toString();
                if (!staged.isEmpty()) {
                    input.setText(staged.substring(0, staged.length() - 1));
                    input.setSelection(input.getText().length());
                    input.requestFocus();
                    return;
                }
            }
            if (altArmed) {
                sendWebKey("\u001b\u007f");
            } else if (ctrlArmed) {
                sendWebKey("\u0008");
            } else {
                sendWebKey("\u007f");
            }
        });
        second.addView(backspace, cellParams());

        webCtrlKey = extraKey("CTRL");
        webCtrlKey.setOnClickListener(v -> {
            ctrlArmed = !ctrlArmed;
            paintModifier(webCtrlKey, ctrlArmed);
        });
        webCtrlKey.setOnLongClickListener(v -> {
            if (!Session.get().running()) {
                toast("Press START (or SHELL) first.");
                return true;
            }
            sendWebKey("\u0003");
            toast("Ctrl-C sent.");
            return true;
        });
        second.addView(webCtrlKey, cellParams());

        webAltKey = extraKey("ALT");
        webAltKey.setOnClickListener(v -> {
            altArmed = !altArmed;
            paintModifier(webAltKey, altArmed);
        });
        second.addView(webAltKey, cellParams());

        second.addView(webSendKey("\u2190", "\u001b[D", "1", 'D'), cellParams());
        second.addView(webSendKey("\u2193", "\u001b[B", "1", 'B'), cellParams());
        second.addView(webSendKey("\u2192", "\u001b[C", "1", 'C'), cellParams());
        second.addView(webSendKey("PGDN", "\u001b[6~", "6", '~'), cellParams());
        box.addView(second);

        return box;
    }

    /**
     * Sends a key sequence to the dashboard's terminal.
     *
     * <p>The SPA owns the xterm.js instance, so there is no Java-side handle to
     * write to. The script below finds it in the order of likelihood —
     * {@code window.term} (the name the dashboard exposes), any xterm instance
     * reachable from the global object, then the helper textarea as a last
     * resort — and calls {@code input()} with the raw bytes.
     */
    private void sendWebKey(String data) {
        clearModifiers();
        if (!Session.get().running()) {
            toast("Press START (or SHELL) first.");
            return;
        }
        if (web == null) return;
        String escaped = data
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        String js = "(function(k){"
                + "try{"
                + "var t=window.term;"
                + "if(!t||!t.input){for(var n in window){try{var o=window[n];"
                + "if(o&&typeof o==='object'&&typeof o.input==='function'&&(o._core||o.buffer)){t=o;break}}catch(e){}}}"
                + "if(t&&typeof t.input==='function'){t.input(k);return 'xterm';}"
                + "var a=document.querySelector('.xterm-helper-textarea');"
                + "if(a){a.focus();document.execCommand('insertText',false,k);return 'textarea';}"
                + "}catch(e){}"
                + "return 'none';"
                + "})('" + escaped + "')";
        web.evaluateJavascript(js, null);
    }

    private Button webSendKey(String label, String plain, String prefix, char last) {
        Button b = extraKey(label);
        b.setOnClickListener(v -> {
            int m = 1 + (altArmed ? 2 : 0) + (ctrlArmed ? 4 : 0);
            sendWebKey(m == 1 ? plain : "\u001b[" + prefix + ";" + m + last);
        });
        return b;
    }

    private Button webCtrlKey;
    private Button webAltKey;

    private LinearLayout keyRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams cellParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        lp.leftMargin = dp(2);
        lp.rightMargin = dp(2);
        lp.topMargin = dp(2);
        return lp;
    }

    private Button extraKey(String label) {
        Button b = new Button(this, null, 0, R.style.KeyButton);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setPadding(0, 0, 0, 0);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        return b;
    }

    /**
     * A cursor/scroll key: {@code plain} when no modifier is armed, otherwise
     * the xterm form with the modifier parameter (3 = Alt, 5 = Ctrl,
     * 7 = Ctrl+Alt).
     */
    private Button sendKey(String label, String plain, String prefix, char last) {
        Button b = extraKey(label);
        b.setOnClickListener(v -> {
            int m = 1 + (altArmed ? 2 : 0) + (ctrlArmed ? 4 : 0);
            sendRaw(m == 1 ? plain : "\u001b[" + prefix + ";" + m + last);
        });
        return b;
    }

    /** Sends raw bytes to the guest and disarms a pending Ctrl/Alt, as Termux does. */
    private void sendRaw(String data) {
        clearModifiers();
        if (!Session.get().running()) {
            toast("Press START (or SHELL) first.");
            return;
        }
        Session.get().write(data);
        input.requestFocus();
    }

    private void paintModifier(Button b, boolean armed) {
        if (b == null) return;
        b.setTextColor(armed ? 0xFF0C2420 : 0xFFB9C9C9);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                armed ? 0xFF7BE0C3 : 0xFF141B22));
    }

    private void clearModifiers() {
        ctrlArmed = false;
        altArmed = false;
        paintModifier(ctrlKey, false);
        paintModifier(altKey, false);
        paintModifier(webCtrlKey, false);
        paintModifier(webAltKey, false);
    }

    private void toggleExtraKeysBar() {
        prefs.extraKeys(!prefs.extraKeys());
        refreshExtraKeys();
    }

    private void refreshExtraKeys() {
        boolean expanded = prefs.extraKeys();
        // The dashboard pane has its own chrome and no way out of the app once
        // the header is gone, so WEB mode always keeps the header visible.
        boolean web = webPane != null && webPane.getVisibility() == View.VISIBLE;
        if (headerBox != null) {
            headerBox.setVisibility(expanded || web ? View.VISIBLE : View.GONE);
        }
        if (extraKeys != null) {
            extraKeys.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        if (extraKeysCompact != null) {
            extraKeysCompact.setVisibility(expanded ? View.GONE : View.VISIBLE);
        }
        if (webExtraKeys != null) {
            webExtraKeys.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        if (!expanded) clearModifiers();
    }

    /**
     * Termux's ⇤: hide the keyboard and drop focus, or focus the input and ask
     * for it again. Clearing the focus is what makes the second tap reliable
     * after the keyboard was dismissed with the system back gesture.
     */
    private void toggleSoftKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        if (input.hasFocus() && imm.isActive(input)) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            input.clearFocus();
        } else {
            input.requestFocus();
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private View buildInput() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(0xFF141B22);
        row.setPadding(dp(6), dp(4), dp(6), dp(6));

        input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        input.setTextColor(0xFFDFE9E8);
        input.setHintTextColor(0xFF819398);
        input.setHint(R.string.hint_input);
        input.setTypeface(TerminalView.typeface(this));
        input.setBackgroundColor(0xFF0C1015);
        input.setPadding(dp(10), dp(8), dp(10), dp(8));
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentInput();
                return true;
            }
            return false;
        });

        Button send = new Button(this, null, 0, R.style.KeyButton);
        send.setText("\u25b8");
        send.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        send.setAllCaps(false);
        send.setOnClickListener(v -> sendCurrentInput());

        row.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(40));
        lp.leftMargin = dp(6);
        row.addView(send, lp);
        return row;
    }

    private View buildDivider() {
        View v = new View(this);
        v.setBackgroundColor(0xFF26343B);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return v;
    }

    // ------------------------------------------------------------------- web

    /**
     * The dashboard runs as an ordinary guest process ({@code hermes dashboard}
     * on loopback) and is displayed here. It is the same web UI the desktop app
     * uses, so panels, tables and chat are rendered by the browser engine
     * instead of the small ANSI screen.
     */
    private View buildWebPane() {
        webPane = new FrameLayout(this);
        webPane.setBackgroundColor(0xFF0C1015);
        webPane.setVisibility(View.GONE);

        web = new WebView(this);
        configureWeb();
        webPane.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        webOverlay = new TextView(this);
        webOverlay.setGravity(Gravity.CENTER);
        webOverlay.setTextColor(0xFFDFE9E8);
        webOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        webOverlay.setBackgroundColor(0xFF0C1015);
        webOverlay.setPadding(dp(24), dp(24), dp(24), dp(24));
        webOverlay.setText(R.string.web_starting);
        webOverlay.setOnClickListener(v -> retryDashboard());
        webPane.addView(webOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Extra keys panel for WebView (injected via JS interface)
        webExtraKeys = buildWebExtraKeys();
        webPane.addView(webExtraKeys, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        return webPane;
    }

    private void configureWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " HermesLoader/" + versionName());
        CookieManager.getInstance().setAcceptCookie(true);

        web.setBackgroundColor(0xFF0A0A0A);
        // Force software rendering so the loading overlay (webOverlay) stays
        // on top of the WebView surface. Without this, the hardware-accelerated
        // WebView surface can draw above sibling views in a FrameLayout.
        web.setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        // JavaScript interface for extra keys panel
        WebKeyInterface keyInterface = new WebKeyInterface();
        keyInterface.setActivity(this);
        web.addJavascriptInterface(keyInterface, "HermesKeys");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return openExternally(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return openExternally(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                webReloads = 0;
                if (webOverlay != null) {
                    webOverlay.setVisibility(View.GONE);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (!request.isForMainFrame()) return;
                webOverlay.setText(R.string.web_unreachable);
                webOverlay.setVisibility(View.VISIBLE);
                // The server may still be importing FastAPI behind PRoot: retry.
                if (webReloads++ < 6 && Session.get().running()) {
                    view.postDelayed(view::reload, 1500);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (webFileCallback != null) {
                    webFileCallback.onReceiveValue(null);
                }
                webFileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_WEB_FILE);
                    return true;
                } catch (Exception e) {
                    webFileCallback = null;
                    toast("No file picker available on this device.");
                    return false;
                }
            }
        });

        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
            try {
                String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                if (mimeType != null) request.setMimeType(mimeType);
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) request.addRequestHeader("Cookie", cookie);
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalFilesDir(MainActivity.this,
                        Environment.DIRECTORY_DOWNLOADS, name);
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm == null) throw new IllegalStateException("no download manager");
                dm.enqueue(request);
                toast("Downloading " + name);
            } catch (Exception e) {
                toast("Download failed: " + e.getMessage());
            }
        });
    }

    /**
     * The Nous device flow prints "open this link … enter code XXXX". Opening it
     * for the user is the difference between "sign in" and "copy a URL out of a
     * terminal on a phone", so do it once per login, as soon as it appears.
     */
    private void offerSignInLink(String text) {
        if (text == null || text.indexOf("http") < 0) return;
        String plain = text.replaceAll("\u001b\\[[0-9;]*[A-Za-z]", "");
        java.util.regex.Matcher m = CLI_URL.matcher(plain);
        if (!m.find()) return;
        String url = m.group().replaceAll("[.,;:]+$", "");
        if (url.equals(loginUrlOpened)) return;
        loginUrlOpened = url;
        ui.post(() -> {
            toast("Sign-in page opened — the code is in the terminal above.");
            openExternally(Uri.parse(url));
        });
    }

    /** Loopback stays in the WebView; anything else goes to a real browser. */
    private boolean openExternally(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        String host = uri.getHost() == null ? "" : uri.getHost();
        if (("http".equals(scheme) || "https".equals(scheme))
                && ("127.0.0.1".equals(host) || "localhost".equals(host))) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            toast("No app can open " + uri);
        }
        return true;
    }

    /**
     * WEB ↔ TERM, which is dashboard ↔ console.
     *
     * <p>The dashboard is not a replacement for the console: both are clients of
     * the same gateway, and Hermes supports that. So when a session is running,
     * WEB starts the dashboard as a second guest process and leaves the session
     * alone — the conversation, the tool calls and the console scrollback are
     * all still there when TERM comes back. A dashboard started on its own
     * would leave TERM with nothing but a server log, so even from a cold start
     * WEB brings up the console first and attaches the dashboard to it.
     */
    private void toggleWeb() {
        if (webPane.getVisibility() == View.VISIBLE) {
            showTerminal();
            return;
        }
        if (busy) {
            toast("Please wait for the current step to finish.");
            return;
        }
        if (!agentInstalled()) {
            startInstallerInsteadOfDashboard();
            return;
        }
        if (Session.get().running()) {
            showWeb();
            if (GuestCommand.MODE_WEB.equals(Session.get().mode())
                    || GuestCommand.MODE_WEB_FAST.equals(Session.get().mode())
                    || GuestCommand.MODE_DASHBOARD_FAST.equals(Session.get().mode())) {
                // A session that *is* the dashboard: no console to go back to,
                // so attach to its server instead of starting a second one.
                if (dashboardUp()) {
                    loadDashboard();
                } else {
                    waitForDashboard();
                }
                return;
            }
            if (dashboardUp()) {
                loadDashboard();
            } else {
                startAuxDashboard();
            }
            return;
        }
        openDashboard();
    }

    /**
     * Starts `hermes dashboard` next to the running console session and waits
     * for it to answer. The guest is started off the UI thread because it
     * builds a command line, reads the SPA and spawns chroot-ng.
     */
    private void startAuxDashboard() {
        beginDashboardTiming();
        webReloads = 0;
        attached = false;
        webOverlay.setText(R.string.web_starting);
        webOverlay.setVisibility(View.VISIBLE);
        waitForDashboard(true);
        Thread t = new Thread(() -> {
            try {
                AuxDashboard.get().start(this, prefs);
                ui.post(this::updateStatus);
            } catch (Throwable e) {
                ui.post(() -> {
                    // Cancel the readiness probe started above. The console
                    // may remain alive, so that probe cannot infer the failed
                    // dashboard spawn from the session's state.
                    webProbe++;
                    toast("Could not start the dashboard: " + describe(e));
                    dashboardFailed();
                });
            }
        }, "dashboard-start");
        t.setDaemon(true);
        t.start();
    }

    /**
     * The same start, for the "open in a browser" action: no pane, no wait
     * loop for the WebView — just the URL once the server answers.
     */
    private void startAuxForBrowser() {
        Thread t = new Thread(() -> {
            if (dashboardUp()) {
                ui.post(this::launchBrowser);
                return;
            }
            beginDashboardTiming();
            try {
                AuxDashboard.get().start(this, prefs);
            } catch (Throwable e) {
                ui.post(() -> {
                    discardDashboardTiming();
                    toast("Could not start the dashboard: " + describe(e));
                });
                return;
            }
            waitThenOpenBrowser(true);
        }, "dashboard-link-start");
        t.setDaemon(true);
        t.start();
    }

    private static String describe(Throwable e) {
        String msg = e.getMessage();
        return msg == null || msg.isEmpty() ? e.getClass().getSimpleName() : msg;
    }

    /**
     * Starts the agent from a cold WEB tap.
     *
     * <p>By default the console starts first and the dashboard attaches to its
     * gateway, keeping both modes available until STOP. On memory-constrained
     * devices the optional WEB-only setting starts the dashboard as the
     * session instead, avoiding the additional console process.
     */
    private void openDashboard() {
        if (busy) return;
        if (Session.get().running()) {
            showWeb();
            if (GuestCommand.MODE_WEB.equals(Session.get().mode())
                    || GuestCommand.MODE_WEB_FAST.equals(Session.get().mode())) {
                if (dashboardUp()) {
                    loadDashboard();
                } else {
                    waitForDashboard();
                }
            } else if (dashboardUp()) {
                loadDashboard();
            } else {
                startAuxDashboard();
            }
            return;
        }
        webReloads = 0;
        attached = false;
        web.loadUrl("about:blank");
        beginDashboardTiming();
        if (prefs.webOnly()) {
            webOverlay.setText("HERMES FAST START\n\n"
                    + "The interface is ready. Starting Linux and the dashboard…\n\n"
                    + "Backend readiness can take longer on this device.");
            startMode(GuestCommand.MODE_WEB_FAST);
            showWeb();
            waitForDashboard(true);
            return;
        }
        // Cold WEB tap: start console in fast mode (skips banner/prepare),
        // gateway starts in background. Dashboard attaches when gateway ready.
        startMode(GuestCommand.MODE_BOOT_FAST);
        showWeb();
        webOverlay.setText(R.string.web_starting);
        webOverlay.setVisibility(View.VISIBLE);
        startAuxWhenConsoleReady(this::startAuxDashboard);
    }

    /**
     * Waits for the console's boot and its gateway, then runs {@code then}.
     *
     * <p>Order matters twice. {@code init.sh boot} kills stale gateways and
     * dashboards before it starts the agent, so a dashboard launched in the
     * same second would be killed by that very sweep. And since 1.6.3 the
     * console waits for the gateway itself before it starts Hermes
     * ({@code run.sh wait_gateway}); starting the dashboard in the middle of
     * that wait makes it open a gateway of its own, which is exactly what
     * killed the console's session with "Gateway connection failed".
     */
    private void startAuxWhenConsoleReady(Runnable then) {
        Thread t = new Thread(() -> {
            // Gateway ensure is capped at 300 s; this covers it plus the boot.
            long deadline = System.currentTimeMillis() + 6 * 60_000L;
            boolean seenRunning = Session.get().running();
            while (System.currentTimeMillis() < deadline) {
                if (consoleBooted()) {
                    ui.post(then);
                    return;
                }
                // The loader thread may not have flipped `busy` or spawned the
                // guest yet, so "nothing is running" only means failure once a
                // session was actually seen alive.
                if (Session.get().running()) {
                    seenRunning = true;
                } else if (seenRunning && !busy) {
                    ui.post(this::dashboardFailed);
                    return;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
            ui.post(this::dashboardFailed);
        }, "console-then-dashboard");
        t.setDaemon(true);
        t.start();
    }

    private static boolean consoleBooted() {
        // Two paths to detect console readiness:
        // 1. Full boot (MODE_BOOT): init.sh prints "loader ready in" after
        //    storage sweep, then run.sh prints gateway markers.
        // 2. Fast boot (MODE_BOOT_FAST): skips banner/prepare, goes straight
        //    to run.sh which starts gateway in background and prints
        //    "Gateway ready in N s." when ready.
        // In both cases, "Gateway ready in" is the reliable signal that the
        // console's gateway is up and the auxiliary dashboard can attach.
        Session s = Session.get();
        // Full boot marker (may not appear in fast mode)
        boolean fullBootReady = s.printed("loader ready in");
        // Gateway readiness marker (appears in both modes)
        boolean gatewayReady = s.printed("Gateway ready in")
                || s.printed("did not answer in time")
                || s.printed("Gateway wait is off");
        return fullBootReady || gatewayReady;
    }

    private boolean agentInstalled() {
        return Env.get(this).agentInstalled();
    }

    /**
     * Before the first install there is no agent to serve: {@code init.sh}
     * runs SETUP in the console instead, so WEB shows the installer. Starting a
     * dashboard anyway would only produce a server that cannot import Hermes,
     * an error overlay on top of a running setup, and a hidden prompt.
     */
    private void startInstallerInsteadOfDashboard() {
        if (!Session.get().running()) startMode(GuestCommand.MODE_BOOT);
        showTerminal();
        toast("The agent is not installed yet — setup runs in the terminal.");
    }

    // ------------------------------------------------------- external browser

    /**
     * Opens the loopback dashboard in an installed browser. Loopback is shared
     * by every app on the phone, so the browser talks to the very same server
     * as the in-app pane — but only while the dashboard is actually running.
     */
    @Override
    public void openDashboardInBrowser() {
        if (isFinishing() || isDestroyed()) return;
        if (busy) {
            toast("Please wait for the current step to finish.");
            return;
        }
        if (Session.get().running()
                && !GuestCommand.MODE_WEB.equals(Session.get().mode())) {
            // The console keeps running: the dashboard is a second guest
            // process behind the same gateway, so no "stop first" question.
            startAuxForBrowser();
            return;
        }
        // The HTTP probe blocks for up to a few seconds: keep it off the UI
        // thread, otherwise the dialog that launched it would freeze.
        final int probe = ++browserProbe;
        Thread t = new Thread(() -> {
            if (probe != browserProbe) return;
            if (dashboardUp()) {
                if (probe == browserProbe) ui.post(this::launchBrowser);
            } else if (Session.get().running()) {
                if (probe == browserProbe) ui.post(this::waitThenOpenBrowser);
            } else {
                if (probe == browserProbe) ui.post(this::askStartDashboardForBrowser);
            }
        }, "dashboard-link-probe");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void copyDashboardLink() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            toast("Clipboard is not available on this device.");
            return;
        }
        String url = shareUrl();
        cm.setPrimaryClip(ClipData.newPlainText("Hermes dashboard", url));
        toast("Link copied: " + url);
    }

    private void askStartDashboardForBrowser() {
        if (isFinishing() || isDestroyed()) return;
        if (busy) {
            toast("Please wait for the current step to finish.");
            return;
        }
        if (Session.get().running()) {
            if (GuestCommand.MODE_WEB.equals(Session.get().mode())
                    || GuestCommand.MODE_WEB_FAST.equals(Session.get().mode())
                    || GuestCommand.MODE_DASHBOARD_FAST.equals(Session.get().mode())) {
                waitThenOpenBrowser();
            } else {
                // Raced with another start; reuse the stop-first prompt.
                openDashboardInBrowser();
            }
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Web dashboard")
                .setMessage("The agent is not running.\n\nStart it and open "
                        + dashboardUrl() + " in a browser?")
                .setPositiveButton(R.string.yes, (d, w) -> startDashboardForBrowser())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void startDashboardForBrowser() {
        if (isFinishing() || isDestroyed()) return;
        if (busy) {
            toast("Please wait for the current step to finish.");
            return;
        }
        if (!agentInstalled()) {
            startInstallerInsteadOfDashboard();
            return;
        }
        if (Session.get().running()) {
            if (GuestCommand.MODE_WEB.equals(Session.get().mode())
                    || GuestCommand.MODE_WEB_FAST.equals(Session.get().mode())) {
                waitThenOpenBrowser();
            } else {
                startAuxForBrowser();
            }
            return;
        }
        webProbe++;
        attached = false;
        beginDashboardTiming();
        if (prefs.webOnly()) {
            startMode(GuestCommand.MODE_WEB_FAST);
            waitThenOpenBrowser(true);
            return;
        }
        // The console comes along as well, so TERM is a real prompt afterwards
        // instead of a server log (see openDashboard).
        startMode(GuestCommand.MODE_BOOT_FAST);
        startAuxWhenConsoleReady(this::startAuxForBrowser);
    }

    /** Polls loopback in the background, then hands the URL to a browser. */
    private void waitThenOpenBrowser() {
        waitThenOpenBrowser(false);
    }

    private void waitThenOpenBrowser(boolean requireSession) {
        final int probe = ++browserProbe;
        final long deadline = System.currentTimeMillis() + 5 * 60_000L;
        Thread t = new Thread(() -> {
            boolean seenRunning = Session.get().running();
            // The console may already have been running when this probe began;
            // then only the dashboard process can tell us it died.
            boolean seenAux = AuxDashboard.get().running();
            while (System.currentTimeMillis() < deadline) {
                if (probe != browserProbe || isFinishing() || isDestroyed()) return;
                if (dashboardUp() && (!requireSession
                        || (Session.get().running() && dashboardReadyForCurrentSession()))) {
                    ui.post(() -> {
                        if (probe == browserProbe) {
                            reportDashboardReady();
                            launchBrowser();
                        }
                    });
                    return;
                }
                if (Session.get().running()) {
                    seenRunning = true;
                } else if (seenRunning && !busy) {
                    ui.post(() -> {
                        if (probe == browserProbe) {
                            discardDashboardTiming();
                            toast("The dashboard stopped before it was ready. See the log.");
                        }
                    });
                    return;
                }
                if (AuxDashboard.get().running()) {
                    seenAux = true;
                } else if (seenAux && !busy) {
                    ui.post(() -> {
                        if (probe == browserProbe) {
                            discardDashboardTiming();
                            toast("The dashboard stopped before it was ready. "
                                    + "See ~/.hermes/cache/loader/webaux.log.");
                        }
                    });
                    return;
                }
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    return;
                }
            }
            ui.post(() -> {
                if (probe == browserProbe) {
                    discardDashboardTiming();
                    toast("The dashboard did not answer within 5 minutes. "
                            + "Tap WEB to watch its log.");
                }
            });
        }, "dashboard-browser-probe");
        t.setDaemon(true);
        t.start();
    }

    private void launchBrowser() {
        if (isFinishing() || isDestroyed()) return;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(dashboardUrl()));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(intent);
        } catch (Exception e) {
            toast("No browser can open " + dashboardUrl());
        }
    }

    private void showWeb() {
        webPane.setVisibility(View.VISIBLE);
        terminalPane.setVisibility(View.GONE);
        webButton.setText(R.string.btn_term);
        if (fastUiReadyAt == 0) {
            fastUiReadyAt = android.os.SystemClock.elapsedRealtime();
            if (dashboardRequestedAt > 0 && terminal != null) {
                terminal.appendLine("[TIME] WEB shell visible after "
                        + (fastUiReadyAt - dashboardRequestedAt) + " ms.");
            }
        }
        // Focus mode hides the header; the web pane must never be a dead end.
        refreshExtraKeys();
        hideKeyboard();
        // Ensure WebView is focused so xterm.js receives keys.
        web.requestFocus();
        updateStatus();
    }

    private void showTerminal() {
        if (webPane == null) return;
        webPane.setVisibility(View.GONE);
        terminalPane.setVisibility(View.VISIBLE);
        webButton.setText(R.string.btn_web);
        refreshExtraKeys();
        hideKeyboard();
        updateStatus();
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            View focus = getCurrentFocus();
            if (focus != null) {
                if (imm != null) {
                    imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
                }
                focus.clearFocus();
            }
        } catch (RuntimeException ignored) {
            // Keyboard is a nicety; never let it break the mode switch.
        }
    }

    /**
     * Waits until the guest answers on loopback. Starting the dashboard means
     * importing FastAPI and a few hundred routes inside chroot-ng, so this is
     * intentionally generous; the log stays visible in the terminal pane.
     */
    private void waitForDashboard() {
        waitForDashboard(false);
    }

    /**
     * @param requireSession when the guest was just started, require its own
     *                       dashboard-ready marker as well as an HTTP response.
     *                       This avoids mistaking a leftover server for the new
     *                       dashboard while init.sh is still cleaning it up.
     */
    private void waitForDashboard(boolean requireSession) {
        final int probe = ++webProbe;
        webOverlay.setText(R.string.web_starting);
        webOverlay.setVisibility(View.VISIBLE);
        Thread t = new Thread(() -> {
            long started = System.currentTimeMillis();
            long deadline = started + 15 * 60_000L;
            // The loader thread may not have flipped `busy` or spawned the
            // guest yet, so "nothing is running" only means failure once a
            // session was seen alive — or after a grace period.
            boolean seenRunning = Session.get().running();
            while (System.currentTimeMillis() < deadline) {
                if (probe != webProbe) return;
                boolean up = dashboardUp();
                boolean readyFromSession = !requireSession
                        || (Session.get().running() && dashboardReadyForCurrentSession());
                if (up && readyFromSession) {
                    ui.post(() -> {
                        if (probe == webProbe) {
                            reportDashboardReady();
                            loadDashboard();
                        }
                    });
                    return;
                }
                if (!up) {
                    if (Session.get().running()) {
                        seenRunning = true;
                    } else if (seenRunning
                            || (!busy && System.currentTimeMillis() - started > 30_000L)) {
                        ui.post(() -> {
                            if (probe == webProbe) dashboardFailed();
                        });
                        return;
                    }
                }
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    return;
                }
            }
            ui.post(() -> {
                if (probe == webProbe) dashboardFailed();
            });
        }, "dashboard-probe");
        t.setDaemon(true);
        t.start();
    }

    private boolean dashboardReadyForCurrentSession() {
        if (GuestCommand.MODE_WEB.equals(Session.get().mode())
                || GuestCommand.MODE_WEB_FAST.equals(Session.get().mode())
                || GuestCommand.MODE_DASHBOARD_FAST.equals(Session.get().mode())) {
            return Session.get().printed("HERMES_DASHBOARD_READY");
        }
        return AuxDashboard.get().ready();
    }

    private synchronized void beginDashboardTiming() {
        if (dashboardRequestedAt == 0) {
            dashboardRequestedAt = android.os.SystemClock.elapsedRealtime();
            fastUiReadyAt = 0;
        }
    }

    private void reportDashboardReady() {
        final long started;
        synchronized (this) {
            started = dashboardRequestedAt;
            dashboardRequestedAt = 0;
        }
        if (started != 0 && terminal != null) {
            terminal.appendLine("[TIME] Dashboard ready after "
                    + (android.os.SystemClock.elapsedRealtime() - started) + " ms.");
            AuxDashboard auxiliary = AuxDashboard.get();
            if (auxiliary.ready()) {
                long processStarted = auxiliary.startedElapsedRealtime();
                if (processStarted > 0) {
                    terminal.appendLine("[TIME] Auxiliary dashboard ready after "
                            + (android.os.SystemClock.elapsedRealtime() - processStarted)
                            + " ms.");
                }
            }
        }
    }

    private synchronized void discardDashboardTiming() {
        dashboardRequestedAt = 0;
    }

    private void loadDashboard() {
        webReloads = 0;
        webOverlay.setText(R.string.web_loading);
        webOverlay.setVisibility(View.VISIBLE);
        web.loadUrl(dashboardUrl());
    }

    private void dashboardFailed() {
        discardDashboardTiming();
        webOverlay.setText(R.string.web_failed);
        webOverlay.setVisibility(View.VISIBLE);
    }

    /**
     * Tapping the overlay: start the server again if the auxiliary one is gone,
     * otherwise just keep waiting for the one that is starting.
     */
    /**
     * The dashboard's own output — the one stream the console never carries.
     *
     * <p>The console belongs to the agent; the dashboard runs as a second guest
     * process and writes to {@code webaux.log}. That is where a dashboard that
     * refuses to start explains itself, so it needs a way in that does not
     * require SHELL.
     */
    private void showDashboardLog() {
        final Env env = Env.get(this);
        final File file = AuxDashboard.logFile(env);
        Thread t = new Thread(() -> {
            String text;
            try {
                text = AuxDashboard.readLog(env, 200_000);
            } catch (Exception e) {
                text = getString(R.string.log_unreadable) + " " + describe(e);
            }
            final String body = text;
            ui.post(() -> showLogDialog(file.getAbsolutePath(), body));
        }, "dashboard-log");
        t.setDaemon(true);
        t.start();
    }

    private void showLogDialog(String path, String body) {
        String text = body.trim().isEmpty() ? getString(R.string.log_empty) : body;

        TextView view = new TextView(this);
        view.setText(getString(R.string.log_body, path, text));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setPadding(dp(12), dp(8), dp(12), dp(8));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(logTitleWithRam())
                .setView(scroll)
                .setPositiveButton(R.string.log_copy, null)
                .setNegativeButton(R.string.log_close, null)
                .create();
        final Runnable[] refreshRamTitle = new Runnable[1];
        refreshRamTitle[0] = () -> {
            if (!dialog.isShowing()) return;
            dialog.setTitle(logTitleWithRam());
            ui.postDelayed(refreshRamTitle[0], 5_000);
        };
        dialog.setOnDismissListener(d -> ui.removeCallbacks(refreshRamTitle[0]));
        // Copying must not close the dialog: the user may want to read on.
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    ClipboardManager cm =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("Hermes dashboard log", text));
                        toast("Log copied.");
                    }
                }));
        dialog.show();
        ui.postDelayed(refreshRamTitle[0], 5_000);
    }

    private String logTitleWithRam() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager == null) return getString(R.string.log_title);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memory);
        double gib = 1024.0 * 1024 * 1024;
        String available = String.format(java.util.Locale.US, "%.1f", memory.availMem / gib)
                .replace('.', ',');
        String total = String.format(java.util.Locale.US, "%.1f", memory.totalMem / gib)
                .replace('.', ',');
        return getString(R.string.log_title) + " · RAM: " + available + "/" + total + " Gb";
    }

    private void retryDashboard() {
        boolean webSession = GuestCommand.MODE_WEB.equals(Session.get().mode())
                || GuestCommand.MODE_WEB_FAST.equals(Session.get().mode())
                || GuestCommand.MODE_DASHBOARD_FAST.equals(Session.get().mode());
        if (Session.get().running() && !webSession && !AuxDashboard.get().running()) {
            startAuxDashboard();
            return;
        }
        waitForDashboard();
    }

    @Override
    public String dashboardUrl() {
        return "http://127.0.0.1:" + GuestCommand.WEB_PORT + "/";
    }

    /**
     * The address another device should open. With "reachable from other
     * devices" off this is loopback; with it on, the first non-loopback IPv4
     * address (Wi-Fi before Tailscale, so the phone's own network wins).
     */
    private String shareUrl() {
        if (prefs.webNetwork() && prefs.webAuthReady()) {
            List<String> addresses = localAddresses();
            if (!addresses.isEmpty()) {
                return "http://" + addresses.get(0) + ":" + GuestCommand.WEB_PORT + "/";
            }
        }
        return dashboardUrl();
    }

    /** One URL per line, for the settings screen. */
    @Override
    public String dashboardAddresses() {
        StringBuilder sb = new StringBuilder();
        for (String address : localAddresses()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("http://").append(address).append(':').append(GuestCommand.WEB_PORT).append('/');
        }
        return sb.toString();
    }

    /**
     * IPv4 addresses other devices can reach. Cellular interfaces are skipped:
     * carriers do not route inbound connections. Tailscale comes last because
     * it only exists when the VPN is up, and then 100.64/10 is the address a
     * laptop can always use, even away from home.
     */
    private static List<String> localAddresses() {
        List<String> lan = new ArrayList<>();
        List<String> tailscale = new ArrayList<>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> all =
                    java.net.NetworkInterface.getNetworkInterfaces();
            if (all == null) return lan;
            while (all.hasMoreElements()) {
                java.net.NetworkInterface ni = all.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName() == null ? "" : ni.getName();
                if (name.startsWith("rmnet") || name.startsWith("dummy")
                        || name.startsWith("p2p")) {
                    continue;
                }
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    java.net.InetAddress address = ia.getAddress();
                    if (!(address instanceof java.net.Inet4Address)) continue;
                    if (address.isLoopbackAddress() || address.isLinkLocalAddress()) continue;
                    String host = address.getHostAddress();
                    if (host == null || host.isEmpty()) continue;
                    if (isTailscaleAddress(host)) {
                        if (!tailscale.contains(host)) tailscale.add(host);
                    } else if (!lan.contains(host)) {
                        lan.add(host);
                    }
                }
            }
        } catch (Exception ignored) {
            // No interface list is not an error: loopback still works.
        }
        lan.addAll(tailscale);
        return lan;
    }

    /** 100.64.0.0/10, the range Tailscale hands out. */
    private static boolean isTailscaleAddress(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        try {
            return Integer.parseInt(parts[0]) == 100
                    && Integer.parseInt(parts[1]) >= 64
                    && Integer.parseInt(parts[1]) <= 127;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean dashboardUp() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(dashboardUrl()).openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(2000);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // -------------------------------------------------------------- actions

    private void startMode(String mode) {
        if (busy) return;
        if (Session.get().running()) {
            toast("A session is already running — press STOP first.");
            return;
        }
        welcomeView.setVisibility(View.GONE);
        busy = true;
        applyRunningState(false);
        // Wall clock for the "tap → first byte" line that onOutput prints.
        sessionRequestedAt = android.os.SystemClock.elapsedRealtime();
        firstOutputSeen = false;
        if (!GuestCommand.MODE_WEB.equals(mode)
                && !GuestCommand.MODE_WEB_FAST.equals(mode)
                && !GuestCommand.MODE_DASHBOARD_FAST.equals(mode)) {
            // Any other mode writes its progress to the console, so show it.
            webProbe++;
            attached = false;
            if (GuestCommand.MODE_LOGIN.equals(mode)) {
                // A retry may print a new device code, so allow one more link.
                loginUrlOpened = null;
            }
            // Leaving a dashboard behind would keep port 9119 and later make
            // `hermes dashboard` attach to a server the UI knows nothing about.
            // Launcher kills leftovers on its own thread before the session, so
            // the tap does not block here.
            showTerminal();
            // A new session must open at its own first line, not wherever the
            // previous one was left (the TextView keeps its scroll position).
            terminal.jumpToBottom();
        }
        // The guest must believe it has exactly the width the console has,
        // otherwise full-width panels wrap and box-drawing borders fall apart.
        prefs.terminalSize(terminal.columns(), terminal.rows());
        if (GuestCommand.MODE_SHELL.equals(mode)) {
            terminal.appendLine("\n\u001b[1;36m[EXEC]\u001b[0m opening a root shell in the Linux container…");
        }
        Launcher.start(this, prefs, mode, this);
    }

    private void stopSession() {
        if (!Session.get().running()) {
            if (AuxDashboard.get().running()) {
                // Only the dashboard is left (the console ended on its own).
                webProbe++;
                attached = false;
                busy = true;
                showTerminal();
                applyRunningState(false);
                setStatus("stopping dashboard…", COLOR_WORK);
                Thread stop = new Thread(() -> {
                    try {
                        AuxDashboard.get().stop();
                    } catch (Throwable e) {
                        ui.post(() -> terminal.appendLine("\n\u001b[1;31m[ERROR]\u001b[0m dashboard stop failed: " + e));
                    } finally {
                        ui.post(() -> {
                            busy = false;
                            applyRunningState(false);
                            toast("Dashboard stopped.");
                        });
                    }
                }, "stop-dashboard");
                stop.setDaemon(true);
                stop.start();
                return;
            }
            if (attached) {
                // The dashboard is alive but no session owns it (an earlier
                // instance was attached to). Shut it down and go back to the log.
                webProbe++;
                attached = false;
                Thread sweep = new Thread(Session::killLeftovers, "sweep-dashboard");
                sweep.setDaemon(true);
                sweep.start();
                showTerminal();
                toast("Dashboard stopped.");
                return;
            }
            toast("Nothing is running.");
            return;
        }
        webProbe++;
        welcomeView.setVisibility(View.GONE);
        terminal.appendLine("\n\u001b[1;33m[STOP]\u001b[0m terminating the guest process…");
        busy = true;
        applyRunningState(true);
        setStatus("stopping…", COLOR_WORK);
        showTerminal();
        Context app = getApplicationContext();
        Thread stop = new Thread(() -> {
            // The sweep can wait for TERM/KILL. Keep it off the UI thread while
            // the terminal continues to redraw the shutdown output.
            try {
                Session.get().stop();
                AuxDashboard.get().stop();
                RunnerService.stop(app);
            } catch (Throwable e) {
                ui.post(() -> terminal.appendLine("\n\u001b[1;31m[ERROR]\u001b[0m stop failed: " + e));
            } finally {
                ui.post(() -> {
                    busy = false;
                    applyRunningState(Session.get().running());
                });
            }
        }, "stop-session");
        stop.setDaemon(true);
        stop.start();
    }

    private void sendCurrentInput() {
        if (!Session.get().running()) {
            toast("Press START (or SHELL) first.");
            return;
        }
        String text = input.getText().toString();
        input.setText("");
        boolean ctrl = ctrlArmed;
        boolean alt = altArmed;
        clearModifiers();
        if (!ctrl && !alt) {
            Session.get().submit(text);
            return;
        }
        // With a modifier armed the line is sent as keystrokes, not as a
        // command: CTRL + "c" + SEND is Ctrl-C, exactly as if the key had been
        // held on a hardware keyboard. No newline is appended.
        StringBuilder out = new StringBuilder(text.length() + 4);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (alt) out.append('\u001b');
            out.append(ctrl ? control(c) : c);
        }
        Session.get().write(out.toString());
    }

    /** The byte a terminal sends for Ctrl + {@code c}. */
    private static char control(char c) {
        if (c >= 'a' && c <= 'z') return (char) (c - 'a' + 1);
        if (c >= 'A' && c <= 'Z') return (char) (c - 'A' + 1);
        switch (c) {
            case ' ':
            case '@':
            case '2':
                return 0;
            case '[':
            case '3':
                return 27;
            case '\\':
            case '4':
                return 28;
            case ']':
            case '5':
                return 29;
            case '^':
            case '6':
                return 30;
            case '_':
            case '7':
                return 31;
            case '?':
            case '8':
                return 127;
            default:
                return c;
        }
    }

    private void pasteIntoInput() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            toast("Clipboard is empty.");
            return;
        }
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (text == null) return;
        int start = Math.max(0, input.getSelectionStart());
        input.getText().insert(start, text);
    }

    private void applyRunningState(boolean running) {
        clearModifiers();
        startButton.setEnabled(!busy && !running);
        shellButton.setEnabled(!busy && !running);
        setupButton.setEnabled(!busy && !running);
        stopButton.setEnabled(!busy && (running || AuxDashboard.get().running()));
        if (running) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        updateStatus();
    }

    /** Shows what the app is doing right now, next to the title. */
    private void setStatus(String text, int color) {
        if (statusView == null) return;
        statusView.setText("\u25cf  " + text);
        statusView.setTextColor(color);
        statusView.setBackground(statusBackground(color));
    }

    private void updateStatus() {
        if (Session.get().running()) {
            boolean webMode = GuestCommand.MODE_WEB.equals(Session.get().mode())
                    || GuestCommand.MODE_WEB_FAST.equals(Session.get().mode())
                    || GuestCommand.MODE_DASHBOARD_FAST.equals(Session.get().mode());
            boolean aux = AuxDashboard.get().running();
            setStatus(webMode || aux ? "running · dashboard" : "running", COLOR_ACTIVE);
            return;
        }
        if (AuxDashboard.get().running()) {
            setStatus("dashboard · auxiliary", COLOR_ACTIVE);
            return;
        }
        if (attached) {
            setStatus("dashboard · attached", COLOR_ACTIVE);
            return;
        }
        if (busy) {
            setStatus("working…", COLOR_WORK);
            return;
        }
        Env env = Env.get(this);
        if (!env.rootfsInstalled()) {
            setStatus("not installed", COLOR_WORK);
        } else if (!env.agentInstalled()) {
            setStatus("rootfs ready", COLOR_OK);
        } else {
            setStatus("ready", COLOR_OK);
        }
    }

    // --------------------------------------------------------- Session hooks

    @Override
    public void onOutput(String chunk) {
        if (disposed || chunk == null || chunk.isEmpty()) return;
        synchronized (outputLock) {
            pendingOutput.append(chunk);
            if (outputDrainScheduled) return;
            outputDrainScheduled = true;
            ui.postDelayed(drainOutputTask, OUTPUT_BATCH_MS);
        }
    }

    /** Coalesces the PTY reader's small chunks into one editable-text update per frame. */
    private void drainOutput() {
        String batch;
        synchronized (outputLock) {
            if (disposed || pendingOutput.length() == 0) {
                outputDrainScheduled = false;
                return;
            }
            batch = pendingOutput.toString();
            pendingOutput.setLength(0);
            outputDrainScheduled = false;
        }
        if (!firstOutputSeen) {
            firstOutputSeen = true;
            if (sessionRequestedAt > 0) {
                terminal.append("\u001b[2m[TIME] guest output after "
                        + (android.os.SystemClock.elapsedRealtime() - sessionRequestedAt)
                        + " ms\u001b[0m\n");
            }
        }
        welcomeView.setVisibility(View.GONE);
        terminal.append(batch);
    }

    @Override
    public void onExit(int code) {
        if (disposed) return;
        ui.post(() -> {
            drainOutput();
            webProbe++;
            discardDashboardTiming();
            String endedMode = Session.get().lastMode();
            terminal.appendLine("");
            terminal.appendLine("\u001b[1;33m[EXIT]\u001b[0m session finished (code " + code + ").");
            if (code != 0) {
                exitHints(code);
            }
            applyRunningState(false);
            RunnerService.stop(this);

            // A gateway daemon or a dashboard can outlive the session (Android
            // kills the app, not its children). The next start would then find a
            // second writer on ~/.hermes/state.db and fail with
            // `storage_unavailable`, so sweep them away here. The dashboard is
            // spared on purpose: `hermes dashboard` exits 0 when one is already
            // running, and the web pane attaches to it.
            //
            // The dashboard started next to the console is tied to it, so this
            // is also where it ends; the sweep thread owns the two-second wait.
            if (!GuestCommand.MODE_WEB.equals(endedMode)
                    && !GuestCommand.MODE_WEB_FAST.equals(endedMode)
                    && !GuestCommand.MODE_DASHBOARD_FAST.equals(endedMode)) {
                Thread sweep = new Thread(() -> {
                    AuxDashboard.get().stop();
                    Session.killLeftovers();
                }, "sweep-guest");
                sweep.setDaemon(true);
                sweep.start();
            }

            if (needsSetupAfterFailure(code, endedMode)) {
                offerSetupAfterFailure(code);
            }

            if (webPane == null || webPane.getVisibility() != View.VISIBLE) {
                updateStatus();
                return;
            }
            // An exit does not always mean "no dashboard": `hermes dashboard`
            // exits 0 when one is already running. Check off the UI thread so a
            // hung socket can never freeze the app.
            Thread t = new Thread(() -> {
                boolean up = dashboardUp();
                ui.post(() -> {
                    if (up) {
                        attached = true;
                        toast("The dashboard was already running — press STOP to close it.");
                    } else {
                        showTerminal();
                        toast("The dashboard stopped. See the log above.");
                    }
                    updateStatus();
                });
            }, "dashboard-exit");
            t.setDaemon(true);
            t.start();
        });
    }

    /** Printed under a non-zero exit, so the number is never the only clue. */
    private void exitHints(int code) {
        if (code == 2) {
            terminal.appendLine("\u001b[1;33m[HINT]\u001b[0m exit 2 means the guest refused to start.");
            terminal.appendLine("[HINT]   `storage_unavailable` comes from the agent's local gateway:");
            terminal.appendLine("[HINT]   its SQLite store in ~/.hermes was busy or the device is short"
                    + " of space.");
            terminal.appendLine("[HINT]   Leftover guest processes are swept automatically — press"
                    + " START again.");
            terminal.appendLine("[HINT]   SHELL → df -h /home/hermes shows the free space.");
        } else if (code == 1) {
            terminal.appendLine("[HINT] Exit 1 from a fresh install usually means setup was"
                    + " declined; use SETUP or ⋮ → Sign in to Nous.");
        }
    }

    /**
     * A first run that ends in a failure is the one place where a dialog beats
     * another line of log: the user has no provider yet, so the two useful
     * actions are exactly the two buttons.
     */
    private boolean needsSetupAfterFailure(int code, String endedMode) {
        if (code == 0 || isFinishing() || isDestroyed()) return false;
        if (GuestCommand.MODE_SHELL.equals(endedMode)
                || GuestCommand.MODE_LOGIN.equals(endedMode)
                || GuestCommand.MODE_UPDATE.equals(endedMode)
                || GuestCommand.MODE_INSTALL.equals(endedMode)) {
            return false;
        }
        return !new File(Env.get(this).hermesHome, "config.yaml").isFile();
    }

    private void offerSetupAfterFailure(int code) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.exit_setup_title)
                .setMessage(getString(R.string.exit_setup_message, code))
                .setPositiveButton(R.string.exit_setup_signin,
                        (d, w) -> startMode(GuestCommand.MODE_LOGIN))
                .setNeutralButton(R.string.exit_setup_run,
                        (d, w) -> startMode(GuestCommand.MODE_SETUP))
                .setNegativeButton(R.string.exit_setup_later, null)
                .show();
    }

    // ------------------------------------------------------------ Launcher.UI

    @Override
    public void line(String text) {
        if (GuestCommand.MODE_LOGIN.equals(Session.get().mode())) {
            offerSignInLink(text);
        }
        ui.post(() -> terminal.appendLine(text));
    }

    @Override
    public void raw(String text) {
        ui.post(() -> terminal.append(text));
    }

    @Override
    public void busy(boolean isBusy) {
        ui.post(() -> {
            busy = isBusy;
            applyRunningState(Session.get().running());
        });
    }

    @Override
    public void status(String text) {
        ui.post(() -> {
            if (text == null) {
                updateStatus();
            } else {
                setStatus(text, COLOR_WORK);
            }
        });
    }

    // ------------------------------------------------------------------ menu

    private void showMenu() {
        String[] items = {
                "Settings",
                "Open web dashboard",
                "Open dashboard in a browser",
                "Send a special key…",
                "Show or hide the keyboard",
                prefs.extraKeys() ? "Hide the header and keys" : "Show the header and keys",
                "Clear terminal",
                "Copy all output",
                "Sign in to Nous",
                "Update agent now",
                "Battery optimization",
                "About",
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_title)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            showSettings();
                            break;
                        case 1:
                            if (webPane.getVisibility() != View.VISIBLE) {
                                toggleWeb();
                            }
                            break;
                        case 2:
                            openDashboardInBrowser();
                            break;
                        case 3:
                            showSpecialKeys();
                            break;
                        case 4:
                            toggleSoftKeyboard();
                            break;
                        case 5:
                            toggleExtraKeysBar();
                            break;
                        case 6:
                            terminal.clear();
                            break;
                        case 7:
                            copyAll();
                            break;
                        case 8:
                            startMode(GuestCommand.MODE_LOGIN);
                            break;
                        case 9:
                            startMode(GuestCommand.MODE_UPDATE);
                            break;
                        case 10:
                            requestIgnoreBatteryOptimizations();
                            break;
                        default:
                            showAbout();
                            break;
                    }
                })
                .show();
    }

    /**
     * Keys Termux keeps in the bar but the default layout does not show: TAB and
     * its shifted form, the control characters people reach for most, and the
     * clipboard. Keeping them here instead of a third row leaves the bar the
     * same height as Termux's.
     */
    private void showSpecialKeys() {
        final String[] keys = {
                "TAB", "SHIFT+TAB", "CTRL+C", "CTRL+D", "CTRL+L", "CTRL+Z",
                "Paste into the input line",
        };
        final String[] sequences = {
                "\t", "\u001b[Z", "\u0003", "\u0004", "\u000c", "\u001a", null,
        };
        new AlertDialog.Builder(this)
                .setTitle("Special keys")
                .setItems(keys, (d, which) -> {
                    if (sequences[which] == null) {
                        pasteIntoInput();
                    } else {
                        sendRaw(sequences[which]);
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showSettings() {
        SettingsDialog.show(this, prefs, this);
    }

    private void copyAll() {
        String text = terminal.dump();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Hermes terminal", text));
            toast("Terminal copied (" + text.length() + " chars).");
        }
    }

    private void doReinstallRootfs() {
        busy = true;
        welcomeView.setVisibility(View.GONE);
        applyRunningState(Session.get().running());
        setStatus("preparing reinstall…", COLOR_WORK);
        terminal.appendLine("\n\u001b[1;36m[FS]\u001b[0m reinstalling Linux rootfs (storage/ is preserved)…");

        new Thread(() -> {
            try {
                stopGuestForMaintenance();
                ui.post(() -> setStatus("reinstalling Linux…", COLOR_WORK));
                Log log = new Log() {
                    @Override
                    public void line(String text) {
                        ui.post(() -> terminal.appendLine(text));
                    }

                    @Override
                    public void raw(String text) {
                        ui.post(() -> terminal.append(text));
                    }
                };
                Env env = Env.get(this);
                env.ensure();
                NgInstaller.ensure(env, log);
                Scripts.sync(this, env);
                NetFiles.write(this, env);
                boolean bundled = false;
                if (prefs.useBundle()) {
                    try {
                        bundled = BundleInstaller.installRootfs(env, prefs, log);
                    } catch (Throwable e) {
                        log.line("\u001b[1;33m[BUNDLE]\u001b[0m Prebuilt rootfs unavailable ("
                                + e + ") — using the normal installer.");
                    }
                }
                if (!bundled) {
                    RootfsInstaller.reinstall(this, env, prefs, log);
                }
                log.line("\u001b[1;32m[FS]\u001b[0m Rootfs reinstalled. Press START to boot the agent.");
            } catch (Throwable e) {
                ui.post(() -> terminal.appendLine("\u001b[1;31m[ERROR]\u001b[0m " + e));
            } finally {
                ui.post(() -> {
                    busy = false;
                    applyRunningState(Session.get().running());
                });
            }
        }, "rootfs-reinstall").start();
    }

    private void doWipeEverything() {
        busy = true;
        applyRunningState(Session.get().running());
        setStatus("wiping data…", COLOR_WORK);
        new Thread(() -> {
            try {
                stopGuestForMaintenance();
                Env env = Env.get(this);
                RootfsInstaller.deleteRecursive(env.rootfs);
                RootfsInstaller.deleteRecursive(env.storage);
                RootfsInstaller.deleteRecursive(env.cache);
                env.ensure();
                ui.post(() -> {
                    terminal.clear();
                    welcome();
                    toast("Everything wiped. Press START to deploy again.");
                });
            } catch (Throwable e) {
                ui.post(() -> terminal.appendLine("\u001b[1;31m[ERROR]\u001b[0m wipe failed: " + e));
            } finally {
                ui.post(() -> {
                    busy = false;
                    applyRunningState(false);
                });
            }
        }, "wipe").start();
    }

    /** Stop guests before touching their rootfs or persistent files. Call off the UI thread. */
    private void stopGuestForMaintenance() {
        Session.get().stop();
        AuxDashboard.get().stop();
        RunnerService.stop(getApplicationContext());
    }

    // ---------------------------------------------------------- phone storage

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            toast("No system folder picker on this device.");
        }
    }

    /**
     * Registers a tree the user just picked and pulls its contents in. The
     * persistable grant is what makes the folder usable after a restart: SAF
     * permissions are otherwise gone as soon as the picker activity closes.
     */
    private void addStorageRoot(Uri tree) {
        // Ask for write as well: export needs it and a tree picked with the
        // system picker normally grants both. Providers that are read-only
        // reject the combined request, so fall back to reading only.
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException writeDenied) {
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                toast("Read-only folder: Export will not work.");
            } catch (SecurityException readDenied) {
                toast("The system did not keep access to this folder. Pick it again and choose “Use this folder”.");
                return;
            }
        }

        for (Prefs.Root existing : prefs.roots()) {
            if (existing.uri.equals(tree.toString())) {
                toast("This folder is already connected as " + existing.label + ".");
                return;
            }
        }

        Prefs.Root root = prefs.addRoot(tree.toString(),
                SafTree.displayName(this, tree));
        toast("Added " + root.label + " — importing…");
        runMirror(root, true);
    }

    private void dropFolder(Prefs.Root root) {
        prefs.removeRoot(root.uri);
        try {
            getContentResolver().releasePersistableUriPermission(Uri.parse(root.uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
            // The grant may already have been revoked by the system.
        }
        Env env = Env.get(this);
        RootfsInstaller.deleteRecursive(env.mirrorDir(root.label));
        File state = env.syncState(root.label);
        if (state.isFile() && !state.delete()) {
            // A stale manifest only costs one round of conflict copies.
        }
        toast(root.label + " removed from Hermes.");
    }

    /**
     * Runs one import or export on a worker thread, with a cancel button and a
     * progress line. Cancelling stops after the current file: whatever was
     * copied stays copied, and the manifest is saved, so the next run resumes.
     */
    private void runMirror(Prefs.Root root, boolean fromDevice) {
        final TextView progress = new TextView(this);
        progress.setText("Preparing…");
        int pad = dp(20);
        progress.setPadding(pad, dp(8), pad, dp(4));
        progress.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        progress.setTypeface(android.graphics.Typeface.MONOSPACE);

        final boolean[] cancelled = {false};
        final AlertDialog[] dialog = new AlertDialog[1];
        dialog[0] = new AlertDialog.Builder(this)
                .setTitle((fromDevice ? "Import: " : "Export: ") + root.label)
                .setView(progress)
                .setNegativeButton("Cancel", (d, w) -> cancelled[0] = true)
                .setCancelable(false)
                .create();
        dialog[0].show();

        Mirror.Sink sink = new Mirror.Sink() {
            private long last;

            @Override
            public void step(String path, int files, long bytes) {
                long now = System.currentTimeMillis();
                if (now - last < 120) return;
                last = now;
                String text = files + " files · " + bytes(bytes) + "\n" + path;
                ui.post(() -> progress.setText(text));
            }

            @Override
            public boolean cancelled() {
                return cancelled[0];
            }
        };

        new Thread(() -> {
            Mirror.Report report;
            try {
                report = fromDevice
                        ? Mirror.importFromDevice(this, root, sink)
                        : Mirror.exportToDevice(this, root, sink);
            } catch (Throwable e) {
                ui.post(() -> {
                    dismiss(dialog[0]);
                    terminal.appendLine("\u001b[1;31m[SAF]\u001b[0m "
                            + (fromDevice ? "import" : "export") + " of " + root.label
                            + " failed: " + e.getMessage());
                    new AlertDialog.Builder(this)
                            .setTitle((fromDevice ? "Import" : "Export") + " failed")
                            .setMessage(String.valueOf(e.getMessage()))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
                return;
            }
            ui.post(() -> {
                dismiss(dialog[0]);
                showMirrorResult(root, report, fromDevice);
            });
        }, "mirror").start();
    }

    private void showMirrorResult(Prefs.Root root, Mirror.Report r, boolean fromDevice) {
        terminal.appendLine("\u001b[1;36m[SAF]\u001b[0m "
                + (fromDevice ? "imported " : "exported ") + root.label
                + " — added " + r.created + ", updated " + r.replaced
                + (r.deleted > 0 ? ", deleted " + r.deleted : "")
                + (r.conflicts > 0 ? ", conflicts " + r.conflicts : "")
                + (r.failed > 0 ? ", failed " + r.failed : ""));

        StringBuilder sb = new StringBuilder();
        if (r.cancelled) sb.append("Cancelled — completed files are kept.\n\n");
        sb.append("Added: ").append(r.created).append('\n');
        sb.append("Updated: ").append(r.replaced).append('\n');
        if (!fromDevice) sb.append("Deleted on the phone: ").append(r.deleted).append('\n');
        sb.append("Conflict copies: ").append(r.conflicts).append('\n');
        sb.append("Unchanged: ").append(r.skipped).append('\n');
        if (r.failed > 0) sb.append("Failed: ").append(r.failed).append('\n');
        sb.append("Transferred: ").append(bytes(r.bytes));

        if (!r.problems.isEmpty()) {
            sb.append("\n\n");
            for (String problem : r.problems) {
                sb.append("• ").append(problem).append('\n');
            }
        }
        if (r.conflicts > 0) {
            sb.append("\nBoth sides changed the same files. Nothing was overwritten: the "
                    + "incoming version was saved next to the original as a “conflict copy”.");
        }
        if (fromDevice) {
            sb.append("\n\nThe guest sees it at ~/projects/").append(root.label)
                    .append(" (also /mnt/android/").append(root.label).append(").");
        }

        new AlertDialog.Builder(this)
                .setTitle((fromDevice ? "Imported " : "Exported ") + root.label)
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode != REQ_MEDIA) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }
        if (mediaGranted()) {
            toast("Media access granted.");
        } else {
            toast("Media access denied. The system file picker still works without it.");
        }
    }

    @Override
    public boolean mediaGranted() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)
                    == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
            // Android 14+ lets the user grant only the selected photos.
            return Build.VERSION.SDK_INT >= 34
                    && checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void grantMediaAccess() {
        List<String> ask = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 34) {
            ask.add("android.permission.READ_MEDIA_VISUAL_USER_SELECTED");
        }
        if (Build.VERSION.SDK_INT >= 33) {
            ask.add(Manifest.permission.READ_MEDIA_IMAGES);
            ask.add(Manifest.permission.READ_MEDIA_VIDEO);
        } else {
            ask.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        requestPermissions(ask.toArray(new String[0]), REQ_MEDIA);
    }

    private static String bytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", n / 1024.0);
        }
        if (n < 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", n / (1024.0 * 1024));
        }
        return String.format(java.util.Locale.ROOT, "%.2f GB", n / (1024.0 * 1024 * 1024));
    }

    private void dismiss(AlertDialog dialog) {
        try {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        } catch (RuntimeException ignored) {
            // The activity may already be gone.
        }
    }

    // ------------------------------------------------------ SettingsDialog

    @Override
    public void reinstallRootfs() {
        confirm(R.string.confirm_reinstall, this::doReinstallRootfs);
    }

    @Override
    public void wipeEverything() {
        confirm(R.string.confirm_reset, this::doWipeEverything);
    }

    @Override
    public void updateAgent() {
        startMode(GuestCommand.MODE_UPDATE);
    }

    @Override
    public void batterySettings() {
        requestIgnoreBatteryOptimizations();
    }

    @Override
    public void applyTerminalFont() {
        terminal.setFontSp(prefs.terminalFontSp());
    }

    @Override
    public void applyExtraKeys() {
        refreshExtraKeys();
    }

    @Override
    public boolean applyDashboardSettings() {
        if (!AuxDashboard.get().running()
                || !Session.get().running()
                || GuestCommand.MODE_WEB.equals(Session.get().mode())
                || GuestCommand.MODE_WEB_FAST.equals(Session.get().mode())
                || GuestCommand.MODE_DASHBOARD_FAST.equals(Session.get().mode())) {
            return false;
        }

        webProbe++;
        busy = true;
        applyRunningState(true);
        setStatus("restarting dashboard…", COLOR_WORK);
        boolean showWeb = webPane.getVisibility() == View.VISIBLE;
        if (showWeb) {
            attached = false;
            webOverlay.setText(R.string.web_starting);
            webOverlay.setVisibility(View.VISIBLE);
        }

        Context app = getApplicationContext();
        Thread restart = new Thread(() -> {
            Throwable failure = null;
            try {
                AuxDashboard.get().stop();
                long deadline = System.currentTimeMillis() + 5_000;
                while (AuxDashboard.get().running()
                        && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50);
                }
                if (AuxDashboard.get().running()) {
                    throw new IllegalStateException("Old dashboard process did not exit");
                }
                if (Session.get().running()
                        && !GuestCommand.MODE_WEB.equals(Session.get().mode())
                        && !GuestCommand.MODE_WEB_FAST.equals(Session.get().mode())
                        && !GuestCommand.MODE_DASHBOARD_FAST.equals(Session.get().mode())) {
                    AuxDashboard.get().start(app, prefs);
                }
            } catch (Throwable e) {
                failure = e;
            }
            Throwable result = failure;
            ui.post(() -> {
                busy = false;
                applyRunningState(Session.get().running());
                if (result != null) {
                    if (webPane.getVisibility() == View.VISIBLE) dashboardFailed();
                    toast("Could not restart the dashboard: " + describe(result));
                } else if (AuxDashboard.get().running()
                        && webPane.getVisibility() == View.VISIBLE) {
                    waitForDashboard();
                }
            });
        }, "dashboard-settings-restart");
        restart.setDaemon(true);
        restart.start();
        return true;
    }

    @Override
    public void addStorageFolder() {
        pickFolder();
    }

    @Override
    public void importFolder(Prefs.Root root) {
        runMirror(root, true);
    }

    @Override
    public void exportFolder(Prefs.Root root) {
        runMirror(root, false);
    }

    @Override
    public void removeFolder(Prefs.Root root) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + root.label + "?")
                .setMessage("The folder is unlinked from Hermes and the local copy in "
                        + "/mnt/android/" + root.label + " is deleted. Files on the phone "
                        + "are not touched. Restart the container to drop the mount.")
                .setPositiveButton(R.string.yes, (d, w) -> dropFolder(root))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void requestIgnoreBatteryOptimizations() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                toast("Open battery settings manually for this app.");
            }
        }
    }

    private void showAbout() {
        Env env = Env.get(this);
        String text = "Hermes Loader " + versionName() + "\n"
                + "ABI: " + Env.abiDir() + " (" + Env.debianArch() + ")\n"
                + "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n\n"
                + "Rootfs: " + env.rootfs + "\n"
                + "Storage (never wiped by updates):\n" + env.storage + "\n\n"
                + "chroot-ng (GPL-2.0+). Debian Trixie arm64 rootfs (glibc).\n"
                + "Hermes Agent: " + prefs.repo();
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_about)
                .setMessage(text)
                .setPositiveButton("OK", null)
                .show();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void confirm(int messageRes, Runnable action) {
        new AlertDialog.Builder(this)
                .setMessage(messageRes)
                .setPositiveButton(R.string.yes, (d, w) -> action.run())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // --------------------------------------------------------------- startup

    private void welcome() {
        String message;
        if (getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            message = "READY · Start Agent for the console · WEB opens the dashboard";
        } else {
            message = "READY · Start Agent for the console\n"
                    + "WEB opens the dashboard · SHELL opens Linux\n"
                    + "First setup downloads the Linux and Hermes bundle.";
        }
        welcomeView.setText(message);
        welcomeView.setVisibility(View.VISIBLE);
    }

    private void maybeAskForNotifications() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
    }

    // --------------------------------------------------------------- helpers

    private void toast(String text) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

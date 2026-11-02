package com.hermes.loader;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;

/**
 * A scrollable, selectable, ANSI-coloured console output view.
 *
 * <p>An {@link EditText} is used purely as a text container: appending to its
 * {@link Editable} makes the platform relayout the text efficiently, which a
 * plain TextView does not do. It is explicitly kept multi-line and top-aligned:
 * {@code setInputType()} must never be called here, because TextView derives
 * "single line" from the input type and would collapse the whole console into
 * one horizontally scrolling row.
 */
public class TerminalView extends ScrollView {

    private static final int FOLLOW_SLOP_DP = 48;
    private static final String FONT_ASSET = "fonts/CascadiaMono.ttf";

    private final EditText text;
    private final AnsiScreen screen;
    private boolean follow = true;
    private boolean scrollScheduled;

    public TerminalView(Context context) {
        super(context);
        setFillViewport(false);
        setBackgroundColor(0xFF0C1015);

        text = new EditText(context);
        text.setTypeface(typeface(context));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        text.setTextColor(0xFFDFE9E8);
        text.setBackground(null);
        text.setLineSpacing(0f, 1.0f);
        text.setIncludeFontPadding(false);
        text.setPadding(dp(10), dp(8), dp(10), dp(10));
        text.setGravity(Gravity.TOP | Gravity.START);
        text.setVerticalScrollBarEnabled(false);
        text.setHorizontalScrollBarEnabled(false);
        text.setOverScrollMode(OVER_SCROLL_NEVER);
        text.setCursorVisible(false);
        text.setShowSoftInputOnFocus(false);
        text.setKeyListener(null);
        text.setTextIsSelectable(true);

        addView(text, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Must stay last: several TextView setters re-apply the single-line flag.
        text.setSingleLine(false);
        text.setHorizontallyScrolling(false);
        text.setEllipsize(null);

        Editable editable = text.getText();
        screen = new AnsiScreen(editable);
    }

    /**
     * Bundled monospace font with real box-drawing, block and braille glyphs.
     * The platform's fallback font renders some of them at a different advance,
     * which makes every panel border ragged; this one is strictly monospaced.
     */
    public static Typeface typeface(Context context) {
        try {
            return Typeface.createFromAsset(context.getAssets(), FONT_ASSET);
        } catch (RuntimeException e) {
            return Typeface.MONOSPACE;
        }
    }

    /** How many character cells fit horizontally at the current font size. */
    public int columns() {
        int available = (text.getWidth() > 0 ? text.getWidth() : getWidth())
                - text.getPaddingLeft() - text.getPaddingRight();
        if (available <= 0) {
            available = getResources().getDisplayMetrics().widthPixels - dp(20);
        }
        float cell = text.getPaint().measureText("M");
        if (cell <= 0f) return 80;
        return Math.max(30, Math.min(400, (int) Math.floor(available / cell)));
    }

    /** How many character rows fit vertically. Used for the guest PTY size. */
    public int rows() {
        int available = (getHeight() > 0 ? getHeight() : getResources()
                .getDisplayMetrics().heightPixels) - text.getPaddingTop() - text.getPaddingBottom();
        if (available <= 0) {
            available = getResources().getDisplayMetrics().heightPixels / 2;
        }
        android.graphics.Paint.FontMetricsInt fm = text.getPaint().getFontMetricsInt();
        int line = Math.max(1, (int) Math.round(fm.descent - fm.ascent));
        return Math.max(12, Math.min(200, available / line));
    }

    private void syncScreenSize() {
        screen.resize(columns(), rows());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        syncScreenSize();
    }

    public void append(String data) {
        if (data == null || data.isEmpty()) return;
        int y = getScrollY();
        screen.feed(data);
        screen.trim();
        if (!follow && getScrollY() != y) {
            // Reading history: new output must not move the viewport, whatever
            // the text view did to its parent while relaying it.
            scrollTo(0, y);
        }
        if (follow && !scrollScheduled) {
            scrollScheduled = true;
            post(() -> {
                scrollScheduled = false;
                if (!follow) return;
                scrollToCursor();
            });
        }
    }

    /**
     * Keeps the terminal's own cursor visible.
     *
     * <p>Full-screen guest programs (the Hermes TUI) paint with absolute cursor
     * addressing and leave the rows below their frame blank. Scrolling to the
     * end of the buffer therefore parks the viewport in that blank area; the
     * cursor is where the program is actually drawing, so follow it instead.
     */
    private void scrollToCursor() {
        android.text.Layout layout = text.getLayout();
        if (layout == null) {
            fullScroll(View.FOCUS_DOWN);
            return;
        }
        int offset = Math.min(screen.cursorOffset(), text.length());
        int line = layout.getLineForOffset(offset);
        int top = layout.getLineTop(line);
        int bottom = layout.getLineBottom(line);
        int viewport = getHeight();
        int y = getScrollY();
        if (bottom > y + viewport || top < y) {
            scrollTo(0, Math.max(0, bottom - viewport + dp(6)));
        }
    }

    public void appendLine(String data) {
        append(data + "\n");
    }

    public void clear() {
        screen.clear();
        follow = true;
    }

    /** Plain text of everything currently on screen (for copy/share). */
    public String dump() {
        return text.getText().toString();
    }

    public void jumpToBottom() {
        follow = true;
        fullScroll(View.FOCUS_DOWN);
    }

    /** Applies a new console font size in SP without losing the buffer. */
    public void setFontSp(float sp) {
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        syncScreenSize();
        if (follow) {
            post(() -> fullScroll(View.FOCUS_DOWN));
        }
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        View child = getChildAt(0);
        if (child == null) return;
        int distanceFromBottom = child.getBottom() - (t + getHeight());
        follow = distanceFromBottom <= dp(FOLLOW_SLOP_DP);
    }

    /**
     * Keeps the viewport where the user put it.
     *
     * <p>The console is a single child <em>taller than the viewport</em>, so the
     * ScrollView's "reveal the focused child" scrolls to that child's top —
     * i.e. to the very first line of the log. The text view takes focus as soon
     * as it is tapped (selection needs it), which is exactly when the user is
     * about to scroll: the console jumped back to the beginning, and scrolling
     * again seemed to be undone. Only {@link #append} (while following) and
     * {@link #jumpToBottom} are allowed to move the viewport.
     */
    @Override
    public void requestChildFocus(View child, View focused) {
        int x = getScrollX();
        int y = getScrollY();
        super.requestChildFocus(child, focused);
        if (getScrollX() != x || getScrollY() != y) {
            scrollTo(x, y);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

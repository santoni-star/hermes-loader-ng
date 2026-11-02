package com.hermes.loader;

import android.graphics.Typeface;
import android.text.Editable;
import android.text.Spannable;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;

/**
 * Turns a raw byte-stream (with ANSI escape sequences) into styled text inside
 * an {@link Editable}.
 *
 * <p>This is a deliberately small, line-oriented terminal emulator. It keeps a
 * real cursor (line + column) and implements the subset of VT100 that
 * prompt_toolkit — the library behind the Hermes interactive CLI — actually
 * emits: cursor up/down/left/right, erase-line, erase-display, carriage
 * return, backspace, tabs, SGR colours and autowrap.
 *
 * <p>The important difference from a naive "append the stream" parser is that
 * text written at a position <em>overwrites</em> what is there instead of
 * being inserted. Without that, every prompt_toolkit redraw shifted the whole
 * line to the right and box-drawing borders fell apart.
 *
 * <p>Cursor addressing is relative to the visible tail of the buffer (the last
 * {@link #rows} lines), which is what an inline (non-full-screen) application
 * expects. Full-screen alternate buffers are not emulated; Hermes uses an
 * inline TUI, so they are not needed.
 */
final class AnsiScreen {

    static final int MAX_CHARS = 200_000;
    private static final int TRIM_CHUNK = 50_000;

    private static final int DEFAULT_FG = 0xFFDFE9E8;
    private static final int DEFAULT_BG = 0xFF0C1015;

    private static final int[] PALETTE = {
            0xFF1A1A1A, 0xFFCD3131, 0xFF0DBC79, 0xFFE5E510,
            0xFF2472C8, 0xFFBC3FBC, 0xFF11A8CD, 0xFFE5E5E5,
            0xFF666666, 0xFFF14C4C, 0xFF23D18B, 0xFFF5F543,
            0xFF3B8EEA, 0xFFD670D6, 0xFF29B8DB, 0xFFFFFFFF,
    };

    private static final int ST_TEXT = 0;
    private static final int ST_ESC = 1;
    private static final int ST_CSI = 2;
    private static final int ST_OSC = 3;
    private static final int ST_OSC_ESC = 4;
    private static final int ST_CHARSET = 5;

    private final Editable buf;

    private int state = ST_TEXT;
    private final StringBuilder seq = new StringBuilder();
    private final StringBuilder run = new StringBuilder();

    private int fg = -1;
    private int bg = -1;
    private boolean bold;
    private boolean dim;
    private boolean italic;
    private boolean underline;
    private boolean strike;
    private boolean inverse;

    /** Insertion point as an absolute offset into {@link #buf}. */
    private int cursor;
    /** Cursor preserved by ESC 7 / restored by ESC 8. */
    private int savedCursor = -1;

    private int columns = 100;
    private int rows = 40;
    private boolean autowrap = true;

    AnsiScreen(Editable buf) {
        this.buf = buf;
        this.cursor = buf.length();
    }

    /** Called by the view whenever the render area or font size changes. */
    void resize(int columns, int rows) {
        this.columns = Math.max(20, Math.min(500, columns));
        this.rows = Math.max(6, Math.min(300, rows));
        if (cursor > buf.length()) {
            cursor = buf.length();
        }
    }

    void feed(String s) {
        if (s == null || s.isEmpty()) return;
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            switch (state) {
                case ST_TEXT:
                    if (c == 0x1b) {
                        flushRun();
                        state = ST_ESC;
                        seq.setLength(0);
                    } else if (c == '\r') {
                        flushRun();
                        carriageReturn();
                    } else if (c == '\n') {
                        flushRun();
                        newline();
                    } else if (c == '\b') {
                        flushRun();
                        backspace();
                    } else if (c == '\t') {
                        flushRun();
                        tab();
                    } else if (c < 0x20 || c == 0x7f) {
                        // Other control characters are not used by the agent.
                    } else {
                        run.append(c);
                    }
                    break;

                case ST_ESC:
                    if (c == '[') {
                        state = ST_CSI;
                        seq.setLength(0);
                    } else if (c == ']') {
                        state = ST_OSC;
                        seq.setLength(0);
                    } else if (c == '(' || c == ')' || c == '#') {
                        state = ST_CHARSET;
                    } else if (c == '7') {
                        savedCursor = cursor;
                        state = ST_TEXT;
                    } else if (c == '8') {
                        restoreCursor();
                        state = ST_TEXT;
                    } else if (c == 'D') {
                        newline();
                        state = ST_TEXT;
                    } else if (c == 'E') {
                        carriageReturn();
                        newline();
                        state = ST_TEXT;
                    } else if (c == 'M') {
                        reverseIndex();
                        state = ST_TEXT;
                    } else if (c == 'c') {
                        buf.clear();
                        cursor = 0;
                        savedCursor = -1;
                        resetStyle();
                        state = ST_TEXT;
                    } else if (c == 0x1b) {
                        // Keep waiting for a real introducer.
                    } else {
                        state = ST_TEXT;
                    }
                    break;

                case ST_CHARSET:
                    state = ST_TEXT;
                    break;

                case ST_OSC:
                    if (c == 0x07) {
                        state = ST_TEXT;
                    } else if (c == 0x1b) {
                        state = ST_OSC_ESC;
                    }
                    break;

                case ST_OSC_ESC:
                    state = (c == '\\') ? ST_TEXT : ST_OSC;
                    break;

                case ST_CSI:
                    if (c >= 0x40 && c <= 0x7e) {
                        handleCsi(seq.toString(), c);
                        state = ST_TEXT;
                        seq.setLength(0);
                    } else if (seq.length() < 256) {
                        seq.append(c);
                    }
                    break;

                default:
                    state = ST_TEXT;
                    break;
            }
        }
        flushRun();
    }

    void clear() {
        buf.clear();
        cursor = 0;
        savedCursor = -1;
        resetStyle();
    }

    void trim() {
        int len = buf.length();
        if (len <= MAX_CHARS) return;
        int cut = len - MAX_CHARS + TRIM_CHUNK;
        int nl = TextUtils.indexOf(buf, '\n', cut);
        if (nl >= 0) cut = nl + 1;
        buf.delete(0, cut);
        cursor = Math.max(0, cursor - cut);
        if (savedCursor >= 0) {
            savedCursor = Math.max(0, savedCursor - cut);
        }
    }

    // ------------------------------------------------------------ line/column

    private int lineStart(int pos) {
        int p = Math.max(0, Math.min(pos, buf.length()));
        int i = p - 1;
        while (i >= 0 && buf.charAt(i) != '\n') {
            i--;
        }
        return i + 1;
    }

    private int lineEnd(int pos) {
        int i = Math.max(0, Math.min(pos, buf.length()));
        int n = buf.length();
        while (i < n && buf.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    /** Display width of the line prefix {@code [start, pos)}. */
    private int columnAt(int pos) {
        int p = Math.max(0, Math.min(pos, buf.length()));
        int i = lineStart(p);
        int col = 0;
        while (i < p) {
            int cp = Character.codePointAt(buf, i);
            int step = Character.charCount(cp);
            if (i + step > p) break;
            col += width(cp);
            i += step;
        }
        return col;
    }

    /**
     * Offset of display column {@code target} on the line starting at
     * {@code start}. When {@code pad} is set and the line is shorter, spaces
     * are inserted so the returned offset really is at that column.
     */
    private int posAtColumn(int start, int target, boolean pad) {
        int p = Math.max(0, Math.min(start, buf.length()));
        if (target <= 0) return p;
        int col = 0;
        int len = buf.length();
        while (p < len && buf.charAt(p) != '\n') {
            int cp = Character.codePointAt(buf, p);
            int w = width(cp);
            if (w > 0 && col + w > target) {
                return p; // target lands inside a wide glyph
            }
            col += w;
            p += Character.charCount(cp);
            if (col >= target) {
                return p;
            }
        }
        if (pad && target > col) {
            int need = target - col;
            buf.insert(p, spaces(need));
            p += need;
        }
        return p;
    }

    private void moveTo(int target) {
        cursor = Math.max(0, Math.min(target, buf.length()));
    }

    // -------------------------------------------------------------- movement

    private void carriageReturn() {
        cursor = lineStart(cursor);
    }

    private void newline() {
        int at = Math.max(0, Math.min(cursor, buf.length()));
        int eol = lineEnd(at);
        int col = columnAt(at);
        if (at >= eol) {
            // End of the buffer: append a real line break.
            buf.insert(eol, "\n");
            cursor = posAtColumn(eol + 1, col, false);
        } else {
            // Cursor in the middle of an existing line: move down at the same
            // column instead of splitting the line.
            int next = eol + 1;
            if (next <= buf.length()) {
                cursor = posAtColumn(next, col, true);
            } else {
                buf.insert(eol, "\n");
                cursor = posAtColumn(eol + 1, col, false);
            }
        }
    }

    private void reverseIndex() {
        int col = columnAt(cursor);
        int start = lineStart(cursor);
        if (start == 0) {
            cursor = posAtColumn(0, col, true);
            return;
        }
        cursor = posAtColumn(lineStart(start - 1), col, true);
    }

    private void backspace() {
        int start = lineStart(cursor);
        if (cursor <= start) return;
        int p = cursor;
        // Skip a combining mark so the cursor lands on its base character.
        int prev = Character.codePointBefore(buf, p);
        p -= Character.charCount(prev);
        while (p > start) {
            int cp = Character.codePointBefore(buf, p);
            if (width(cp) != 0) break;
            p -= Character.charCount(cp);
        }
        cursor = p;
    }

    private void tab() {
        int col = columnAt(cursor);
        int next = (col / 8 + 1) * 8;
        cursor = posAtColumn(lineStart(cursor), next, true);
    }

    private void restoreCursor() {
        cursor = savedCursor >= 0
                ? Math.max(0, Math.min(savedCursor, buf.length()))
                : cursor;
    }

    private int screenTop() {
        int total = 1;
        for (int p = 0; p < buf.length(); p++) {
            if (buf.charAt(p) == '\n') total++;
        }
        int skip = Math.max(0, total - rows);
        int p = 0;
        for (int i = 0; i < skip && p < buf.length(); i++) {
            p = lineEnd(p) + 1;
        }
        return Math.min(p, buf.length());
    }

    private int lineDown(int start, int times) {
        int p = Math.max(0, Math.min(start, buf.length()));
        for (int i = 0; i < times; i++) {
            int eol = lineEnd(p);
            if (eol < buf.length() && buf.charAt(eol) == '\n') {
                p = eol + 1;
            } else {
                buf.insert(eol, "\n");
                p = eol + 1;
            }
        }
        return p;
    }

    private int lineUp(int start, int times) {
        int p = Math.max(0, Math.min(start, buf.length()));
        for (int i = 0; i < times; i++) {
            int ls = lineStart(p);
            if (ls == 0) return 0;
            p = lineStart(ls - 1);
        }
        return p;
    }

    // ---------------------------------------------------------------- writing

    private void flushRun() {
        if (run.length() == 0) return;
        String t = run.toString();
        run.setLength(0);
        write(t);
    }

    /**
     * Writes text at the cursor. Characters replace existing cells; only at
     * the end of a line is new text inserted. Autowrap is honoured unless the
     * client disabled it with {@code CSI ? 7 l} (prompt_toolkit does).
     */
    private void write(String t) {
        if (t == null || t.isEmpty()) return;
        if (cursor > buf.length()) cursor = buf.length();

        int start = 0;
        int col = columnAt(cursor);
        int i = 0;
        while (i < t.length()) {
            int cp = t.codePointAt(i);
            int step = Character.charCount(cp);
            int w = width(cp);
            if (autowrap && w > 0 && col > 0 && col + w > columns
                    && cursor >= lineEnd(cursor)) {
                if (i > start) {
                    putText(t.substring(start, i));
                }
                newlineAtEnd();
                col = 0;
                start = i;
            }
            col += w;
            i += step;
        }
        if (start < t.length()) {
            putText(t.substring(start));
        }
    }

    private void newlineAtEnd() {
        int eol = lineEnd(cursor);
        buf.insert(eol, "\n");
        cursor = eol + 1;
    }

    private void putText(String t) {
        if (t.isEmpty()) return;
        int at = Math.min(cursor, buf.length());
        int eol = lineEnd(at);

        int newWidth = displayWidth(t);
        int oldEnd = at;
        int oldWidth = 0;
        while (oldEnd < eol && oldWidth < newWidth) {
            int cp = Character.codePointAt(buf, oldEnd);
            oldWidth += width(cp);
            oldEnd += Character.charCount(cp);
        }

        if (oldEnd > at) {
            buf.delete(at, oldEnd);
        }
        buf.insert(at, t);
        applyStyle(at, at + t.length());
        cursor = at + t.length();

        // A narrow character wrote over a wide cell: keep the leftover blank
        // cell so columns after it do not shift left.
        if (oldWidth > newWidth) {
            int pad = oldWidth - newWidth;
            int after = cursor;
            buf.insert(after, spaces(pad));
            cursor = after;
        }
    }

    private static String spaces(int n) {
        if (n <= 0) return "";
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append(' ');
        return b.toString();
    }

    private static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            w += width(cp);
            i += Character.charCount(cp);
        }
        return w;
    }

    /**
     * Monospace-aware approximation of wcwidth(). Box-drawing and block
     * characters are one cell; CJK and emoji are two; combining marks are
     * zero. This matches what prompt_toolkit assumes when it lays out panels.
     */
    private static int width(int cp) {
        if (cp < 0x20 || cp == 0x7f) return 0;
        int type = Character.getType(cp);
        if (type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK) {
            return 0;
        }
        if (cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0x200E
                || cp == 0x200F || cp == 0x2060 || cp == 0xFEFF) {
            return 0;
        }
        if ((cp >= 0x1100 && cp <= 0x115F)
                || (cp >= 0x2E80 && cp <= 0xA4CF && cp != 0x303F)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE10 && cp <= 0xFE19)
                || (cp >= 0xFE30 && cp <= 0xFE6F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x1F000 && cp <= 0x1FAFF)
                || (cp >= 0x20000 && cp <= 0x3FFFD)) {
            return 2;
        }
        return 1;
    }

    // --------------------------------------------------------------- styling

    private void applyStyle(int start, int end) {
        if (end <= start) return;

        int f = fg >= 0 ? fg : DEFAULT_FG;
        int b = bg >= 0 ? bg : DEFAULT_BG;
        if (inverse) {
            int tmp = f;
            f = b;
            b = tmp;
        }
        if (dim) {
            f = blend(f, DEFAULT_BG, 0.45f);
        }

        buf.setSpan(new ForegroundColorSpan(f), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bg >= 0 || inverse) {
            buf.setSpan(new BackgroundColorSpan(b), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (bold) {
            buf.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (italic) {
            buf.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (underline) {
            buf.setSpan(new UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (strike) {
            buf.setSpan(new StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void resetStyle() {
        fg = -1;
        bg = -1;
        bold = dim = italic = underline = strike = inverse = false;
    }

    private static int blend(int from, int to, float amount) {
        int r = (int) (((from >> 16) & 0xFF) * (1 - amount) + ((to >> 16) & 0xFF) * amount);
        int g = (int) (((from >> 8) & 0xFF) * (1 - amount) + ((to >> 8) & 0xFF) * amount);
        int b = (int) ((from & 0xFF) * (1 - amount) + (to & 0xFF) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int xterm256(int index) {
        if (index < 0) return DEFAULT_FG;
        if (index < 16) return PALETTE[index];
        if (index < 232) {
            int i = index - 16;
            int[] steps = {0, 95, 135, 175, 215, 255};
            int r = steps[i / 36];
            int g = steps[(i / 6) % 6];
            int b = steps[i % 6];
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        if (index < 256) {
            int v = 8 + (index - 232) * 10;
            if (v > 255) v = 255;
            return 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        return DEFAULT_FG;
    }

    // ------------------------------------------------------------------- CSI

    private void handleCsi(String params, char finalChar) {
        boolean priv = params.startsWith("?");
        int[] ps = ints(priv ? params.substring(1) : params);

        switch (finalChar) {
            case 'm':
                sgr(params);
                break;

            case 'h':
            case 'l':
                if (priv) {
                    for (int p : ps) {
                        if (p == 7) {
                            autowrap = finalChar == 'h';
                        }
                    }
                }
                break;

            case 'A':
                cursor = posAtColumn(lineUp(cursor, arg(ps, 0, 1)),
                        columnAt(cursor), true);
                break;
            case 'B':
            case 'e':
                cursor = posAtColumn(lineDown(cursor, arg(ps, 0, 1)),
                        columnAt(cursor), true);
                break;
            case 'C':
            case 'a':
                cursor = posAtColumn(lineStart(cursor),
                        columnAt(cursor) + arg(ps, 0, 1), true);
                break;
            case 'D':
                cursor = posAtColumn(lineStart(cursor),
                        Math.max(0, columnAt(cursor) - arg(ps, 0, 1)), true);
                break;
            case 'E':
                cursor = posAtColumn(lineDown(cursor, arg(ps, 0, 1)), 0, false);
                break;
            case 'F':
                cursor = posAtColumn(lineUp(cursor, arg(ps, 0, 1)), 0, false);
                break;
            case 'G':
            case '`':
                cursor = posAtColumn(lineStart(cursor),
                        Math.max(0, arg(ps, 0, 1) - 1), true);
                break;
            case 'd':
                cursor = cursorAtScreenRow(Math.max(0, arg(ps, 0, 1) - 1),
                        columnAt(cursor));
                break;
            case 'H':
            case 'f':
                cursor = cursorAtScreenRow(Math.max(0, arg(ps, 0, 1) - 1),
                        Math.max(0, arg(ps, 1, 1) - 1));
                break;

            case 'K': {
                int n = arg(ps, 0, 0);
                int start = lineStart(cursor);
                int end = lineEnd(cursor);
                if (n == 1) {
                    int at = cursor;
                    buf.delete(start, at);
                    cursor = start;
                } else if (n == 2) {
                    buf.delete(start, end);
                    cursor = start;
                } else {
                    buf.delete(cursor, end);
                }
                break;
            }

            case 'J': {
                int n = arg(ps, 0, 0);
                if (n == 2 || n == 3) {
                    buf.clear();
                    cursor = 0;
                    savedCursor = -1;
                } else if (n == 1) {
                    int at = Math.min(cursor, buf.length());
                    buf.delete(0, at);
                    cursor = 0;
                } else {
                    int at = Math.min(cursor, buf.length());
                    buf.delete(at, buf.length());
                }
                break;
            }

            case 'X': {
                int n = Math.max(1, arg(ps, 0, 1));
                int end = posAtColumn(lineStart(cursor), columnAt(cursor) + n, true);
                if (end > cursor) {
                    buf.replace(cursor, end, spaces(n));
                    cursor = Math.min(cursor, buf.length());
                }
                break;
            }

            case '@': {
                int n = Math.max(1, arg(ps, 0, 1));
                int at = Math.min(cursor, buf.length());
                buf.insert(at, spaces(n));
                break;
            }

            case 'P': {
                int n = Math.max(1, arg(ps, 0, 1));
                int end = posAtColumn(lineStart(cursor), columnAt(cursor) + n, true);
                if (end > cursor) {
                    buf.delete(cursor, end);
                }
                break;
            }

            case 'L': {
                int n = Math.max(1, arg(ps, 0, 1));
                int start = lineStart(cursor);
                int col = columnAt(cursor);
                String blanks = repeat("\n", n);
                buf.insert(start, blanks);
                cursor = posAtColumn(start + blanks.length(), col, true);
                break;
            }

            case 'M': {
                int n = Math.max(1, arg(ps, 0, 1));
                int start = lineStart(cursor);
                int col = columnAt(cursor);
                for (int i = 0; i < n; i++) {
                    int eol = lineEnd(start);
                    if (eol < buf.length() && buf.charAt(eol) == '\n') {
                        buf.delete(start, eol + 1);
                    } else {
                        buf.delete(start, eol);
                        break;
                    }
                }
                cursor = posAtColumn(start, col, true);
                break;
            }

            case 's':
                savedCursor = cursor;
                break;
            case 'u':
                restoreCursor();
                break;

            default:
                // Cursor visibility, bracketed paste, mouse modes, scroll
                // regions and device reports are irrelevant for this view.
                break;
        }
    }

    private int cursorAtScreenRow(int row, int col) {
        int start = screenTop();
        int line = lineDown(start, row);
        return posAtColumn(line, Math.max(0, col), true);
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    private void sgr(String params) {
        String p = params.isEmpty() ? "0" : params;
        String[] parts = p.split(";", -1);
        for (int i = 0; i < parts.length; i++) {
            int v = parse(parts[i], -1);
            switch (v) {
                case 0:
                    resetStyle();
                    break;
                case 1:
                    bold = true;
                    break;
                case 2:
                    dim = true;
                    break;
                case 3:
                    italic = true;
                    break;
                case 4:
                    underline = true;
                    break;
                case 7:
                    inverse = true;
                    break;
                case 9:
                    strike = true;
                    break;
                case 22:
                    bold = false;
                    dim = false;
                    break;
                case 23:
                    italic = false;
                    break;
                case 24:
                    underline = false;
                    break;
                case 27:
                    inverse = false;
                    break;
                case 29:
                    strike = false;
                    break;
                case 39:
                    fg = -1;
                    break;
                case 49:
                    bg = -1;
                    break;
                case 38:
                case 48: {
                    int mode = i + 1 < parts.length ? parse(parts[i + 1], -1) : -1;
                    int color = -1;
                    if (mode == 5 && i + 2 < parts.length) {
                        color = xterm256(parse(parts[i + 2], -1));
                        i += 2;
                    } else if (mode == 2 && i + 4 < parts.length) {
                        int r = clamp(parse(parts[i + 2], 0));
                        int g = clamp(parse(parts[i + 3], 0));
                        int b = clamp(parse(parts[i + 4], 0));
                        color = 0xFF000000 | (r << 16) | (g << 8) | b;
                        i += 4;
                    } else {
                        i = parts.length; // malformed sequence, bail out
                    }
                    if (v == 38) {
                        fg = color;
                    } else {
                        bg = color;
                    }
                    break;
                }
                default:
                    if (v >= 30 && v <= 37) {
                        fg = PALETTE[v - 30];
                    } else if (v >= 40 && v <= 47) {
                        bg = PALETTE[v - 40];
                    } else if (v >= 90 && v <= 97) {
                        fg = PALETTE[8 + (v - 90)];
                    } else if (v >= 100 && v <= 107) {
                        bg = PALETTE[8 + (v - 100)];
                    }
                    break;
            }
        }
    }

    private static int[] ints(String params) {
        if (params == null || params.isEmpty()) {
            return new int[0];
        }
        String[] parts = params.split(";", -1);
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = parse(parts[i], -1);
        }
        return out;
    }

    private static int arg(int[] ps, int index, int def) {
        if (index >= ps.length) return def;
        int v = ps[index];
        return v < 0 ? def : v;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static int parse(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try {
            int i = 0;
            while (i < s.length() && !Character.isDigit(s.charAt(i))) i++;
            if (i == s.length()) return def;
            int v = 0;
            while (i < s.length() && Character.isDigit(s.charAt(i))) {
                v = v * 10 + (s.charAt(i) - '0');
                if (v > 100_000) return def;
                i++;
            }
            return v;
        } catch (RuntimeException e) {
            return def;
        }
    }
}

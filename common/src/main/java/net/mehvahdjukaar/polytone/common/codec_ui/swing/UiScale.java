package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import com.formdev.flatlaf.util.UIScale;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Toolkit;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Thin delegate over FlatLaf's {@link UIScale}. Use these helpers for every
 * explicit pixel value so dimensions participate in the same scaling pipeline
 * FlatLaf already uses for fonts and L&amp;F defaults.
 *
 * <p><b>Do NOT manually scale font sizes</b> — FlatLaf rewrites every L&amp;F
 * font default based on {@code flatlaf.uiScale}. Multiplying again here yields
 * fonts that grow with {@code scale^2} while boxes only grow with {@code
 * scale^1} (the "text grew but boxes didn't" bug). Use {@link #deriveFont(Font,
 * int, float)} which is relative to the L&amp;F-scaled base.</p>
 */
public final class UiScale {

    private UiScale() {}

    // ---- UI zoom (100% = 1.0) --------------------------------------------------
    // The editor's zoom nudges the base font point size, which grows FONTS but NOT the
    // DPI uiScale (that is pinned for reliable HiDPI detection). Left alone, spacing,
    // insets and font-size deltas would stay put while text grew — cramped boxes, tiny
    // "opt" badges, first fields clipped by fixed card padding. So we mirror the same
    // ratio here and fold it into px()/deriveFont()/zoomLogical() so the whole pixel
    // system tracks the font. SwingSchemaEditor keeps this in step with the base font.
    private static volatile float zoom = 1f;

    /** Set the UI zoom ratio (base font pt / default pt). Call BEFORE {@code FlatLaf.updateUI()}. */
    public static void setZoom(float z) { zoom = z > 0f ? z : 1f; }

    /** Current UI zoom ratio (1.0 = 100%). */
    public static float zoom() { return zoom; }

    /**
     * A LOGICAL (pre-uiScale) value nudged by the UI zoom — for values FlatLaf scales by
     * uiScale ITSELF, so we must NOT also run them through {@link #px}: {@code FlatLineBorder}
     * insets and {@code UIManager} metric defaults. Final size = {@code base * zoom * uiScale}.
     */
    public static int zoomLogical(int base) { return Math.max(1, Math.round(base * zoom)); }

    // ---- Spacing tokens (LOGICAL px). Use these everywhere instead of ad-hoc numbers
    // so the widget tree has a single, consistent rhythm.
    //   SMALL  — gap inside a row (label↔widget, button↔button in a tight cluster)
    //   MED    — gap between rows in a Record / List / Map
    //   LARGE  — gap between sections; outer window margins
    public static int small()  { return px(4);  }
    public static int med()    { return px(8);  }
    public static int large()  { return px(16); }

    // ---- Editor (code / JSON) font — INDEPENDENT of the UI zoom ---------------------------
    // The monospace areas were pinned at px(15): that tracked HiDPI uiScale but NOT the user's
    // zoom, so they read as oversized with no way to shrink them ("zoom doesn't affect it").
    // They get their OWN persisted logical-pt size instead, adjusted with Ctrl+mouse-wheel while
    // hovering a code area, and applied live to every registered area. Only uiScale is folded in
    // (HiDPI crispness) — deliberately NOT the zoom, so chrome and code size move separately.
    private static final java.util.prefs.Preferences EDITOR_PREFS =
            java.util.prefs.Preferences.userNodeForPackage(UiScale.class);
    private static final String EDITOR_PT_KEY = "editorFontPt";
    private static final int EDITOR_PT_DEFAULT = 13;
    private static final int EDITOR_PT_MIN = 8;
    private static final int EDITOR_PT_MAX = 28;
    private static int editorPt = clampEditorPt(EDITOR_PREFS.getInt(EDITOR_PT_KEY, EDITOR_PT_DEFAULT));

    // Weak so a closed editor tab's area is collected — no listener leak.
    private static final java.util.List<java.lang.ref.WeakReference<JComponent>> editorAreas =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private static String monoFamily;

    private static int clampEditorPt(int pt) {
        return Math.max(EDITOR_PT_MIN, Math.min(EDITOR_PT_MAX, pt));
    }

    /**
     * A real, non-serif monospaced family. {@code Font.MONOSPACED} maps to Courier New on
     * Windows (serif, dated — the "serif feels wrong" report); pick the first genuine coding
     * font actually installed, falling back to the logical monospace only if none are present.
     */
    private static String monoFamily() {
        if (monoFamily != null) return monoFamily;
        java.util.Set<String> avail = new java.util.HashSet<>(java.util.Arrays.asList(
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String f : new String[]{"JetBrains Mono", "Cascadia Code", "Cascadia Mono", "Consolas",
                "SF Mono", "Menlo", "DejaVu Sans Mono", "Liberation Mono", "Noto Sans Mono"}) {
            if (avail.contains(f)) { monoFamily = f; return monoFamily; }
        }
        monoFamily = Font.MONOSPACED;
        return monoFamily;
    }

    /** The monospaced font for code/JSON areas at the user's chosen editor size (HiDPI-scaled). */
    public static Font editorFont() {
        return new Font(monoFamily(), Font.PLAIN, UIScale.scale(editorPt));
    }

    /** Nudge the editor font ({@code deltaPt} pt; 0 resets) and restyle every open code area. */
    public static void adjustEditorFont(int deltaPt) {
        editorPt = deltaPt == 0 ? EDITOR_PT_DEFAULT : clampEditorPt(editorPt + deltaPt);
        EDITOR_PREFS.putInt(EDITOR_PT_KEY, editorPt);
        Font f = editorFont();
        editorAreas.removeIf(ref -> {
            JComponent c = ref.get();
            if (c == null) return true;
            c.setFont(f);
            return false;
        });
    }

    /**
     * Bind a code area to the shared editor font: applies it now, keeps it in step with later
     * size changes, and wires Ctrl+mouse-wheel to zoom the code (forwarding a plain wheel to the
     * enclosing scroll pane so normal scrolling still works). Independent of the UI zoom.
     */
    public static void installEditorZoom(JComponent area) {
        area.setFont(editorFont());
        editorAreas.add(new java.lang.ref.WeakReference<>(area));
        area.addMouseWheelListener(e -> {
            if (e.isControlDown()) {
                adjustEditorFont(-e.getWheelRotation()); // wheel up (negative) => larger
                e.consume();
                return;
            }
            java.awt.Container p = area.getParent();
            while (p != null && !(p instanceof javax.swing.JScrollPane)) p = p.getParent();
            if (p != null) p.dispatchEvent(SwingUtilities.convertMouseEvent(area, e, p));
        });
    }

    /**
     * Initial scale factor for FlatLaf, returned as a {@code "Nx"} string
     * (e.g. {@code "2.0x"}). Pass this as the value of the {@code flatlaf.uiScale}
     * system property BEFORE {@code FlatDarkLaf.setup()}.
     *
     * <p>Detection priority — first hit wins:
     * <ol>
     *   <li>Explicit {@code -Dflatlaf.uiScale=...} override (returned as-is).</li>
     *   <li>Explicit {@code -Dpolytone.uiScale=...} override.</li>
     *   <li>{@code GDK_SCALE} env var (set by GNOME on HiDPI).</li>
     *   <li>{@code GDK_DPI_SCALE} env var (set by GTK/KDE on HiDPI).</li>
     *   <li>Physical display mode resolution — threshold-based bucketing:
     *       &ge;3200 wide or &ge;1800 tall &rArr; 2.0x;
     *       &ge;2560 / &ge;1440 &rArr; 1.5x; otherwise 1.0x.</li>
     *   <li>Last-resort fallback: {@code 1.0x}.</li>
     * </ol>
     * Never returns less than 1.0x.</p>
     *
     * <p>Why not {@link Toolkit#getScreenResolution()}? Because on most Linux
     * setups it returns 96 regardless of the actual display DPI. The DE sets a
     * scaling factor via env vars / settings the AWT toolkit doesn't read, so
     * relying on it produces unreadably small UI on 4K. Reading the raw display
     * mode is the only signal we can trust everywhere.</p>
     */
    public static String detectInitialScale() {
        // 1. Honor explicit user override.
        String env = System.getProperty("flatlaf.uiScale");
        if (env != null && !env.isBlank()) return env;
        String userOverride = System.getProperty("polytone.uiScale");
        if (userOverride != null && !userOverride.isBlank()) return userOverride;

        // 2. Linux: GDK_SCALE / GDK_DPI_SCALE — set by GNOME/KDE for HiDPI.
        String gdkScale = System.getenv("GDK_SCALE");
        if (gdkScale != null && !gdkScale.isBlank()) {
            try {
                float s = Float.parseFloat(gdkScale.trim());
                if (s >= 1f) return s + "x";
            } catch (NumberFormatException ignored) {}
        }
        String gdkDpi = System.getenv("GDK_DPI_SCALE");
        if (gdkDpi != null && !gdkDpi.isBlank()) {
            try {
                float s = Float.parseFloat(gdkDpi.trim());
                if (s >= 1f) return s + "x";
            } catch (NumberFormatException ignored) {}
        }

        // 3. Physical screen resolution. 4K = 3840x2160 regardless of DE state.
        try {
            java.awt.GraphicsDevice device = java.awt.GraphicsEnvironment
                    .getLocalGraphicsEnvironment().getDefaultScreenDevice();
            java.awt.DisplayMode mode = device.getDisplayMode();
            int width = mode.getWidth();
            int height = mode.getHeight();
            float scale;
            if (width >= 3200 || height >= 1800) scale = 2.0f;
            else if (width >= 2560 || height >= 1440) scale = 1.5f;
            else if (width >= 1920 || height >= 1080) scale = 1.0f;
            else scale = 1.0f;
            return scale + "x";
        } catch (Throwable ignored) {}

        // 4. Last-resort fallback.
        return "1.0x";
    }

    /** Effective scale that FlatLaf is currently using. */
    public static float scale() {
        return UIScale.getUserScaleFactor();
    }

    /** Effective scale as a "NN%" string for display. */
    public static String scaleAsPercent() {
        return Math.round(scale() * 100f) + "%";
    }

    /** Scale a logical pixel count via FlatLaf, including the UI zoom so spacing tracks the font. */
    public static int px(int base) {
        return UIScale.scale(Math.round(base * zoom));
    }

    /** Scaled preferred size (zoom-aware, via {@link #px}). */
    public static Dimension dim(int w, int h) {
        return new Dimension(px(w), px(h));
    }

    /** Scaled {@link Insets}. */
    public static Insets insets(int top, int left, int bottom, int right) {
        return new Insets(px(top), px(left), px(bottom), px(right));
    }

    /**
     * Derive a styled, size-shifted variant of an existing component font
     * WITHOUT manual scaling. {@code sizeDeltaLogical} is added in LOGICAL
     * points to the current (already-scaled) font size, so callers can ask for
     * "+2pt bold" or "-1pt italic" without re-scaling.
     */
    public static Font deriveFont(Font base, int style, float sizeDeltaLogical) {
        // Multiply the delta by the zoom too, so a "-3pt" badge stays the SAME proportion of
        // the field font at every zoom. Without this the fixed-pt delta is a large fraction of
        // a small zoomed-down font (badge shrinks far faster than its sibling) and a tiny
        // fraction of a zoomed-up one.
        float scaledDelta = sizeDeltaLogical * scale() * zoom;
        return base.deriveFont(style, base.getSize2D() + scaledDelta);
    }

    /**
     * Glue a single-line component's MAX height to its LIVE preferred height so a
     * {@code BoxLayout} parent (list / map rows, the form root) never stretches it
     * vertically — and RE-PIN whenever its font changes (theme switch / UI zoom).
     *
     * <p>The old pattern captured {@code getPreferredSize().height} ONCE at construction and
     * pinned {@code maximumSize} to it. After a zoom the font (and real preferred height)
     * grew but the cap stayed at the original height, so the widget's text got clipped — the
     * "lists cut off with zoom" bug. FlatLaf re-applies the zoomed default font on
     * {@code updateUI()}, which fires a {@code "font"} property change we re-pin on.</p>
     */
    public static void pinRowHeight(JComponent c) { installRowPin(c, false); }

    /** Like {@link #pinRowHeight} but also caps WIDTH to preferred — compact combos that
     *  must not span the whole form column. */
    public static void pinCompact(JComponent c) { installRowPin(c, true); }

    private static void installRowPin(JComponent c, boolean compactWidth) {
        Runnable pin = () -> {
            Dimension p = c.getPreferredSize();
            if (compactWidth) {
                c.setMaximumSize(new Dimension(p.width, p.height));
            } else {
                c.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.height));
                c.setMinimumSize(new Dimension(0, p.height));
            }
        };
        pin.run();
        // FlatLaf.updateUI() re-applies the (zoomed) default font, firing this event — but it
        // fires DURING updateUI's tree traversal. Re-pinning synchronously there mutates sizes
        // (→ invalidate) mid-layout and corrupts BoxLayout's non-reentrant size cache (the
        // "xTotal is null" NPE). Defer so it runs after the update/layout settles.
        c.addPropertyChangeListener("font", e -> SwingUtilities.invokeLater(pin));
    }

    // Guards {@link #maxHeightHugging} against reentrancy — EDT-only, so a plain identity set.
    private static final Set<Object> SIZE_GUARD = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Reentrancy-safe "fill available width, hug the LIVE preferred height" — the idiom for a
     * {@code BoxLayout} child that must not stretch vertically yet grows with a zoomed font.
     * Swing's {@code BoxLayout} size cache is not reentrant: if this component's size is queried
     * again while we're mid-computation (which a {@code FlatLaf.updateUI()} relayout can
     * provoke), return an unconstrained height to break the cycle instead of reading the
     * half-built cache and throwing {@code "xTotal is null"}.
     */
    public static Dimension maxHeightHugging(JComponent c) {
        if (!SIZE_GUARD.add(c)) return new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE);
        try {
            return new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height);
        } finally {
            SIZE_GUARD.remove(c);
        }
    }

    /** UIManager-default font for a key, or a sane fallback. */
    public static Font uiFont(String key) {
        Font f = UIManager.getFont(key);
        return f != null ? f : UIManager.getFont("Label.font");
    }

    /**
     * L&amp;F label font with a style/size tweak, derived FRESH from UIManager. This is the
     * only safe base inside {@code updateUI()} overrides: deriving from {@code getFont()}
     * there compounds the delta on every theme switch / zoom, because a derived font is not
     * a UIResource and {@code super.updateUI()} never replaces it.
     */
    public static Font labelFont(int style, float sizeDeltaLogical) {
        Font base = UIManager.getFont("Label.font");
        if (base == null) base = UIManager.getFont("defaultFont");
        if (base == null) base = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        return deriveFont(base, style, sizeDeltaLogical);
    }
}

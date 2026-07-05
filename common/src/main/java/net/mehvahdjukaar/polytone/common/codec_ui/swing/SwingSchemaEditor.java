package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.google.gson.JsonElement;
import net.mehvahdjukaar.polytone.common.codec_ui.SchemaCodec;
import net.mehvahdjukaar.polytone.common.codec_ui.SchemaEditor;
import org.jetbrains.annotations.Nullable;

import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Toolkit;
import java.lang.reflect.Field;
import java.util.function.Consumer;

/**
 * {@link SchemaEditor} implementation backed by the single-window {@link SwingWorkbench}:
 * every {@code open()} lands as one editor tab in the shared workbench frame. This class
 * also owns the one-time Swing/FlatLaf bootstrap shared by all entry points.
 */
public final class SwingSchemaEditor implements SchemaEditor {

    @Override
    public <A> void open(SchemaCodec<A> codec, @Nullable A initial, Consumer<A> onSave) {
        open(codec, "Schema Editor", initial, onSave);
    }

    /** Overload with a human-readable label used as the tab title. */
    public <A> void open(SchemaCodec<A> codec, String label, @Nullable A initial, Consumer<A> onSave) {
        open(codec, label, Side.CLIENT_RESOURCES, initial, onSave);
    }

    /** Full overload: {@code side} selects the registry view (see {@link Side}) used for
     *  encoding/validation ops — required for codecs touching datapack registries. */
    public <A> void open(SchemaCodec<A> codec, String label, Side side, @Nullable A initial, Consumer<A> onSave) {
        setupSwingDefaults();
        forceNonHeadless();
        JsonElement initialJson = initial == null ? null
                : codec.encodeStart(EditorOps.buildOps(side), initial).result().orElse(null);
        SwingWorkbench.openStandalone(codec, label, side, initialJson, onSave);
    }

    // -------------------- Shared Swing bootstrap --------------------

    private static volatile boolean swingSetupDone = false;

    static synchronized void setupSwingDefaults() {
        if (swingSetupDone) return;
        swingSetupDone = true;
        // OpenGL pipeline: significantly faster than the default software renderer at 4K.
        System.setProperty("sun.java2d.opengl", "true");
        // Smooth text.
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
    }

    /**
     * Install the L&amp;F. Must run on the EDT before any JFrame/JDialog is
     * constructed.
     *
     * <p><b>Strict ordering — do NOT reorder these phases:</b>
     * <ol>
     *   <li>{@code flatlaf.uiScale} system property is set BEFORE
     *       {@code FlatDarkLaf.setup()} so FlatLaf reads it during init.</li>
     *   <li>{@code FlatDarkLaf.setup()} installs the dark L&amp;F. Hard requirement —
     *       no fallback. If FlatLaf is missing from the classpath this throws
     *       {@code NoClassDefFoundError} and that's the point: silent fallback
     *       to Metal/Nimbus is what made earlier attempts unreadable.</li>
     *   <li>UIManager polish + font + minimum-component-size keys are applied
     *       AFTER {@code setup()} so they override defaults rather than getting
     *       wiped out by L&amp;F install.</li>
     *   <li>Diagnostic logging dumps everything we know — the user has burned
     *       three phases without visibility. They need numbers to share.</li>
     * </ol></p>
     *
     * <p>Callers must invoke {@link #forceNonHeadless()} BEFORE this method
     * because {@code FlatDarkLaf.setup()} touches AWT.</p>
     */
    public static void bootstrapLF() {
        // Phase 1: seed the scale BEFORE FlatLaf reads it.
        if (System.getProperty("flatlaf.uiScale") == null) {
            System.setProperty("flatlaf.uiScale", UiScale.detectInitialScale());
        }

        // Phase 1b: single accent color, seeded via global extra defaults BEFORE the L&F
        // installs so every accent-derived color (default button, focus ring, selection,
        // tab underline, checkbox/radio) is recomputed from it. The map is retained by
        // FlatLaf and re-applied on every subsequent theme install, so light/dark toggles
        // keep the accent for free. One source of truth lives in EditorOps so hand-drawn
        // touches (brand text) match exactly.
        FlatLaf.setGlobalExtraDefaults(java.util.Map.of("@accentColor", EditorOps.ACCENT_HEX));

        // Phase 2 + 3: install the persisted theme and apply our defaults on top.
        darkTheme = PREFS.getBoolean(THEME_PREF_KEY, true);
        installTheme(darkTheme);

        // Phase 4: diagnostic logging — gives the user concrete numbers if
        // the editor STILL renders too small after this fix.
        logBootstrapDiagnostics();
    }

    // -------------------- Light / dark theme --------------------

    private static final java.util.prefs.Preferences PREFS =
            java.util.prefs.Preferences.userNodeForPackage(SwingSchemaEditor.class);
    private static final String THEME_PREF_KEY = "darkTheme";
    private static boolean darkTheme = true;

    /** True when the current L&amp;F is the dark theme. */
    public static boolean isDarkTheme() {
        return darkTheme;
    }

    /**
     * Flip between the dark and light FlatLaf themes and restyle every open window live.
     * The accent (global extra default) and our UI defaults are re-applied by
     * {@link #installTheme(boolean)}; {@link FlatLaf#updateUI()} then repaints all frames.
     */
    public static void toggleTheme() {
        darkTheme = !darkTheme;
        PREFS.putBoolean(THEME_PREF_KEY, darkTheme);
        installTheme(darkTheme);
        FlatLaf.updateUI();
    }

    /** Install the requested theme, then (re-)apply our UI defaults on top of it. */
    private static void installTheme(boolean dark) {
        if (dark) FlatDarkLaf.setup(); else FlatLightLaf.setup();
        applyUiDefaults();
    }

    // -------------------- UI zoom --------------------

    private static final String FONT_PREF_KEY = "baseFontPt";
    private static final int DEFAULT_FONT_PT = 20;
    private static int fontPt = PREFS.getInt(FONT_PREF_KEY, DEFAULT_FONT_PT);

    /**
     * Zoom the whole UI by nudging the base font ({@code deltaPt} pt; 0 resets). FlatLaf
     * derives component metrics from the default font, so this rescales everything except
     * the hand-sized monospace editors. Persisted across sessions.
     */
    static void adjustZoom(int deltaPt) {
        fontPt = deltaPt == 0 ? DEFAULT_FONT_PT
                : Math.max(8, Math.min(40, fontPt + deltaPt)); // 40%..200% of the 20pt base
        PREFS.putInt(FONT_PREF_KEY, fontPt);
        UIManager.put("defaultFont", new FontUIResource(Font.SANS_SERIF, Font.PLAIN, fontPt));
        FlatLaf.updateUI();
    }

    static int zoomPercent() {
        return Math.round(fontPt * 100f / DEFAULT_FONT_PT);
    }

    /**
     * Fonts, minimum component sizes and visual polish layered on top of whichever FlatLaf
     * theme is installed. Must run after EVERY {@code setup()} — installing an L&amp;F resets
     * the UIManager defaults, so a live theme switch has to re-apply these.
     */
    private static void applyUiDefaults() {
        // Base font in logical pt (FlatLaf further scales by flatlaf.uiScale). Mutable:
        // this is the UI zoom — bumping it makes FlatLaf recompute every component metric.
        UIManager.put("defaultFont", new FontUIResource(Font.SANS_SERIF, Font.PLAIN, fontPt));

        // Minimum component heights — logical px, FlatLaf scales them.
        UIManager.put("Button.minimumHeight", 48);
        UIManager.put("TextComponent.minimumHeight", 44);
        UIManager.put("Spinner.minimumHeight", 44);
        UIManager.put("ComboBox.minimumHeight", 44);
        UIManager.put("Button.minimumWidth", 120);

        // Rounded corners + scroll bar polish.
        UIManager.put("Component.arc", 8);
        UIManager.put("Button.arc", 8);
        UIManager.put("TextComponent.arc", 6);
        UIManager.put("ProgressBar.arc", 999);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.trackArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
        UIManager.put("ScrollBar.width", 12);

        // Focus ring polish.
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.innerFocusWidth", 1);

        // Modern tabbed-pane styling: a slim accent underline marks the selected tab,
        // roomy tab height, and a subtle elevation on the selected/hovered tab so the
        // card-style editor tabs read like a real code editor's file tabs.
        UIManager.put("TabbedPane.tabSelectionHeight", 3);
        UIManager.put("TabbedPane.tabHeight", 38);
        UIManager.put("TabbedPane.showTabSeparators", Boolean.TRUE);
        UIManager.put("TabbedPane.tabSeparatorsFullHeight", Boolean.FALSE);
        UIManager.put("TabbedPane.selectedBackground", new ColorUIResource(EditorOps.surface(0.05f)));
        UIManager.put("TabbedPane.hoverColor", new ColorUIResource(EditorOps.surface(0.03f)));
    }

    private static void logBootstrapDiagnostics() {
        UiLog.get().info("[codec_ui] FlatLaf class: {} (dark={})",
                UIManager.getLookAndFeel().getClass().getName(), FlatLaf.isLafDark());
        UiLog.get().info("[codec_ui] flatlaf.uiScale (system prop): {}", System.getProperty("flatlaf.uiScale"));
        UiLog.get().info("[codec_ui] FlatLaf UIScale.getUserScaleFactor(): {}",
                com.formdev.flatlaf.util.UIScale.getUserScaleFactor());
        UiLog.get().info("[codec_ui] Toolkit.getScreenResolution(): {}",
                Toolkit.getDefaultToolkit().getScreenResolution());
        try {
            var mode = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDisplayMode();
            UiLog.get().info("[codec_ui] Display mode: {}x{} @ {}Hz",
                    mode.getWidth(), mode.getHeight(), mode.getRefreshRate());
        } catch (Throwable t) {
            UiLog.get().warn("[codec_ui] Could not read DisplayMode", t);
        }
        UiLog.get().info("[codec_ui] Detected initial scale: {}", UiScale.detectInitialScale());
        UiLog.get().info("[codec_ui] Default font: {}", UIManager.getFont("defaultFont"));
        UiLog.get().info("[codec_ui] GDK_SCALE env: {}, GDK_DPI_SCALE env: {}",
                System.getenv("GDK_SCALE"), System.getenv("GDK_DPI_SCALE"));
    }

    // NeoForge launches with java.awt.headless=true and GraphicsEnvironment caches the flag.
    // setAccessible on a java.desktop private field is blocked since JDK 16, so go through
    // sun.misc.Unsafe (jdk.unsupported is opened for reflection) to overwrite the cache directly.
    static void forceNonHeadless() {
        System.setProperty("java.awt.headless", "false");
        if (!GraphicsEnvironment.isHeadless()) return;
        try {
            Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object unsafe = unsafeField.get(null);
            Class<?> unsafeClass = unsafe.getClass();
            Field headlessField = GraphicsEnvironment.class.getDeclaredField("headless");
            Object base = unsafeClass.getMethod("staticFieldBase", Field.class).invoke(unsafe, headlessField);
            long offset = (long) unsafeClass.getMethod("staticFieldOffset", Field.class).invoke(unsafe, headlessField);
            unsafeClass.getMethod("putObject", Object.class, long.class, Object.class)
                    .invoke(unsafe, base, offset, Boolean.FALSE);
        } catch (Throwable t) {
            UiLog.get().warn("Could not disable AWT headless mode. Add JVM arg -Djava.awt.headless=false "
                    + "to your run config if the editor fails to open.", t);
        }
    }
}

package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.google.gson.JsonElement;
import net.mehvahdjukaar.polytone.Polytone;
import net.mehvahdjukaar.polytone.common.codec_ui.SchemaCodec;
import net.mehvahdjukaar.polytone.common.codec_ui.SchemaEditor;
import org.jetbrains.annotations.Nullable;

import javax.swing.UIManager;
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

        // Phase 2: install FlatLaf (dark). Hard requirement — let it throw if missing.
        FlatDarkLaf.setup();

        // Phase 3: bigger fonts + minimum component sizes + visual polish.
        // 20pt logical (was 18). FlatLaf further scales by flatlaf.uiScale.
        UIManager.put("defaultFont", new FontUIResource(Font.SANS_SERIF, Font.PLAIN, 20));

        // Minimum component heights — logical px, FlatLaf scales them.
        // Bumped proportionally with the font bump above.
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

        // Phase 4: diagnostic logging — gives the user concrete numbers if
        // the editor STILL renders too small after this fix.
        logBootstrapDiagnostics();
    }

    private static void logBootstrapDiagnostics() {
        Polytone.LOGGER.info("[codec_ui] FlatLaf class: {} (dark={})",
                UIManager.getLookAndFeel().getClass().getName(), FlatLaf.isLafDark());
        Polytone.LOGGER.info("[codec_ui] flatlaf.uiScale (system prop): {}", System.getProperty("flatlaf.uiScale"));
        Polytone.LOGGER.info("[codec_ui] FlatLaf UIScale.getUserScaleFactor(): {}",
                com.formdev.flatlaf.util.UIScale.getUserScaleFactor());
        Polytone.LOGGER.info("[codec_ui] Toolkit.getScreenResolution(): {}",
                Toolkit.getDefaultToolkit().getScreenResolution());
        try {
            var mode = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDisplayMode();
            Polytone.LOGGER.info("[codec_ui] Display mode: {}x{} @ {}Hz",
                    mode.getWidth(), mode.getHeight(), mode.getRefreshRate());
        } catch (Throwable t) {
            Polytone.LOGGER.warn("[codec_ui] Could not read DisplayMode", t);
        }
        Polytone.LOGGER.info("[codec_ui] Detected initial scale: {}", UiScale.detectInitialScale());
        Polytone.LOGGER.info("[codec_ui] Default font: {}", UIManager.getFont("defaultFont"));
        Polytone.LOGGER.info("[codec_ui] GDK_SCALE env: {}, GDK_DPI_SCALE env: {}",
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
            Polytone.LOGGER.warn("Could not disable AWT headless mode. Add JVM arg -Djava.awt.headless=false "
                    + "to your run config if the editor fails to open.", t);
        }
    }
}

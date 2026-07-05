package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.mehvahdjukaar.polytone.Polytone;
import net.mehvahdjukaar.polytone.common.codec_ui.SchemaCodec;
import net.mehvahdjukaar.polytone.common.codec_ui.SchemaEditor;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.plaf.FontUIResource;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.function.Consumer;

public final class SwingSchemaEditor implements SchemaEditor {

    // ---- Persistent editor frame. One JFrame reused across every open() call so
    // we never spawn multiple windows. Rebuilt content (root widget, buttons, error
    // label) is swapped in via setContentPane on each call.
    private static @Nullable JFrame sharedFrame;

    @Override
    public <A> void open(SchemaCodec<A> codec, @Nullable A initial, Consumer<A> onSave) {
        open(codec, "Schema Editor", initial, onSave);
    }

    /**
     * Overload that accepts a human-readable label used in the window title bar.
     * Lets the launcher show "Edit: <codec label>" without changing the SchemaEditor
     * interface.
     */
    public <A> void open(SchemaCodec<A> codec, String label, @Nullable A initial, Consumer<A> onSave) {
        open(codec, label, Side.CLIENT_RESOURCES, initial, onSave);
    }

    /** Full overload: {@code side} selects the registry view (see {@link Side}) used for
     *  encoding/validation ops — required for codecs touching datapack registries. */
    public <A> void open(SchemaCodec<A> codec, String label, Side side, @Nullable A initial, Consumer<A> onSave) {
        setupSwingDefaults();
        forceNonHeadless();
        SwingUtilities.invokeLater(() -> openOnEdt(codec, label, side, initial, onSave));
    }

    private static volatile boolean swingSetupDone = false;
    private static synchronized void setupSwingDefaults() {
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
     * constructed. Public so {@link net.mehvahdjukaar.polytone.common.codec_ui.example.ExamplesLauncher}
     * can share the exact same bootstrap sequence.
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
    private static void forceNonHeadless() {
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

    /**
     * Registry-aware JSON ops for the given side. Codecs over datapack registries
     * ({@code worldgen/biome}, dimension types, ...) fail with plain JsonOps
     * ("Can't access registry ..."); we bind the best available registry view:
     * <ol>
     *   <li>SERVER_DATA with an integrated server running → the server's registries;</li>
     *   <li>CLIENT_RESOURCES (or no server) in a world → the client-synced registries —
     *       this is what polytone's own resource-pack-side files see;</li>
     *   <li>no world at all (bare launcher) → {@code VanillaRegistries.createLookup()},
     *       the full vanilla worldgen content (built once, cached — it's expensive);</li>
     *   <li>if even that fails → plain JsonOps (registry codecs will show their error).</li>
     * </ol>
     */
    private static com.mojang.serialization.DynamicOps<JsonElement> buildOps(Side side) {
        net.minecraft.core.HolderLookup.Provider provider = null;
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null) {
                if (side == Side.SERVER_DATA && mc.getSingleplayerServer() != null) {
                    provider = mc.getSingleplayerServer().registryAccess();
                } else if (mc.level != null) {
                    provider = mc.level.registryAccess();
                } else if (mc.getConnection() != null) {
                    provider = mc.getConnection().registryAccess();
                }
            }
        } catch (Throwable ignored) {
            // Not in a client environment (bare launcher main) — fall through.
        }
        if (provider == null) provider = vanillaLookup();
        if (provider != null) {
            try {
                return provider.createSerializationContext(JsonOps.INSTANCE);
            } catch (Throwable t) {
                Polytone.LOGGER.warn("[codec_ui] could not create registry ops, falling back to plain JsonOps", t);
            }
        }
        return JsonOps.INSTANCE;
    }

    private static net.minecraft.core.HolderLookup.@Nullable Provider vanillaLookup;
    private static boolean vanillaLookupFailed;

    private static net.minecraft.core.HolderLookup.@Nullable Provider vanillaLookup() {
        if (vanillaLookup == null && !vanillaLookupFailed) {
            try {
                vanillaLookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
            } catch (Throwable t) {
                vanillaLookupFailed = true;
                Polytone.LOGGER.warn("[codec_ui] VanillaRegistries lookup unavailable", t);
            }
        }
        return vanillaLookup;
    }

    private <A> void openOnEdt(SchemaCodec<A> codec, String label, Side side, @Nullable A initial, Consumer<A> onSave) {
        // Single-window invariant: reuse the static sharedFrame across every open() call.
        // Only bootstrap the L&F once — re-running FlatLaf.setup() per click is wasteful and
        // can drift the UI defaults underneath an already-realized frame.
        JFrame frame = sharedFrame;
        boolean firstOpen = (frame == null);
        if (firstOpen) {
            bootstrapLF();
            frame = new JFrame();
            // Hide on close (X button) instead of dispose — we reuse this frame
            // for every subsequent open() so disposing it would force a costly rebuild.
            frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            sharedFrame = frame;
            Polytone.LOGGER.info("[codec_ui] Created shared editor frame (first open)");
        } else {
            Polytone.LOGGER.debug("[codec_ui] Reusing shared editor frame for '{}'", label);
        }
        final JFrame f = frame;
        f.setTitle("Edit: " + label);

        // Registry-aware ops for this codec's logical side; used by every encode/parse below.
        com.mojang.serialization.DynamicOps<JsonElement> ops = buildOps(side);

        SwingWidget rootWidget = SwingWidgetFactory.create(codec.schema());

        if (initial != null) {
            DataResult<JsonElement> encoded = codec.encodeStart(ops, initial);
            encoded.result().ifPresent(rootWidget::setJson);
        }

        // Outer padding + section spacing — large outer margin, medium between sections.
        JPanel content = new JPanel(new BorderLayout(0, UiScale.med()));
        content.setBorder(BorderFactory.createEmptyBorder(
                UiScale.large(), UiScale.large(), UiScale.large(), UiScale.large()));

        // ---- Header: bold title (no manual size: rely on L&F-scaled font) ----
        JLabel title = new JLabel(label);
        title.setFont(UiScale.deriveFont(title.getFont(), Font.BOLD, 4f));
        title.setBorder(BorderFactory.createEmptyBorder(0, 0, UiScale.small(), 0));
        content.add(title, BorderLayout.NORTH);

        // ---- Center: scrollable widget area ----
        // Wrap the root widget in a Scrollable host that ALWAYS tracks the viewport's width.
        // Without this, JScrollPane sizes the inner content to its preferred width — which
        // is typically narrower than the viewport — and the user has to widen the window
        // manually for fields to fill. Tracking the viewport width keeps text fields and
        // nested records stretched across the full available form width.
        JPanel scrollHost = new ScrollableFormHost(new BorderLayout());
        scrollHost.add(rootWidget.component(), BorderLayout.CENTER);

        JScrollPane scroll = new JScrollPane(scrollHost);
        scroll.setBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")));
        scroll.getVerticalScrollBar().setUnitIncrement(UiScale.px(16));
        scroll.getViewport().setOpaque(false);
        // Content should always wrap to the form width — never show a horizontal bar.
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

        // ---- Center split: form (left) | live JSON output preview (right) ----
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                scroll, buildJsonPreview(codec, rootWidget, ops));
        split.setResizeWeight(0.6);
        split.setContinuousLayout(true);
        split.setBorder(null);
        content.add(split, BorderLayout.CENTER);

        // ---- Footer: error label above right-aligned action row, separated from form ----
        JLabel errorLabel = new JLabel(" ");
        errorLabel.setForeground(errorColor());

        JButton open = new JButton("Open");
        JButton save = new JButton("Save");
        JButton cancel = new JButton("Cancel");
        save.putClientProperty("JButton.buttonType", "default");
        save.setToolTipText("Save (Ctrl+S)");
        cancel.setToolTipText("Cancel (Esc)");

        JPanel buttons = new JPanel(new BorderLayout());
        Box right = Box.createHorizontalBox();
        right.add(open);
        right.add(Box.createHorizontalStrut(UiScale.med()));
        right.add(cancel);
        right.add(Box.createHorizontalStrut(UiScale.med()));
        right.add(save);
        buttons.add(right, BorderLayout.EAST);
        // A subtle top border separates the action bar from the scrollable form.
        buttons.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(UiScale.med(), 0, 0, 0)));

        JPanel south = new JPanel(new BorderLayout(0, UiScale.small()));
        south.add(errorLabel, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);
        content.add(south, BorderLayout.SOUTH);

        cancel.addActionListener(e -> f.setVisible(false));

        open.addActionListener(e -> {
            errorLabel.setText(" ");
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Open Schema JSON");
            chooser.setFileFilter(new FileNameExtensionFilter("JSON", "json"));
            int result = chooser.showOpenDialog(f);
            if (result != JFileChooser.APPROVE_OPTION) return;
            File target = chooser.getSelectedFile();
            if (target == null) return;
            String text;
            try {
                text = Files.readString(target.toPath());
            } catch (Exception ex) {
                errorLabel.setText("Read error: " + ex.getMessage());
                return;
            }
            JsonElement parsedJson;
            try {
                parsedJson = JsonParser.parseString(text);
            } catch (Exception ex) {
                errorLabel.setText("JSON parse error: " + ex.getMessage());
                return;
            }
            DataResult<A> decoded = codec.parse(ops, parsedJson);
            if (decoded.error().isPresent()) {
                errorLabel.setText("Schema parse error: " + decoded.error().get().message());
                return;
            }
            A value = decoded.result().orElseThrow();
            DataResult<JsonElement> reEncoded = codec.encodeStart(ops, value);
            if (reEncoded.error().isPresent()) {
                errorLabel.setText("Re-encode error: " + reEncoded.error().get().message());
                return;
            }
            reEncoded.result().ifPresent(rootWidget::setJson);
        });

        Runnable doSave = () -> {
            errorLabel.setText(" ");
            DataResult<JsonElement> jsonResult = rootWidget.currentJson();
            if (jsonResult.error().isPresent()) {
                // Full message lives in the output panel's status line — don't duplicate it here.
                errorLabel.setText("Can't save: fix the error shown in the output panel.");
                return;
            }
            JsonElement json = jsonResult.result().orElseThrow();

            DataResult<A> parsed = codec.parse(ops, json);
            if (parsed.error().isPresent()) {
                errorLabel.setText("Can't save: fix the error shown in the output panel.");
                return;
            }
            A value = parsed.result().orElseThrow();

            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Save Schema JSON");
            chooser.setFileFilter(new FileNameExtensionFilter("JSON", "json"));
            chooser.setSelectedFile(new File("schema.json"));
            int result = chooser.showSaveDialog(f);
            if (result != JFileChooser.APPROVE_OPTION) {
                // Stay open
                return;
            }
            File target = chooser.getSelectedFile();
            if (target == null) return;
            if (!target.getName().toLowerCase().endsWith(".json")) {
                target = new File(target.getParentFile(), target.getName() + ".json");
            }

            String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(json);
            try {
                Files.writeString(target.toPath(), pretty);
            } catch (Exception ex) {
                errorLabel.setText("Write error: " + ex.getMessage());
                return;
            }

            try {
                onSave.accept(value);
            } catch (Exception ex) {
                errorLabel.setText("Callback error: " + ex.getMessage());
                return;
            }
            f.setVisible(false);
        };
        save.addActionListener(e -> doSave.run());

        // ---- Keyboard shortcuts: Esc → cancel/hide, Ctrl+S → save ----
        // Bound on the content pane so they fire from anywhere within the editor.
        var im = content.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        var am = content.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        am.put("cancel", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { f.setVisible(false); }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "save");
        am.put("save", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { doSave.run(); }
        });

        f.setContentPane(content);
        f.getRootPane().setDefaultButton(save);

        if (firstOpen) {
            // Wide baseline to fit the preview | form split: 1280x800 logical px (scaled by
            // UiScale), capped to 90% of the screen. Min width raised accordingly.
            f.pack();
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            int targetW = (int) Math.min(UiScale.px(1280), screen.getWidth() * 0.9);
            int targetH = (int) Math.min(UiScale.px(800), screen.getHeight() * 0.9);
            Dimension pref = f.getSize();
            int w = Math.max(pref.width, targetW);
            int h = Math.max(pref.height, targetH);
            f.setSize(Math.max(w, UiScale.px(900)), Math.max(h, UiScale.px(480)));
            f.setMinimumSize(new Dimension(UiScale.px(900), UiScale.px(480)));
            f.setLocationRelativeTo(null);

            // Returning focus to the launcher window when this hides is automatic —
            // Swing returns focus to the previously focused window. Nothing to wire.
        } else {
            // Reusing the frame — keep its existing size/position, just revalidate
            // the swapped content. This preserves the user's window placement.
            f.revalidate();
            f.repaint();
        }
        f.setVisible(true);
        f.toFront();
        f.requestFocus();
    }

    // One preview refresher at a time — the shared frame hosts a single editor page, so the
    // previous page's timer is stopped whenever a new page is built.
    private static @Nullable javax.swing.Timer previewTimer;

    /**
     * Read-only, syntax-highlighted live view of the JSON the form currently produces,
     * with a validity line on top (the JSON is re-parsed through the codec on every
     * refresh). Poll-based (400ms): widgets have no change-listener plumbing, and a
     * cheap dirty-check keeps idle refreshes free.
     */
    private static <A> JComponent buildJsonPreview(SchemaCodec<A> codec, SwingWidget rootWidget,
                                                   com.mojang.serialization.DynamicOps<JsonElement> ops) {
        RSyntaxTextArea area = new RSyntaxTextArea();
        area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
        area.setEditable(false);
        area.setHighlightCurrentLine(false);
        try (var in = Theme.class.getResourceAsStream(
                "/org/fife/ui/rsyntaxtextarea/themes/" + (FlatLaf.isLafDark() ? "dark.xml" : "default.xml"))) {
            if (in != null) Theme.load(in).apply(area);
        } catch (Throwable ignored) {
            // Theme is cosmetic only.
        }
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UiScale.px(15)));

        RTextScrollPane areaScroll = new RTextScrollPane(area);
        areaScroll.setLineNumbersEnabled(false);
        areaScroll.setBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")));

        // Wrapping, read-only status line — validation errors show IN FULL, never elided.
        javax.swing.JTextArea status = new javax.swing.JTextArea("Output");
        status.setEditable(false);
        status.setFocusable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setOpaque(false);
        status.setFont(UIManager.getFont("Label.font"));
        status.setBorder(BorderFactory.createEmptyBorder(0, 0, UiScale.small(), 0));
        Color okColor = UIManager.getColor("Label.foreground");

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        // Dirty-gate: validation (codec.parse) only runs when the produced JSON actually
        // changed. Parsing every poll tick re-decodes registry lookups and spams the log
        // (lenient codecs log their own failures).
        String[] lastValidated = {null};
        Runnable refresh = () -> {
            if (!area.isShowing()) return;
            String text = "";
            String buildError = null;
            JsonElement json = null;
            try {
                DataResult<JsonElement> res = rootWidget.currentJson();
                if (res.error().isPresent()) {
                    buildError = res.error().get().message();
                } else {
                    json = res.result().orElseThrow();
                    text = gson.toJson(json);
                }
            } catch (Throwable t) {
                buildError = String.valueOf(t);
            }
            String key = buildError != null ? "!" + buildError : text;
            if (key.equals(lastValidated[0])) return; // nothing changed — no re-parse, no repaint
            lastValidated[0] = key;

            boolean ok = false;
            String state;
            if (buildError != null) {
                state = "✗ " + buildError;
            } else {
                try {
                    DataResult<A> parsed = codec.parse(ops, json);
                    ok = parsed.error().isEmpty();
                    state = ok ? "✓ valid" : "✗ " + parsed.error().get().message();
                } catch (Throwable t) {
                    state = "✗ " + t;
                }
            }
            area.setText(text);
            area.setCaretPosition(0);
            status.setText(state);
            status.setForeground(ok ? okColor : errorColor());
        };

        if (previewTimer != null) previewTimer.stop();
        previewTimer = new javax.swing.Timer(400, e -> refresh.run());
        previewTimer.start();
        SwingUtilities.invokeLater(refresh);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(status, BorderLayout.NORTH);
        panel.add(areaScroll, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(UiScale.px(460), UiScale.px(400)));
        panel.setMinimumSize(new Dimension(UiScale.px(280), UiScale.px(200)));
        return panel;
    }

    /** Error/warning color that reads on both light and dark themes. */
    private static Color errorColor() {
        Color c = UIManager.getColor("Actions.Red");
        if (c != null) return c;
        // #FF6B6B reads on dark; #C0392B on light. Pick by L&F dark flag.
        return FlatLaf.isLafDark() ? new Color(0xFF6B6B) : new Color(0xC0392B);
    }

    /**
     * Scrollable JPanel whose preferred-viewport-width tracking is ON and whose
     * preferred-viewport-height tracking is OFF. This gives the form panel the
     * full viewport width (so text fields stretch) while still allowing vertical
     * scrolling when content is taller than the viewport.
     */
    private static final class ScrollableFormHost extends JPanel implements Scrollable {
        ScrollableFormHost(java.awt.LayoutManager lm) {
            super(lm);
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(java.awt.Rectangle vr, int o, int d) { return UiScale.px(16); }
        @Override public int getScrollableBlockIncrement(java.awt.Rectangle vr, int o, int d) {
            return o == javax.swing.SwingConstants.VERTICAL ? vr.height : vr.width;
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}

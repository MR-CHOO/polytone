package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import com.formdev.flatlaf.FlatLaf;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.mehvahdjukaar.polytone.common.codec_ui.SchemaCodec;
import net.mehvahdjukaar.polytone.common.codec_ui.SchemaEditor.Side;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * One codec editor: schema-driven form (left) | live validated JSON preview (right), with a
 * Load/Save action row. Hosted as a workbench tab; may be bound to a concrete file (saves go
 * straight there) or unbound (first save asks and then binds).
 *
 * <p>Dirty tracking rides on the preview refresh: the first stable JSON snapshot after a
 * (re)load becomes the baseline, and any later snapshot that differs marks the tab dirty.
 * This sidesteps per-widget change-listener plumbing, which the widgets don't have.</p>
 */
public final class EditorPanel<A> extends JPanel implements WorkbenchTab {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final SchemaCodec<A> codec;
    private final String label;
    private final DynamicOps<JsonElement> ops;
    private final SwingWidget rootWidget;
    private final JLabel errorLabel = new JLabel(" ");
    private final javax.swing.Timer previewTimer;
    private final JPanel kindHeader = new JPanel();
    /** Accent-outlined chip naming the content type being edited ("Fluid modifier"). */
    private final JLabel kindChip = new JLabel() {
        @Override public void updateUI() {
            super.updateUI();
            setForeground(EditorOps.accentColor());
            setFont(UiScale.labelFont(Font.BOLD, -1f));
            setBorder(new com.formdev.flatlaf.ui.FlatLineBorder(
                    new java.awt.Insets(2, 8, 2, 8),
                    EditorOps.mix(EditorOps.dividerColor(), EditorOps.accentColor(), 0.5f), 1f, 999));
        }
    };

    private @Nullable Path boundFile;
    private @Nullable Path defaultDir;
    private @Nullable Consumer<A> onSave;
    private @Nullable Consumer<Path> onSavedToFile;
    private @Nullable Runnable stateListener;

    /** JSON text considered "saved"; null = capture the next stable snapshot as baseline. */
    private @Nullable String baselineText;
    private @Nullable String lastRefreshKey;
    private boolean dirty;

    public EditorPanel(SchemaCodec<A> codec, String label, Side side) {
        super(new BorderLayout(0, UiScale.med()));
        this.codec = codec;
        this.label = label;
        this.ops = EditorOps.buildOps(side);
        this.rootWidget = SwingWidgetFactory.create(codec.schema());
        // A collapsible (raw JSON / expression) as the ENTIRE page starts expanded —
        // collapsing exists to keep big forms scannable, not to hide the only editor.
        if (rootWidget instanceof CollapsibleWidget collapsible) collapsible.setCollapsed(false);

        setBorder(BorderFactory.createEmptyBorder(
                UiScale.med(), UiScale.med(), UiScale.med(), UiScale.med()));

        // ---- Kind header: what am I editing? (hidden until the shell names it) ----
        kindHeader.setLayout(new BoxLayout(kindHeader, BoxLayout.X_AXIS));
        kindHeader.setOpaque(false);
        JLabel sideLabel = new JLabel(side == Side.SERVER_DATA
                ? "datapack side" : "resource pack side") {
            @Override public void updateUI() {
                super.updateUI();
                setFont(UiScale.labelFont(Font.PLAIN, -1f));
                setForeground(EditorOps.mutedColor());
            }
        };
        kindHeader.add(kindChip);
        kindHeader.add(Box.createHorizontalStrut(UiScale.med()));
        kindHeader.add(sideLabel);
        kindHeader.add(Box.createHorizontalGlue());
        kindHeader.setVisible(false);
        add(kindHeader, BorderLayout.NORTH);

        // ---- Center split: scrollable form (left) | live JSON preview (right) ----
        JPanel scrollHost = new ScrollableFormHost(new BorderLayout());
        scrollHost.add(rootWidget.component(), BorderLayout.CENTER);

        JScrollPane scroll = new JScrollPane(scrollHost);
        // No border of its own: the root widget's rounded card is the form's visual edge —
        // a second (square) box around it read as double-wrapping.
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(UiScale.px(16));
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll, buildJsonPreview());
        split.setResizeWeight(0.6);
        split.setContinuousLayout(true);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        // ---- Footer: error label above right-aligned action row ----
        errorLabel.setForeground(EditorOps.errorColor());

        JButton load = new JButton("Load JSON…", WorkbenchIcons.file());
        JButton save = new JButton("Save", WorkbenchIcons.save());
        save.putClientProperty("JButton.buttonType", "default");
        save.setToolTipText("Save (Ctrl+S)");
        load.setToolTipText("Load a JSON file into the form");

        JPanel buttons = new JPanel(new BorderLayout());
        Box right = Box.createHorizontalBox();
        right.add(load);
        right.add(Box.createHorizontalStrut(UiScale.med()));
        right.add(save);
        buttons.add(right, BorderLayout.EAST);
        buttons.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(UiScale.med(), 0, 0, 0)));

        JPanel south = new JPanel(new BorderLayout(0, UiScale.small()));
        south.add(errorLabel, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);

        save.addActionListener(e -> save());
        load.addActionListener(e -> loadFromChooser());

        var im = getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        var am = getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "save");
        am.put("save", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { save(); }
        });

        // Poll-based preview: widgets have no change-listener plumbing; the isShowing()
        // gate keeps background tabs free.
        previewTimer = new javax.swing.Timer(400, e -> refreshPreview());
        previewTimer.start();
        SwingUtilities.invokeLater(this::refreshPreview);
    }

    // -------------------- Wiring --------------------

    /** Save directly to this file from now on (no chooser). */
    public void bindFile(@Nullable Path file) {
        this.boundFile = file;
    }

    /** Where the save chooser starts for unbound editors (typically the workspace root). */
    public void setDefaultDir(@Nullable Path dir) {
        this.defaultDir = dir;
    }

    /** Domain callback invoked with the parsed value after every successful save. */
    public void setOnSave(@Nullable Consumer<A> onSave) {
        this.onSave = onSave;
    }

    /** Shell callback after every successful write (status line, tree refresh). */
    public void setOnSavedToFile(@Nullable Consumer<Path> onSavedToFile) {
        this.onSavedToFile = onSavedToFile;
    }

    /** Names the content type being edited ("Fluid modifier") — shown as a header chip. */
    public void setContentKind(@Nullable String kind) {
        if (kind == null || kind.isBlank()) {
            kindHeader.setVisible(false);
        } else {
            kindChip.setText(kind);
            kindHeader.setVisible(true);
        }
    }

    // -------------------- Content --------------------

    /**
     * Push JSON into the form and surface (without blocking the load) any codec complaints —
     * a file that fails validation still opens, showing the error. The next stable preview
     * snapshot becomes the clean baseline.
     */
    public void loadJson(JsonElement json) {
        rootWidget.setJson(json);
        baselineText = null;
        lastRefreshKey = null;
        DataResult<A> decoded = codec.parse(ops, json);
        errorLabel.setText(decoded.error()
                .map(e -> "Loaded with problems: " + e.message())
                .orElse(" "));
        SwingUtilities.invokeLater(this::refreshPreview);
    }

    /** Encode a runtime value into the form (used by the SchemaEditor API path). */
    public void loadValue(A value) {
        codec.encodeStart(ops, value).result().ifPresent(this::loadJson);
    }

    private void loadFromChooser() {
        errorLabel.setText(" ");
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Load JSON");
        chooser.setFileFilter(new FileNameExtensionFilter("JSON", "json", "mcmeta"));
        if (defaultDir != null) chooser.setCurrentDirectory(defaultDir.toFile());
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File target = chooser.getSelectedFile();
        if (target == null) return;
        try {
            loadJson(JsonParser.parseString(Files.readString(target.toPath())));
        } catch (Exception ex) {
            errorLabel.setText("Load error: " + ex.getMessage());
        }
    }

    // -------------------- WorkbenchTab --------------------

    @Override
    public JComponent component() {
        return this;
    }

    @Override
    public String title() {
        return label; // dirty state is the shell's amber tab dot, not a text prefix
    }

    @Override
    public @Nullable Path file() {
        return boundFile;
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public boolean save() {
        errorLabel.setText(" ");
        DataResult<JsonElement> jsonResult = rootWidget.currentJson();
        if (jsonResult.error().isPresent()) {
            errorLabel.setText("Can't save: fix the error shown in the output panel.");
            return false;
        }
        JsonElement json = jsonResult.result().orElseThrow();
        DataResult<A> parsed = codec.parse(ops, json);
        if (parsed.error().isPresent()) {
            errorLabel.setText("Can't save: " + parsed.error().get().message());
            return false;
        }

        Path target = boundFile;
        if (target == null) {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Save JSON");
            chooser.setFileFilter(new FileNameExtensionFilter("JSON", "json"));
            if (defaultDir != null) chooser.setCurrentDirectory(defaultDir.toFile());
            chooser.setSelectedFile(new File("untitled.json"));
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return false;
            File file = chooser.getSelectedFile();
            if (file == null) return false;
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".json") && !name.endsWith(".mcmeta")) {
                file = new File(file.getParentFile(), file.getName() + ".json");
            }
            target = file.toPath();
        }

        String pretty = GSON.toJson(json);
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(target, pretty);
        } catch (Exception ex) {
            errorLabel.setText("Write error: " + ex.getMessage());
            return false;
        }

        boundFile = target;
        baselineText = pretty;
        lastRefreshKey = null;
        setDirty(false);

        if (onSavedToFile != null) onSavedToFile.accept(target);
        if (onSave != null) {
            try {
                onSave.accept(parsed.result().orElseThrow());
            } catch (Exception ex) {
                errorLabel.setText("Callback error: " + ex.getMessage());
            }
        }
        return true;
    }

    @Override
    public void setStateListener(Runnable listener) {
        this.stateListener = listener;
    }

    @Override
    public void dispose() {
        previewTimer.stop();
        UIManager.removePropertyChangeListener(lafListener);
    }

    private void setDirty(boolean value) {
        if (dirty == value) return;
        dirty = value;
        if (stateListener != null) stateListener.run();
    }

    // -------------------- Live preview --------------------

    private RSyntaxTextArea previewArea;
    private Pill validityPill;
    private JTextArea errorBanner;
    private JPanel errorBannerHost;

    private JComponent buildJsonPreview() {
        previewArea = new RSyntaxTextArea();
        previewArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
        previewArea.setEditable(false);
        previewArea.setHighlightCurrentLine(false);
        applySyntaxTheme();
        // Keep the JSON highlighting in step with a live light/dark theme switch.
        UIManager.addPropertyChangeListener(lafListener);

        RTextScrollPane areaScroll = new RTextScrollPane(previewArea);
        areaScroll.setLineNumbersEnabled(true);
        areaScroll.setBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")));

        // ---- Header: title | validity pill | copy ----
        JLabel title = new JLabel("JSON Preview");
        title.setFont(UiScale.deriveFont(title.getFont(), Font.BOLD, 0f));

        validityPill = new Pill();

        JButton copy = new JButton(WorkbenchIcons.copy());
        copy.putClientProperty("JButton.buttonType", "toolBarButton");
        copy.setFocusable(false);
        copy.setToolTipText("Copy JSON to clipboard");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new java.awt.datatransfer.StringSelection(previewArea.getText()), null));

        Box header = Box.createHorizontalBox();
        header.add(title);
        header.add(Box.createHorizontalGlue());
        header.add(validityPill);
        header.add(Box.createHorizontalStrut(UiScale.small()));
        header.add(copy);

        // ---- Bottom banner: FULL validation error, tinted red, never elided ----
        errorBanner = new JTextArea();
        errorBanner.setEditable(false);
        errorBanner.setFocusable(false);
        errorBanner.setLineWrap(true);
        errorBanner.setWrapStyleWord(true);
        errorBanner.setOpaque(false);
        errorBanner.setFont(UiScale.deriveFont(UIManager.getFont("Label.font"), Font.PLAIN, -1f));
        errorBannerHost = new JPanel(new BorderLayout()) {
            @Override public void updateUI() {
                super.updateUI();
                setOpaque(true);
                setBackground(EditorOps.mix(EditorOps.surface(0f), EditorOps.errorColor(), 0.12f));
                setBorder(new com.formdev.flatlaf.ui.FlatLineBorder(
                        new java.awt.Insets(8, 10, 8, 10),
                        EditorOps.mix(EditorOps.surface(0f), EditorOps.errorColor(), 0.5f), 1f, 10));
                if (errorBanner != null) errorBanner.setForeground(EditorOps.errorColor());
            }
        };
        errorBannerHost.add(errorBanner, BorderLayout.CENTER);
        errorBannerHost.setVisible(false);

        JPanel panel = new JPanel(new BorderLayout(0, UiScale.small()));
        panel.add(header, BorderLayout.NORTH);
        panel.add(areaScroll, BorderLayout.CENTER);
        panel.add(errorBannerHost, BorderLayout.SOUTH);
        panel.setPreferredSize(new Dimension(UiScale.px(460), UiScale.px(400)));
        panel.setMinimumSize(new Dimension(UiScale.px(280), UiScale.px(200)));
        return panel;
    }

    /** Small filled rounded status chip ("Valid" / "Invalid"). */
    private static final class Pill extends JLabel {
        Pill() {
            setText(" ");
            setBorder(BorderFactory.createEmptyBorder(
                    UiScale.px(2), UiScale.px(8), UiScale.px(2), UiScale.px(8)));
        }

        void set(String text, Color fg, Color bg) {
            setText(text);
            setForeground(fg);
            setBackground(bg);
            setFont(UiScale.deriveFont(UIManager.getFont("Label.font"), Font.BOLD, -1f));
            repaint();
        }

        @Override protected void paintComponent(java.awt.Graphics g) {
            if (getBackground() != null && !getText().isBlank()) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /** (Re)apply the RSyntaxTextArea theme matching the current FlatLaf light/dark mode. */
    private void applySyntaxTheme() {
        if (previewArea == null) return;
        try (var in = Theme.class.getResourceAsStream(
                "/org/fife/ui/rsyntaxtextarea/themes/" + (FlatLaf.isLafDark() ? "dark.xml" : "default.xml"))) {
            if (in != null) Theme.load(in).apply(previewArea);
        } catch (Throwable ignored) {
            // Theme is cosmetic only.
        }
        previewArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UiScale.px(15)));
    }

    private final java.beans.PropertyChangeListener lafListener = e -> {
        if ("lookAndFeel".equals(e.getPropertyName())) {
            SwingUtilities.invokeLater(this::applySyntaxTheme);
        }
    };

    private void refreshPreview() {
        if (!isShowing()) return;
        String text = "";
        String buildError = null;
        JsonElement json = null;
        try {
            DataResult<JsonElement> res = rootWidget.currentJson();
            if (res.error().isPresent()) {
                buildError = res.error().get().message();
            } else {
                json = res.result().orElseThrow();
                text = GSON.toJson(json);
            }
        } catch (Throwable t) {
            buildError = String.valueOf(t);
        }
        // Dirty-gate: validation (codec.parse) only runs when the produced JSON actually
        // changed. Parsing every poll tick re-decodes registry lookups and spams the log.
        String key = buildError != null ? "!" + buildError : text;
        if (key.equals(lastRefreshKey)) return;
        lastRefreshKey = key;

        boolean ok = false;
        String problem = buildError;
        if (buildError == null) {
            try {
                DataResult<A> parsed = codec.parse(ops, json);
                ok = parsed.error().isEmpty();
                if (!ok) problem = parsed.error().get().message();
            } catch (Throwable t) {
                problem = String.valueOf(t);
            }
        }
        previewArea.setText(text);
        previewArea.setCaretPosition(0);
        Color surface = EditorOps.surface(0f);
        if (ok) {
            validityPill.set("Valid", EditorOps.successColor(),
                    EditorOps.mix(surface, EditorOps.successColor(), 0.18f));
            errorBannerHost.setVisible(false);
        } else {
            validityPill.set("Invalid", EditorOps.errorColor(),
                    EditorOps.mix(surface, EditorOps.errorColor(), 0.18f));
            errorBanner.setText(problem == null ? "unknown error" : problem);
            errorBannerHost.setVisible(true);
        }
        revalidate();

        if (buildError == null) {
            if (baselineText == null) {
                baselineText = text; // first stable snapshot after a (re)load = clean state
                setDirty(false);
            } else {
                setDirty(!text.equals(baselineText));
            }
        } else {
            setDirty(true);
        }
    }

    /**
     * Scrollable JPanel whose preferred-viewport-width tracking is ON and whose
     * preferred-viewport-height tracking is OFF: the form gets the full viewport width
     * (text fields stretch) while still scrolling vertically.
     */
    private static final class ScrollableFormHost extends JPanel implements Scrollable {
        ScrollableFormHost(java.awt.LayoutManager lm) {
            super(lm);
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle vr, int o, int d) { return UiScale.px(16); }
        @Override public int getScrollableBlockIncrement(Rectangle vr, int o, int d) {
            return o == SwingConstants.VERTICAL ? vr.height : vr.width;
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}

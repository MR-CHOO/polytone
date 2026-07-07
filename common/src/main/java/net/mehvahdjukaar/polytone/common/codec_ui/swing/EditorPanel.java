package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import com.formdev.flatlaf.FlatLaf;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.mehvahdjukaar.codecui.SchemaCodec;
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
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JToggleButton;
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

    /** How the center area is arranged — toggled by the header's Form / Split / JSON control. */
    private enum ViewMode { FORM, SPLIT, JSON }
    private ViewMode viewMode = ViewMode.SPLIT;
    private double splitRatio = 0.6;
    private JScrollPane formScroll;
    private JComponent previewPanel;
    private JSplitPane split;
    private final JPanel centerHost = new JPanel(new BorderLayout());
    private Box kindInfo;

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

        // ---- Header row: content-kind chip (left, when named) · view toggle (right, always) ----
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
        kindInfo = Box.createHorizontalBox();
        kindInfo.add(kindChip);
        kindInfo.add(Box.createHorizontalStrut(UiScale.med()));
        kindInfo.add(sideLabel);
        kindInfo.setVisible(false); // shown once the shell names the content kind
        kindHeader.add(kindInfo);
        kindHeader.add(Box.createHorizontalGlue());
        kindHeader.add(buildViewToggle());
        add(kindHeader, BorderLayout.NORTH);

        // ---- Center: form (left) | live JSON preview (right); arrangement per view mode ----
        JPanel scrollHost = new ScrollableFormHost(new BorderLayout());
        scrollHost.add(rootWidget.component(), BorderLayout.CENTER);

        formScroll = new JScrollPane(scrollHost);
        // No border of its own: the root widget's rounded card is the form's visual edge —
        // a second (square) box around it read as double-wrapping.
        formScroll.setBorder(BorderFactory.createEmptyBorder());
        formScroll.getVerticalScrollBar().setUnitIncrement(UiScale.px(16));
        formScroll.getViewport().setOpaque(false);
        formScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        formScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

        previewPanel = buildJsonPreview();

        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setResizeWeight(splitRatio);
        split.setContinuousLayout(true);
        split.setBorder(null);

        centerHost.setOpaque(false);
        add(centerHost, BorderLayout.CENTER);
        applyViewMode(); // seats the split (default mode) into centerHost

        // ---- Footer: ONE row — error text (left, empty when clean) | Load/Save (right).
        // A dedicated error line above the buttons sat there as a permanently blank strip
        // right on top of the status bar; inlining it removes the dead band.
        errorLabel.setForeground(EditorOps.errorColor());
        errorLabel.setIconTextGap(UiScale.small());

        JButton load = new JButton("Load JSON…", WorkbenchIcons.file());
        JButton save = new JButton("Save", WorkbenchIcons.save());
        save.putClientProperty("JButton.buttonType", "default");
        save.setToolTipText("Save (Ctrl+S)");
        load.setToolTipText("Load a JSON file into the form");

        JPanel footer = new JPanel(new BorderLayout(UiScale.med(), 0)) {
            @Override public void updateUI() {
                super.updateUI();
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(1, 0, 0, 0, EditorOps.dividerColor()),
                        BorderFactory.createEmptyBorder(UiScale.med(), 0, 0, 0)));
            }
        };
        Box right = Box.createHorizontalBox();
        right.add(load);
        right.add(Box.createHorizontalStrut(UiScale.med()));
        right.add(save);
        footer.add(errorLabel, BorderLayout.CENTER);
        footer.add(right, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);

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

    // -------------------- View mode (form / split / JSON) --------------------

    /** Segmented icon control, opposite the kind chip: form-only | split | JSON-only. */
    private JComponent buildViewToggle() {
        JToggleButton form = viewToggleButton(WorkbenchIcons.viewForm(),
                "Form only — hide the JSON preview", ViewMode.FORM);
        JToggleButton splitBtn = viewToggleButton(WorkbenchIcons.viewSplit(),
                "Split — form and JSON side by side", ViewMode.SPLIT);
        JToggleButton json = viewToggleButton(WorkbenchIcons.viewJson(),
                "JSON only — hide the form", ViewMode.JSON);
        ButtonGroup group = new ButtonGroup();
        group.add(form);
        group.add(splitBtn);
        group.add(json);
        Box box = Box.createHorizontalBox();
        box.add(form);
        box.add(splitBtn);
        box.add(json);
        splitBtn.setSelected(true); // fires the accent-tint listener too
        return box;
    }

    private JToggleButton viewToggleButton(javax.swing.Icon icon, String tooltip, ViewMode mode) {
        JToggleButton b = new JToggleButton(icon);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setFocusable(false);
        b.setToolTipText(tooltip);
        // Active mode's glyph goes accent-purple, matching the sidebar dock's highlight language.
        b.addItemListener(e -> b.setForeground(b.isSelected() ? EditorOps.accentColor() : null));
        b.addActionListener(e -> setViewMode(mode));
        return b;
    }

    private void setViewMode(ViewMode mode) {
        if (viewMode == mode) return;
        // Remember the divider so returning to Split restores the user's ratio.
        if (viewMode == ViewMode.SPLIT && split.getWidth() > 0) {
            splitRatio = split.getDividerLocation() / (double) Math.max(1, split.getWidth());
        }
        viewMode = mode;
        applyViewMode();
    }

    private void applyViewMode() {
        centerHost.removeAll();
        // Detach both from the split so they can be re-parented into centerHost directly.
        split.setLeftComponent(null);
        split.setRightComponent(null);
        switch (viewMode) {
            case FORM -> centerHost.add(formScroll, BorderLayout.CENTER);
            case JSON -> centerHost.add(previewPanel, BorderLayout.CENTER);
            case SPLIT -> {
                split.setLeftComponent(formScroll);
                split.setRightComponent(previewPanel);
                centerHost.add(split, BorderLayout.CENTER);
                SwingUtilities.invokeLater(() -> {
                    if (split.getWidth() > 0) split.setDividerLocation(splitRatio);
                });
            }
        }
        centerHost.revalidate();
        centerHost.repaint();
    }

    // -------------------- Wiring --------------------

    /** Footer error slot: red glyph + message, or a bare space keeping the row height. */
    private void footerError(@Nullable String message) {
        if (message == null || message.isBlank()) {
            errorLabel.setIcon(null);
            errorLabel.setText(" ");
        } else {
            errorLabel.setIcon(WorkbenchIcons.xTinted());
            errorLabel.setText(message);
        }
    }

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
        // The header row stays visible for the view toggle; only the chip+side info toggles.
        if (kind == null || kind.isBlank()) {
            kindInfo.setVisible(false);
        } else {
            kindChip.setText(kind);
            kindInfo.setVisible(true);
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
        footerError(decoded.error()
                .map(e -> "Loaded with problems: " + e.message())
                .orElse(null));
        SwingUtilities.invokeLater(this::refreshPreview);
    }

    /** Encode a runtime value into the form (used by the SchemaEditor API path). */
    public void loadValue(A value) {
        codec.encodeStart(ops, value).result().ifPresent(this::loadJson);
    }

    private void loadFromChooser() {
        footerError(null);
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
            footerError("Load error: " + ex.getMessage());
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
        footerError(null);
        DataResult<JsonElement> jsonResult = rootWidget.currentJson();
        if (jsonResult.error().isPresent()) {
            footerError("Can't save: fix the error shown in the output panel.");
            return false;
        }
        JsonElement json = jsonResult.result().orElseThrow();
        DataResult<A> parsed = codec.parse(ops, json);
        if (parsed.error().isPresent()) {
            footerError("Can't save: " + parsed.error().get().message());
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
            footerError("Write error: " + ex.getMessage());
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
                footerError("Callback error: " + ex.getMessage());
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
    private ValidityLabel validityPill;
    private JTextArea errorBanner;
    private JPanel errorBannerHost;

    private JComponent buildJsonPreview() {
        SyntaxPalettes.ensureBuiltinTokenMakers(); // make the bundled JSON lexer load in a modded classloader
        previewArea = new RSyntaxTextArea();
        previewArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
        previewArea.setEditable(false);
        previewArea.setHighlightCurrentLine(false);
        UiScale.installEditorZoom(previewArea); // Ctrl+wheel resize + live editor-font updates
        applySyntaxTheme();
        // Keep the JSON highlighting in step with a live light/dark theme switch.
        UIManager.addPropertyChangeListener(lafListener);

        RTextScrollPane areaScroll = new RTextScrollPane(previewArea);
        areaScroll.setLineNumbersEnabled(true);
        areaScroll.setBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")));

        // ---- Header: title | validity pill | copy ----
        JLabel title = new JLabel("JSON Preview");
        title.setFont(UiScale.deriveFont(title.getFont(), Font.BOLD, 0f));

        validityPill = new ValidityLabel();

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
                // Neutral raised surface (NOT a red-tinted one, which muddied the text and read
                // as "faded") + a full error-red outline; the red text then reads at full strength,
                // consistent with every other error indicator.
                setBackground(EditorOps.surface(0.03f));
                setBorder(new com.formdev.flatlaf.ui.FlatLineBorder(
                        new java.awt.Insets(8, 10, 8, 10), EditorOps.errorColor(), 1f, 10));
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

    /**
     * "Valid" / "Invalid" status marker — a tinted glyph + tinted text, matching the green
     * reload glyphs in the toolbar instead of a filled chip. Colors/icons are re-derived in
     * {@link #updateUI()} so a live light/dark theme switch (or zoom) restyles it even though
     * {@link #refreshPreview()} is dirty-gated and won't re-run on a theme change alone.
     */
    private static final class ValidityLabel extends JLabel {
        private Boolean ok; // null = no state yet

        ValidityLabel() {
            setText(" ");
            setIconTextGap(UiScale.small());
        }

        void set(boolean valid) {
            this.ok = valid;
            applyStyle();
        }

        private void applyStyle() {
            if (ok == null) {
                setIcon(null);
                setText(" ");
                return;
            }
            setIcon(ok ? WorkbenchIcons.checkTinted() : WorkbenchIcons.xTinted());
            setText(ok ? "Valid" : "Invalid");
            setForeground(ok ? EditorOps.successColor() : EditorOps.errorColor());
            setFont(UiScale.labelFont(Font.BOLD, -1f));
        }

        @Override public void updateUI() {
            super.updateUI();
            applyStyle();
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
        // Override the RSyntax theme's JSON token colors with our dedicated JSON palette (distinct
        // from the expression editor's), then re-assert the shared editor font + surface that
        // Theme.apply() just reset.
        SyntaxPalettes.applyJson(previewArea, FlatLaf.isLafDark());
        previewArea.setFont(UiScale.editorFont());
        Color bg = EditorOps.editorSurface();
        if (bg != null) previewArea.setBackground(bg);
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
        if (ok) {
            validityPill.set(true);
            errorBannerHost.setVisible(false);
        } else {
            validityPill.set(false);
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

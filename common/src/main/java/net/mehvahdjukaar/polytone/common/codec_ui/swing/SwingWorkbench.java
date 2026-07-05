package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import com.formdev.flatlaf.FlatLaf;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.mehvahdjukaar.polytone.common.codec_ui.SchemaCodec;
import net.mehvahdjukaar.polytone.common.codec_ui.SchemaEditor.Side;
import net.mehvahdjukaar.polytone.common.codec_ui.workbench.CodecEntry;
import net.mehvahdjukaar.polytone.common.codec_ui.workbench.GamePaths;
import net.mehvahdjukaar.polytone.common.codec_ui.workbench.PackReloader;
import net.mehvahdjukaar.polytone.common.codec_ui.workbench.PackWorkspace;
import net.mehvahdjukaar.polytone.common.codec_ui.workbench.Workbench;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/**
 * The single-window workbench shell: everything the tool shows lives in this one frame.
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────────────┐
 * │ [Open Pack…]  pack name            [Reload Resources] [Data] │
 * ├───────────────┬──────────────────────────────────────────────┤
 * │ Files │Codecs │  ┌ tab ┐┌ tab ┐┌ tab ┐                    ×  │
 * │  pack tree /  │  form | live JSON preview  (EditorPanel)     │
 * │  codec library│                                              │
 * ├───────────────┴──────────────────────────────────────────────┤
 * │ status message                        150% · /path/to/pack   │
 * └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * Renders the Swing-free {@link Workbench} model; all state that outlives this window
 * belongs there, so a future non-Swing backend replaces only this package.
 */
public final class SwingWorkbench {

    private static final String CARD_EMPTY = "empty";
    private static final String CARD_TABS = "tabs";
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp");
    private static final long TEXT_FALLBACK_MAX_BYTES = 512 * 1024;

    private static @Nullable SwingWorkbench instance;

    private final Workbench model;
    private final JFrame frame;
    private final JTabbedPane editorTabs = new JTabbedPane();
    private final CardLayout centerCards = new CardLayout();
    private final JPanel centerHost = new JPanel(centerCards);
    private final PackTreePanel treePanel;
    private final JLabel packLabel = new JLabel("No pack opened");
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel infoLabel = new JLabel();
    private final JButton newContentButton = new JButton("New Content…");
    private final JButton reloadResourcesButton = new JButton("Reload Resources");
    private final JButton reloadDataButton = new JButton("Reload Data");
    private final javax.swing.Timer reloadAvailabilityTimer;

    /** Dedup key (file path string / CodecEntry / standalone label) → hosted tab. */
    private final Map<Object, WorkbenchTab> tabsByKey = new LinkedHashMap<>();
    private final Map<Component, Object> keysByComponent = new HashMap<>();

    // -------------------- Entry points --------------------

    /** Open (or focus) the workbench window rendering the given model. */
    public static void open(Workbench model) {
        SwingUtilities.invokeLater(() -> ensureInstance(model).show());
    }

    /**
     * Open (or focus) the workbench and add one editor tab — the {@code SchemaEditor} API
     * path. If no workbench exists yet, one is created with an empty codec library.
     */
    public static <A> void openStandalone(SchemaCodec<A> codec, String label, Side side,
                                          @Nullable JsonElement initialJson, @Nullable Consumer<A> onSave) {
        SwingUtilities.invokeLater(() -> {
            SwingWorkbench wb = ensureInstance(new Workbench(List.of()));
            wb.show();
            wb.addCodecTab("standalone:" + label, codec, label, side, initialJson, null, onSave);
        });
    }

    private static SwingWorkbench ensureInstance(Workbench model) {
        if (instance != null && instance.frame.isDisplayable()) return instance;
        if (!(UIManager.getLookAndFeel() instanceof FlatLaf)) {
            SwingSchemaEditor.bootstrapLF();
        }
        instance = new SwingWorkbench(model);
        return instance;
    }

    private void show() {
        frame.setVisible(true);
        frame.toFront();
        frame.requestFocus();
    }

    // -------------------- Construction --------------------

    private SwingWorkbench(Workbench model) {
        this.model = model;

        frame = new JFrame("Polytone Workbench");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { requestClose(); }
        });

        treePanel = new PackTreePanel(this::openFile, this::openPackChooser);

        JPanel content = new JPanel(new BorderLayout());
        content.add(buildToolbar(), BorderLayout.NORTH);
        content.add(buildCenter(), BorderLayout.CENTER);
        content.add(buildStatusBar(), BorderLayout.SOUTH);
        frame.setContentPane(content);

        model.addListener(() -> SwingUtilities.invokeLater(this::onWorkspaceChanged));
        onWorkspaceChanged();

        // Game state (world joined, server started...) changes reload availability over time.
        reloadAvailabilityTimer = new javax.swing.Timer(1500, e -> updateReloadButtons());
        reloadAvailabilityTimer.start();
        updateReloadButtons();

        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int w = (int) Math.min(UiScale.px(1280), screen.getWidth() * 0.9);
        int h = (int) Math.min(UiScale.px(800), screen.getHeight() * 0.9);
        frame.setSize(Math.max(w, UiScale.px(900)), Math.max(h, UiScale.px(480)));
        frame.setMinimumSize(new Dimension(UiScale.px(900), UiScale.px(480)));
        frame.setLocationRelativeTo(null);
    }

    private JComponent buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(UiScale.small(), UiScale.med(), UiScale.small(), UiScale.med())));

        JButton openPack = new JButton("Open Pack…");
        openPack.setToolTipText("Open a resource pack / datapack folder — any folder works");
        openPack.addActionListener(e -> openPackChooser());
        bar.add(openPack);
        bar.add(Box.createHorizontalStrut(UiScale.small()));

        newContentButton.setToolTipText(
                "Add content to the pack — the file lands in its correct folder automatically");
        newContentButton.addActionListener(e -> openNewContentDialog());
        bar.add(newContentButton);
        bar.add(Box.createHorizontalStrut(UiScale.med()));

        packLabel.setForeground(EditorOps.mutedColor());
        bar.add(packLabel);

        bar.add(Box.createHorizontalGlue());

        reloadResourcesButton.setToolTipText("Reload the game's resource packs (F3+T) so saved files take effect");
        reloadResourcesButton.addActionListener(e -> triggerReload(Side.CLIENT_RESOURCES, reloadResourcesButton));
        reloadDataButton.setToolTipText("Reload the integrated server's datapacks (/reload)");
        reloadDataButton.addActionListener(e -> triggerReload(Side.SERVER_DATA, reloadDataButton));
        bar.add(reloadResourcesButton);
        bar.add(Box.createHorizontalStrut(UiScale.small()));
        bar.add(reloadDataButton);
        return bar;
    }

    private JComponent buildCenter() {
        JTabbedPane sidebar = new JTabbedPane();
        sidebar.addTab("Files", treePanel);
        sidebar.addTab("Codecs", buildCodecLibrary());
        sidebar.setPreferredSize(new Dimension(UiScale.px(300), UiScale.px(400)));
        sidebar.setMinimumSize(new Dimension(UiScale.px(220), UiScale.px(200)));

        editorTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        editorTabs.putClientProperty("JTabbedPane.tabClosable", true);
        editorTabs.putClientProperty("JTabbedPane.tabCloseToolTipText", "Close (Ctrl+W)");
        editorTabs.putClientProperty("JTabbedPane.tabCloseCallback",
                (java.util.function.BiConsumer<JTabbedPane, Integer>) (tp, index) -> closeTab(index));

        centerHost.add(buildEmptyState(), CARD_EMPTY);
        centerHost.add(editorTabs, CARD_TABS);
        centerCards.show(centerHost, CARD_EMPTY);

        // Ctrl+W closes the selected tab.
        var im = centerHost.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_W,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "closeTab");
        centerHost.getActionMap().put("closeTab", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                int index = editorTabs.getSelectedIndex();
                if (index >= 0) closeTab(index);
            }
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, centerHost);
        split.setResizeWeight(0);
        split.setContinuousLayout(true);
        split.setDividerLocation(UiScale.px(300));
        split.setBorder(null);
        return split;
    }

    private JComponent buildEmptyState() {
        JPanel empty = new JPanel();
        empty.setLayout(new BoxLayout(empty, BoxLayout.Y_AXIS));
        empty.add(Box.createVerticalGlue());
        JLabel title = new JLabel("No editors open");
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        title.setFont(UiScale.deriveFont(title.getFont(), Font.BOLD, 4f));
        title.setForeground(EditorOps.mutedColor());
        JLabel hint = new JLabel("Open a pack folder and double-click a file, or pick a codec from the library");
        hint.setAlignmentX(Component.CENTER_ALIGNMENT);
        hint.setForeground(EditorOps.mutedColor());
        empty.add(title);
        empty.add(Box.createVerticalStrut(UiScale.med()));
        empty.add(hint);
        empty.add(Box.createVerticalGlue());
        return empty;
    }

    private JComponent buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(UiScale.small(), UiScale.med(), UiScale.small(), UiScale.med())));
        statusLabel.setFont(UiScale.deriveFont(statusLabel.getFont(), Font.PLAIN, -1f));
        infoLabel.setFont(UiScale.deriveFont(infoLabel.getFont(), Font.PLAIN, -1f));
        infoLabel.setForeground(EditorOps.mutedColor());
        bar.add(statusLabel, BorderLayout.CENTER);
        bar.add(infoLabel, BorderLayout.EAST);
        return bar;
    }

    // -------------------- Codec library sidebar --------------------

    private JComponent buildCodecLibrary() {
        JPanel panel = new JPanel(new BorderLayout(0, UiScale.small()));
        panel.setBorder(BorderFactory.createEmptyBorder(
                UiScale.small(), UiScale.small(), UiScale.small(), UiScale.small()));

        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", "Search codecs...");
        search.putClientProperty("JTextField.showClearButton", Boolean.TRUE);

        JComboBox<String> sideFilter = new JComboBox<>(new String[]{"All", "Client", "Server"});

        JPanel north = new JPanel(new BorderLayout(UiScale.small(), 0));
        north.add(search, BorderLayout.CENTER);
        north.add(sideFilter, BorderLayout.EAST);
        panel.add(north, BorderLayout.NORTH);

        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));

        JScrollPane scroll = new JScrollPane(column);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(UiScale.px(16));
        scroll.getViewport().setOpaque(false);
        panel.add(scroll, BorderLayout.CENTER);

        Runnable rebuild = () -> {
            rebuildCodecColumn(column, search.getText(), (String) sideFilter.getSelectedItem());
            column.revalidate();
            column.repaint();
        };
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { rebuild.run(); }
            @Override public void removeUpdate(DocumentEvent e) { rebuild.run(); }
            @Override public void changedUpdate(DocumentEvent e) { rebuild.run(); }
        });
        sideFilter.addActionListener(e -> rebuild.run());
        rebuild.run();
        return panel;
    }

    private void rebuildCodecColumn(JPanel column, String query, @Nullable String sideChoice) {
        column.removeAll();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Side side = "Client".equals(sideChoice) ? Side.CLIENT_RESOURCES
                : "Server".equals(sideChoice) ? Side.SERVER_DATA : null;

        Map<String, List<CodecEntry>> groups = new LinkedHashMap<>();
        for (CodecEntry entry : model.entries()) {
            if (side != null && entry.side() != side) continue;
            if (!q.isEmpty() && !entry.label().toLowerCase(Locale.ROOT).contains(q)) continue;
            groups.computeIfAbsent(entry.group(), g -> new ArrayList<>()).add(entry);
        }

        boolean first = true;
        for (Map.Entry<String, List<CodecEntry>> group : groups.entrySet()) {
            if (!first) column.add(Box.createVerticalStrut(UiScale.large()));
            first = false;

            JLabel header = new JLabel(group.getKey().toUpperCase(Locale.ROOT));
            header.setAlignmentX(Component.LEFT_ALIGNMENT);
            header.setFont(UiScale.deriveFont(header.getFont(), Font.BOLD, -1f));
            header.setForeground(EditorOps.mutedColor());
            column.add(header);

            JSeparator sep = new JSeparator(SwingConstants.HORIZONTAL);
            sep.setAlignmentX(Component.LEFT_ALIGNMENT);
            sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiScale.px(1)));
            column.add(sep);
            column.add(Box.createVerticalStrut(UiScale.small()));

            for (CodecEntry entry : group.getValue()) {
                JButton button = new JButton(entry.label());
                button.setHorizontalAlignment(SwingConstants.LEFT);
                button.setAlignmentX(Component.LEFT_ALIGNMENT);
                int rowH = Math.max(button.getPreferredSize().height, UiScale.px(34));
                button.setMaximumSize(new Dimension(Integer.MAX_VALUE, rowH));
                if (entry.containerDir() != null) {
                    button.setToolTipText("Pack folder: " + entry.containerDir());
                }
                button.addActionListener(e -> openEntryTab(entry));
                column.add(button);
                column.add(Box.createVerticalStrut(UiScale.small()));
            }
        }
        column.add(Box.createVerticalGlue());
    }

    // -------------------- Pack handling --------------------

    private void openPackChooser() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Open Pack Folder (lenient — any folder works)");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        Preferences prefs = Preferences.userNodeForPackage(SwingWorkbench.class);
        // Start at the running game's resourcepacks folder when there is one; otherwise
        // wherever the user last opened a pack from.
        Path gamePacks = GamePaths.resourcePackDir();
        String last = prefs.get("lastPackDir", null);
        if (gamePacks != null) chooser.setCurrentDirectory(gamePacks.toFile());
        else if (last != null) chooser.setCurrentDirectory(new File(last));
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        File dir = chooser.getSelectedFile();
        if (dir == null) return;
        try {
            model.openWorkspace(dir.toPath());
            prefs.put("lastPackDir", dir.getAbsolutePath());
        } catch (Exception ex) {
            status("Could not open pack: " + ex.getMessage());
        }
    }

    private void onWorkspaceChanged() {
        PackWorkspace ws = model.workspace();
        treePanel.setWorkspace(ws);
        newContentButton.setEnabled(ws != null
                && model.entries().stream().anyMatch(e -> e.containerDir() != null));
        if (ws == null) {
            packLabel.setText("No pack opened");
            infoLabel.setText(UiScale.scaleAsPercent());
        } else {
            packLabel.setText(ws.name() + "  —  " + ws.kind().display());
            packLabel.setToolTipText(ws.root().toString());
            infoLabel.setText(UiScale.scaleAsPercent() + " · " + ws.root());
            status("Opened " + ws.kind().display().toLowerCase(Locale.ROOT) + ": " + ws.root());
        }
    }

    // -------------------- Reload hooks --------------------

    private void updateReloadButtons() {
        PackReloader reloader = PackReloader.get();
        boolean resources = reloader.available(Side.CLIENT_RESOURCES);
        boolean data = reloader.available(Side.SERVER_DATA);
        reloadResourcesButton.setEnabled(resources);
        reloadDataButton.setEnabled(data);
        if (!resources) reloadResourcesButton.setToolTipText("No running game to reload");
        if (!data) reloadDataButton.setToolTipText("No integrated server running");
    }

    private void triggerReload(Side side, JButton button) {
        button.setEnabled(false);
        status(side == Side.CLIENT_RESOURCES ? "Reloading resource packs…" : "Reloading datapacks…");
        PackReloader.get().reload(side, error -> SwingUtilities.invokeLater(() -> {
            updateReloadButtons();
            status(error == null ? "Reload complete — new files are now referenceable"
                    : "Reload failed: " + error);
        }));
    }

    // -------------------- New content flow --------------------

    private void openNewContentDialog() {
        PackWorkspace ws = model.workspace();
        if (ws == null) return;
        NewContentDialog.Result created = NewContentDialog.show(frame, model, ws);
        if (created == null) return;

        String key = created.file().toString();
        if (focusExisting(key)) return;
        @SuppressWarnings("unchecked")
        SchemaCodec<Object> codec = (SchemaCodec<Object>) created.entry().codec();
        EditorPanel<Object> panel;
        try {
            panel = new EditorPanel<>(codec, String.valueOf(created.file().getFileName()),
                    created.entry().side());
        } catch (Throwable t) {
            UiLog.get().error("[codec_ui] failed to build editor for {}", created.entry().label(), t);
            status("Could not build editor for " + created.entry().label() + ": " + t);
            return;
        }
        // Bound but not yet on disk: the file (and its namespace/container folders) are
        // created on first save, so cancelled tabs leave no debris in the pack.
        panel.bindFile(created.file());
        panel.setDefaultDir(created.file().getParent());
        panel.setOnSavedToFile(saved -> {
            status("Saved " + ws.relativize(saved));
            treePanel.refresh();
        });
        addTab(key, panel, created.entry().label());
        status("New " + created.entry().label().toLowerCase(Locale.ROOT) + " → "
                + ws.relativize(created.file()) + "  (created on first save)");
    }

    // -------------------- Tabs --------------------

    private void openEntryTab(CodecEntry entry) {
        PackWorkspace ws = model.workspace();
        addCodecTab(entry, entry.codec(), entry.label(), entry.side(), null,
                ws != null ? ws.root() : null, null);
    }

    @SuppressWarnings("unchecked")
    private <A> void addCodecTab(Object key, SchemaCodec<?> codec, String label, Side side,
                                 @Nullable JsonElement initialJson, @Nullable Path defaultDir,
                                 @Nullable Consumer<A> onSave) {
        if (focusExisting(key)) return;
        EditorPanel<A> panel;
        try {
            panel = new EditorPanel<>((SchemaCodec<A>) codec, label, side);
        } catch (Throwable t) {
            UiLog.get().error("[codec_ui] failed to build editor for {}", label, t);
            status("Could not build editor for " + label + ": " + t);
            return;
        }
        panel.setDefaultDir(defaultDir);
        if (onSave != null) panel.setOnSave(onSave);
        panel.setOnSavedToFile(file -> {
            PackWorkspace ws = model.workspace();
            status("Saved " + (ws != null ? ws.relativize(file) : file.toString()));
            treePanel.refresh();
        });
        if (initialJson != null) panel.loadJson(initialJson);
        addTab(key, panel, label);
    }

    /** Route a pack file to the right kind of tab. */
    private void openFile(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        if (Files.isDirectory(abs)) return;
        String key = abs.toString();
        if (focusExisting(key)) return;

        String name = String.valueOf(abs.getFileName()).toLowerCase(Locale.ROOT);
        String ext = name.lastIndexOf('.') < 0 ? "" : name.substring(name.lastIndexOf('.') + 1);

        if (ext.equals("json") || ext.equals("mcmeta")) {
            CodecEntry entry = model.entryFor(abs);
            if (entry != null && openCodecFile(key, entry, abs)) return;
            // No association (or unreadable as JSON) → plain text editor.
        }
        if (IMAGE_EXTENSIONS.contains(ext)) {
            openImageTab(key, abs);
            return;
        }
        try {
            if (Files.size(abs) > TEXT_FALLBACK_MAX_BYTES) {
                status("File too large for the text editor: " + abs.getFileName());
                return;
            }
            addTab(key, new TextEditorPanel(abs), null);
        } catch (Exception ex) {
            status("Could not open " + abs.getFileName() + ": " + ex.getMessage());
        }
    }

    /** Codec-backed file tab; false = could not even read/parse the file as JSON. */
    private boolean openCodecFile(Object key, CodecEntry entry, Path file) {
        JsonElement json;
        try {
            json = JsonParser.parseString(Files.readString(file));
        } catch (Exception ex) {
            status("Not parseable as JSON (" + ex.getMessage() + ") — opening as text");
            return false;
        }
        @SuppressWarnings("unchecked")
        SchemaCodec<Object> codec = (SchemaCodec<Object>) entry.codec();
        EditorPanel<Object> panel;
        try {
            panel = new EditorPanel<>(codec, String.valueOf(file.getFileName()), entry.side());
        } catch (Throwable t) {
            UiLog.get().error("[codec_ui] failed to build editor for {}", file, t);
            status("Could not build editor (" + t + ") — opening as text");
            return false;
        }
        panel.bindFile(file);
        panel.setDefaultDir(file.getParent());
        panel.setOnSavedToFile(saved -> {
            PackWorkspace ws = model.workspace();
            status("Saved " + (ws != null ? ws.relativize(saved) : saved.toString()));
        });
        panel.loadJson(json);
        addTab(key, panel, entry.label());
        return true;
    }

    private void openImageTab(Object key, Path file) {
        BufferedImage image;
        try {
            image = ImageIO.read(file.toFile());
        } catch (Exception ex) {
            status("Could not read image: " + ex.getMessage());
            return;
        }
        if (image == null) {
            status("Unsupported image format: " + file.getFileName());
            return;
        }
        // Nearest-neighbor upscale for the typical tiny MC texture, so it's actually visible.
        int scale = Math.max(1, UiScale.px(128) / Math.max(image.getWidth(), image.getHeight()));
        Image shown = scale > 1
                ? image.getScaledInstance(image.getWidth() * scale, image.getHeight() * scale, Image.SCALE_FAST)
                : image;

        JPanel panel = new JPanel(new BorderLayout());
        JLabel picture = new JLabel(new ImageIcon(shown));
        picture.setHorizontalAlignment(SwingConstants.CENTER);
        panel.add(new JScrollPane(picture), BorderLayout.CENTER);
        JLabel caption = new JLabel(file.getFileName() + "  —  " + image.getWidth() + "×" + image.getHeight()
                + (scale > 1 ? "  (shown " + scale + "×)" : ""));
        caption.setHorizontalAlignment(SwingConstants.CENTER);
        caption.setForeground(EditorOps.mutedColor());
        caption.setBorder(BorderFactory.createEmptyBorder(UiScale.small(), 0, UiScale.small(), 0));
        panel.add(caption, BorderLayout.SOUTH);

        String title = String.valueOf(file.getFileName());
        addTab(key, new WorkbenchTab() {
            @Override public JComponent component() { return panel; }
            @Override public String title() { return title; }
            @Override public Path file() { return file; }
            @Override public boolean isDirty() { return false; }
            @Override public boolean save() { return true; }
            @Override public void setStateListener(Runnable listener) {}
            @Override public void dispose() {}
        }, null);
    }

    private boolean focusExisting(Object key) {
        WorkbenchTab existing = tabsByKey.get(key);
        if (existing == null) return false;
        editorTabs.setSelectedComponent(existing.component());
        return true;
    }

    private void addTab(Object key, WorkbenchTab tab, @Nullable String tooltipPrefix) {
        JComponent comp = tab.component();
        tabsByKey.put(key, tab);
        keysByComponent.put(comp, key);
        tab.setStateListener(() -> SwingUtilities.invokeLater(() -> {
            int index = editorTabs.indexOfComponent(comp);
            if (index >= 0) editorTabs.setTitleAt(index, tab.title());
        }));
        editorTabs.addTab(tab.title(), comp);
        int index = editorTabs.indexOfComponent(comp);
        Path file = tab.file();
        String tooltip = file != null ? file.toString() : tooltipPrefix;
        if (tooltip != null) editorTabs.setToolTipTextAt(index, tooltip);
        editorTabs.setSelectedIndex(index);
        centerCards.show(centerHost, CARD_TABS);
    }

    private void closeTab(int index) {
        Component comp = editorTabs.getComponentAt(index);
        Object key = keysByComponent.get(comp);
        WorkbenchTab tab = key != null ? tabsByKey.get(key) : null;
        if (tab != null && tab.isDirty()) {
            int choice = JOptionPane.showConfirmDialog(frame,
                    "Save changes to \"" + tab.title().replace("• ", "") + "\"?",
                    "Unsaved changes", JOptionPane.YES_NO_CANCEL_OPTION);
            if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) return;
            if (choice == JOptionPane.YES_OPTION && !tab.save()) return;
        }
        if (tab != null) tab.dispose();
        if (key != null) tabsByKey.remove(key);
        keysByComponent.remove(comp);
        editorTabs.removeTabAt(index);
        if (editorTabs.getTabCount() == 0) centerCards.show(centerHost, CARD_EMPTY);
    }

    // -------------------- Shutdown --------------------

    private void requestClose() {
        long dirtyCount = tabsByKey.values().stream().filter(WorkbenchTab::isDirty).count();
        if (dirtyCount > 0) {
            int choice = JOptionPane.showConfirmDialog(frame,
                    dirtyCount + (dirtyCount == 1 ? " tab has" : " tabs have")
                            + " unsaved changes. Discard and close?",
                    "Unsaved changes", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) return;
        }
        reloadAvailabilityTimer.stop();
        tabsByKey.values().forEach(WorkbenchTab::dispose);
        tabsByKey.clear();
        keysByComponent.clear();
        frame.dispose();
        if (instance == this) instance = null;
    }

    private void status(String message) {
        statusLabel.setText(message);
        UiLog.get().info("[codec_ui] {}", message);
    }
}

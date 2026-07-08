package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import com.formdev.flatlaf.FlatLaf;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.mehvahdjukaar.codecui.SchemaCodec;
import net.mehvahdjukaar.polytone.common.codec_ui.SchemaEditor.Side;
import net.mehvahdjukaar.polytone.common.codec_ui.workbench.CodecEntry;
import net.mehvahdjukaar.polytone.common.codec_ui.workbench.GamePaths;
import net.mehvahdjukaar.polytone.common.codec_ui.workbench.PackWorkspace;
import net.mehvahdjukaar.polytone.common.codec_ui.workbench.Workbench;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
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

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp");
    private static final long TEXT_FALLBACK_MAX_BYTES = 512 * 1024;

    private static @Nullable SwingWorkbench instance;

    private final Workbench model;
    private final JFrame frame;
    private final WorkbenchTabs tabs;
    private final PackTreePanel treePanel;
    /** Divider location remembered while the sidebar is collapsed, so expanding restores it. */
    private int lastSidebarDivider = -1;
    /** Which activity-rail tool ("files" / "codecs") is currently shown. */
    private String activeSidebarCard = "files";
    private final JLabel statusLabel = StyledLabels.small(" ");
    private final JLabel pathLabel = StyledLabels.mutedSmall("");
    /** Live zoom readout in the status bar; clicking it resets to 100%. */
    private final JButton zoomResetButton = new JButton();
    private final WorkbenchToolbar toolbar;

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
            wb.addCodecTab("standalone:" + label, codec, label, side, null, initialJson, null, onSave);
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

        tabs = new WorkbenchTabs(frame, this::openPackChooser);
        treePanel = new PackTreePanel(this::openFile, this::openPackChooser, model::entryForContainer);
        toolbar = new WorkbenchToolbar(this::openPackChooser, this::openNewContentDialog,
                new WorkbenchToolbar.StatusSink() {
                    @Override public void info(String m) { status(m); }
                    @Override public void success(String m) { statusSuccess(m); }
                    @Override public void error(String m) { statusError(m); }
                });

        JPanel content = new JPanel(new BorderLayout());
        content.add(toolbar.component(), BorderLayout.NORTH);
        content.add(buildCenter(), BorderLayout.CENTER);
        content.add(buildStatusBar(), BorderLayout.SOUTH);
        frame.setContentPane(content);

        model.addListener(() -> SwingUtilities.invokeLater(this::onWorkspaceChanged));
        onWorkspaceChanged();

        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int w = (int) Math.min(UiScale.px(1280), screen.getWidth() * 0.9);
        int h = (int) Math.min(UiScale.px(800), screen.getHeight() * 0.9);
        frame.setSize(Math.max(w, UiScale.px(900)), Math.max(h, UiScale.px(480)));
        frame.setMinimumSize(new Dimension(UiScale.px(900), UiScale.px(480)));
        frame.setLocationRelativeTo(null);
    }

    private JButton zoomButton(javax.swing.Icon icon, String tooltip, int deltaPt) {
        return Buttons.toolbar(icon, tooltip, e -> adjustZoom(deltaPt));
    }

    private void adjustZoom(int deltaPt) {
        SwingSchemaEditor.adjustZoom(deltaPt);
        zoomResetButton.setText(SwingSchemaEditor.zoomPercent() + "%");
    }

    private JComponent buildCenter() {
        // The tab strip (+ empty-state card) and Ctrl+W live in WorkbenchTabs; this is its host.
        JComponent centerHost = tabs.component();

        // Ctrl+= / Ctrl+- / Ctrl+0: UI zoom (base-font scaling).
        var im = centerHost.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_EQUALS, menuMask), "zoomIn");
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_PLUS, menuMask), "zoomIn");
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_MINUS, menuMask), "zoomOut");
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_0, menuMask), "zoomReset");
        centerHost.getActionMap().put("zoomIn", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { adjustZoom(+2); }
        });
        centerHost.getActionMap().put("zoomOut", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { adjustZoom(-2); }
        });
        centerHost.getActionMap().put("zoomReset", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { adjustZoom(0); }
        });

        // ---- Sidebar body: a CardLayout the activity rail swaps between. No top tabs — the rail
        // (VSCode-style) both names the tools and does the switching. ----
        CardLayout sidebarCards = new CardLayout();
        JPanel sidebarBody = new JPanel(sidebarCards);
        sidebarBody.add(treePanel, "files");
        sidebarBody.add(new CodecLibraryPanel(model, this::openEntryTab), "codecs");

        // Hairline seam so the sidebar reads as a distinct panel from the editor area.
        JPanel sidebarHost = new JPanel(new BorderLayout()) {
            @Override public void updateUI() {
                super.updateUI();
                setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, EditorOps.dividerColor()));
            }
        };
        sidebarHost.add(sidebarBody, BorderLayout.CENTER);

        // ---- Activity rail: vertical icon tools. Click shows a tool's panel (accent-highlighted);
        // click the ACTIVE tool to collapse the sidebar and give the editor the whole width.
        // BorderLayout ignores the hidden CENTER child, so the rail alone sets the collapsed
        // left width — no JSplitPane min-size wrangling needed. ----
        ActivityButton filesBtn = new ActivityButton(WorkbenchIcons.folder(), "Files");
        ActivityButton codecsBtn = new ActivityButton(WorkbenchIcons.layers(), "Codecs");
        JPanel rail = new JPanel() {
            @Override public void updateUI() {
                super.updateUI();
                setOpaque(true);
                setBackground(EditorOps.railBg());
                setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, EditorOps.dividerColor()));
            }
        };
        rail.setLayout(new BoxLayout(rail, BoxLayout.Y_AXIS));
        filesBtn.setAlignmentX(0.5f);
        codecsBtn.setAlignmentX(0.5f);
        rail.add(Box.createVerticalStrut(UiScale.small()));
        rail.add(filesBtn);
        rail.add(codecsBtn);
        rail.add(Box.createVerticalGlue());

        JPanel leftWrap = new JPanel(new BorderLayout());
        leftWrap.add(rail, BorderLayout.WEST);
        leftWrap.add(sidebarHost, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftWrap, centerHost);
        split.setResizeWeight(0);
        split.setContinuousLayout(true);
        split.setDividerLocation(UiScale.px(280));
        split.setBorder(null);

        Runnable expand = () -> {
            if (!sidebarHost.isVisible()) {
                sidebarHost.setVisible(true);
                int w = lastSidebarDivider > UiScale.px(60) ? lastSidebarDivider : UiScale.px(280);
                split.setDividerLocation(w);
                split.revalidate();
            }
        };
        java.util.function.Consumer<String> selectTool = card -> {
            if (sidebarHost.isVisible() && card.equals(activeSidebarCard)) {
                // Re-clicking the active tool collapses the sidebar.
                lastSidebarDivider = split.getDividerLocation();
                sidebarHost.setVisible(false);
                split.setDividerLocation(rail.getPreferredSize().width);
                split.revalidate();
                filesBtn.setActive(false);
                codecsBtn.setActive(false);
            } else {
                activeSidebarCard = card;
                sidebarCards.show(sidebarBody, card);
                filesBtn.setActive(card.equals("files"));
                codecsBtn.setActive(card.equals("codecs"));
                expand.run();
            }
        };
        filesBtn.addActionListener(e -> selectTool.accept("files"));
        codecsBtn.addActionListener(e -> selectTool.accept("codecs"));

        // Initial state: Files tool active, sidebar shown.
        sidebarCards.show(sidebarBody, "files");
        activeSidebarCard = "files";
        filesBtn.setActive(true);

        return split;
    }

    /**
     * VSCode-style activity-bar button: an icon that highlights with the accent when its tool is
     * active (accent left-bar + accent-tinted icon — {@code WorkbenchIcons} tint to the foreground).
     */
    private static final class ActivityButton extends JButton {
        private boolean active;

        ActivityButton(javax.swing.Icon icon, String tip) {
            super(icon);
            Buttons.asToolbar(this);
            setToolTipText(tip);
            setBorder(BorderFactory.createEmptyBorder(UiScale.med(), UiScale.med(), UiScale.med(), UiScale.med()));
        }

        void setActive(boolean a) {
            active = a;
            setForeground(a ? EditorOps.accentColor() : null); // null → inherit default; icon follows
            repaint();
        }

        @Override public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            if (active) {
                g.setColor(EditorOps.accentColor());
                g.fillRect(0, UiScale.px(6), UiScale.px(3), getHeight() - UiScale.px(12));
            }
        }
    }

    private JComponent buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout()) {
            @Override public void updateUI() {
                super.updateUI();
                setOpaque(true);
                setBackground(EditorOps.surface(0.03f));
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(1, 0, 0, 0, EditorOps.dividerColor()),
                        BorderFactory.createEmptyBorder(UiScale.small(), UiScale.med(), UiScale.small(), UiScale.med())));
            }
        };
        bar.add(statusLabel, BorderLayout.CENTER);

        // Right side: pack path readout | – 100% + zoom cluster (the percent resets).
        Buttons.asToolbar(zoomResetButton);
        zoomResetButton.setToolTipText("Reset zoom to 100% (Ctrl+0)");
        zoomResetButton.setText(SwingSchemaEditor.zoomPercent() + "%");
        zoomResetButton.addActionListener(e -> adjustZoom(0));

        Box east = Box.createHorizontalBox();
        east.add(pathLabel);
        east.add(Box.createHorizontalStrut(UiScale.med()));
        east.add(WorkbenchToolbar.separator());
        east.add(Box.createHorizontalStrut(UiScale.small()));
        east.add(zoomButton(WorkbenchIcons.zoomOut(), "Zoom out (Ctrl+-)", -2));
        east.add(zoomResetButton);
        east.add(zoomButton(WorkbenchIcons.zoomIn(), "Zoom in (Ctrl+=)", +2));
        bar.add(east, BorderLayout.EAST);
        return bar;
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
            statusError("Could not open pack: " + ex.getMessage());
        }
    }

    private void onWorkspaceChanged() {
        PackWorkspace ws = model.workspace();
        treePanel.setWorkspace(ws);
        toolbar.setNewContentEnabled(ws != null
                && model.entries().stream().anyMatch(e -> e.containerDir() != null));
        if (ws == null) {
            toolbar.setPack(null, null, null);
            pathLabel.setText("");
        } else {
            toolbar.setPack(ws.name(), ws.kind().display(), ws.root().toString());
            pathLabel.setText(ws.root().toString());
            status("Opened " + ws.kind().display().toLowerCase(Locale.ROOT) + ": " + ws.root());
        }
    }

    // -------------------- New content flow --------------------

    private void openNewContentDialog() {
        PackWorkspace ws = model.workspace();
        if (ws == null) return;
        NewContentDialog.Result created = NewContentDialog.show(frame, model, ws);
        if (created == null) return;

        String key = created.file().toString();
        if (tabs.focus(key)) return;
        @SuppressWarnings("unchecked")
        SchemaCodec<Object> codec = (SchemaCodec<Object>) created.entry().codec();
        EditorPanel<Object> panel;
        try {
            panel = new EditorPanel<>(codec, String.valueOf(created.file().getFileName()),
                    created.entry().side());
        } catch (Throwable t) {
            UiLog.get().error("[codec_ui] failed to build editor for {}", created.entry().label(), t);
            statusError("Could not build editor for " + created.entry().label() + ": " + t);
            return;
        }
        // Bound but not yet on disk: the file (and its namespace/container folders) are
        // created on first save, so cancelled tabs leave no debris in the pack.
        panel.setContentKind(created.entry().label());
        panel.bindFile(created.file());
        panel.setDefaultDir(created.file().getParent());
        panel.setOnSavedToFile(saved -> {
            statusSuccess("Saved " + ws.relativize(saved));
            treePanel.refresh();
        });
        tabs.add(key,panel, created.entry().label());
        status("New " + created.entry().label().toLowerCase(Locale.ROOT) + " → "
                + ws.relativize(created.file()) + "  (created on first save)");
    }

    // -------------------- Tabs --------------------

    private void openEntryTab(CodecEntry entry) {
        PackWorkspace ws = model.workspace();
        addCodecTab(entry, entry.codec(), entry.label(), entry.side(), entry.label(), null,
                ws != null ? ws.root() : null, null);
    }

    @SuppressWarnings("unchecked")
    private <A> void addCodecTab(Object key, SchemaCodec<?> codec, String label, Side side,
                                 @Nullable String contentKind, @Nullable JsonElement initialJson,
                                 @Nullable Path defaultDir, @Nullable Consumer<A> onSave) {
        if (tabs.focus(key)) return;
        EditorPanel<A> panel;
        try {
            panel = new EditorPanel<>((SchemaCodec<A>) codec, label, side);
        } catch (Throwable t) {
            UiLog.get().error("[codec_ui] failed to build editor for {}", label, t);
            statusError("Could not build editor for " + label + ": " + t);
            return;
        }
        panel.setContentKind(contentKind);
        panel.setDefaultDir(defaultDir);
        if (onSave != null) panel.setOnSave(onSave);
        panel.setOnSavedToFile(file -> {
            PackWorkspace ws = model.workspace();
            statusSuccess("Saved " + (ws != null ? ws.relativize(file) : file.toString()));
            treePanel.refresh();
        });
        if (initialJson != null) panel.loadJson(initialJson);
        tabs.add(key,panel, label);
    }

    /** Route a pack file to the right kind of tab. */
    private void openFile(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        if (Files.isDirectory(abs)) return;
        String key = abs.toString();
        if (tabs.focus(key)) return;

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
                statusError("File too large for the text editor: " + abs.getFileName());
                return;
            }
            tabs.add(key,new TextEditorPanel(abs), null);
        } catch (Exception ex) {
            statusError("Could not open " + abs.getFileName() + ": " + ex.getMessage());
        }
    }

    /** Codec-backed file tab; false = could not even read/parse the file as JSON. */
    private boolean openCodecFile(Object key, CodecEntry entry, Path file) {
        JsonElement json;
        try {
            json = JsonParser.parseString(Files.readString(file));
        } catch (Exception ex) {
            statusError("Not parseable as JSON (" + ex.getMessage() + ") — opening as text");
            return false;
        }
        @SuppressWarnings("unchecked")
        SchemaCodec<Object> codec = (SchemaCodec<Object>) entry.codec();
        EditorPanel<Object> panel;
        try {
            panel = new EditorPanel<>(codec, String.valueOf(file.getFileName()), entry.side());
        } catch (Throwable t) {
            UiLog.get().error("[codec_ui] failed to build editor for {}", file, t);
            statusError("Could not build editor (" + t + ") — opening as text");
            return false;
        }
        panel.setContentKind(entry.label());
        panel.bindFile(file);
        panel.setDefaultDir(file.getParent());
        panel.setOnSavedToFile(saved -> {
            PackWorkspace ws = model.workspace();
            statusSuccess("Saved " + (ws != null ? ws.relativize(saved) : saved.toString()));
        });
        panel.loadJson(json);
        tabs.add(key,panel, entry.label());
        return true;
    }

    private void openImageTab(Object key, Path file) {
        BufferedImage image;
        try {
            image = ImageIO.read(file.toFile());
        } catch (Exception ex) {
            statusError("Could not read image: " + ex.getMessage());
            return;
        }
        if (image == null) {
            statusError("Unsupported image format: " + file.getFileName());
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
        tabs.add(key,new WorkbenchTab() {
            @Override public JComponent component() { return panel; }
            @Override public String title() { return title; }
            @Override public Path file() { return file; }
            @Override public boolean isDirty() { return false; }
            @Override public boolean save() { return true; }
            @Override public void setStateListener(Runnable listener) {}
            @Override public void dispose() {}
        }, null);
    }

    // -------------------- Shutdown --------------------

    private void requestClose() {
        long dirtyCount = tabs.dirtyCount();
        if (dirtyCount > 0) {
            int choice = JOptionPane.showConfirmDialog(frame,
                    dirtyCount + (dirtyCount == 1 ? " tab has" : " tabs have")
                            + " unsaved changes. Discard and close?",
                    "Unsaved changes", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) return;
        }
        toolbar.dispose();
        tabs.disposeAll();
        frame.dispose();
        if (instance == this) instance = null;
    }

    private void status(String message) {
        statusMessage(message, null, null);
    }

    private void statusSuccess(String message) {
        statusMessage(message, EditorOps.successColor(), WorkbenchIcons.checkTinted());
    }

    private void statusError(String message) {
        statusMessage(message, EditorOps.errorColor(), WorkbenchIcons.xTinted());
    }

    private void statusMessage(String message, java.awt.@Nullable Color color,
                               javax.swing.@Nullable Icon icon) {
        statusLabel.setIcon(icon);
        statusLabel.setForeground(color != null ? color
                : UIManager.getColor("Label.foreground"));
        statusLabel.setText(message);
        UiLog.get().info("[codec_ui] {}", message);
    }
}

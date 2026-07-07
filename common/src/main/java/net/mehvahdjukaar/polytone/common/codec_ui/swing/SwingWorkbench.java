package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import com.formdev.flatlaf.FlatLaf;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.mehvahdjukaar.codecui.SchemaCodec;
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
import javax.swing.JToggleButton;
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
    /** Divider location remembered while the sidebar is collapsed, so expanding restores it. */
    private int lastSidebarDivider = -1;
    /** Which activity-rail tool ("files" / "codecs") is currently shown. */
    private String activeSidebarCard = "files";
    private final JLabel packLabel = new JLabel("No pack opened") {
        @Override public void updateUI() {
            super.updateUI();
            setForeground(EditorOps.mutedColor());
        }
    };
    private final JLabel packKindLabel = new JLabel() {
        @Override public void updateUI() {
            super.updateUI();
            setFont(UiScale.labelFont(Font.PLAIN, -1f));
            setForeground(EditorOps.accentColor());
        }
    };
    private final JLabel statusLabel = new JLabel(" ") {
        @Override public void updateUI() {
            super.updateUI();
            setFont(UiScale.labelFont(Font.PLAIN, -1f));
        }
    };
    private final JLabel pathLabel = new JLabel() {
        @Override public void updateUI() {
            super.updateUI();
            setFont(UiScale.labelFont(Font.PLAIN, -1f));
            setForeground(EditorOps.mutedColor());
        }
    };
    // THE primary action — a NATIVE FlatLaf "default" (accent-filled) button, identical to the
    // accent buttons in dialogs (e.g. the unsaved-changes prompt): same computed text color, same
    // native padding. The old hand-rolled FlatLaf.style forced #FFFFFF text and manual margins that
    // diverged from those buttons and got eaten by the toolbar. A default button only paints its
    // accent when it is NOT a direct JToolBar child (FlatLaf flattens those to toolbar buttons), so
    // buildToolbar() nests it in a plain panel.
    private final JButton newContentButton = new JButton("New Content");
    /** Live zoom readout in the status bar; clicking it resets to 100%. */
    private final JButton zoomResetButton = new JButton();
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

        treePanel = new PackTreePanel(this::openFile, this::openPackChooser, model::entryForContainer);

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
        // Slight elevation + bottom hairline turns the toolbar into a proper header band.
        // Styling in updateUI() so a live theme switch recomputes the surface + divider.
        JToolBar bar = new JToolBar() {
            @Override public void updateUI() {
                super.updateUI();
                setOpaque(true);
                setBackground(EditorOps.surface(0.03f));
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, EditorOps.dividerColor()),
                        BorderFactory.createEmptyBorder(UiScale.small(), UiScale.med(), UiScale.small(), UiScale.med())));
            }
        };
        bar.setFloatable(false);

        // Accent brand mark, left-aligned like an app title bar.
        JLabel brand = new JLabel("Polytone") {
            @Override public void updateUI() {
                super.updateUI();
                setFont(UiScale.labelFont(Font.BOLD, 2f));
                setForeground(EditorOps.accentColor());
            }
        };
        brand.setBorder(BorderFactory.createEmptyBorder(0, UiScale.small(), 0, UiScale.large()));
        bar.add(brand);

        // Context first, like a title bar: brand · current pack chip. Actions follow after
        // the hairline — the old order (actions, THEN the chip, then glue) left the chip
        // floating mid-bar between button groups.
        bar.add(buildPackChip());

        bar.add(Box.createHorizontalStrut(UiScale.med()));
        bar.add(toolbarSeparator());
        bar.add(Box.createHorizontalStrut(UiScale.med()));

        newContentButton.putClientProperty("JButton.buttonType", "default"); // accent-filled primary
        newContentButton.setIcon(WorkbenchIcons.filePlus());
        newContentButton.setToolTipText(
                "Add content to the pack — the file lands in its correct folder automatically");
        newContentButton.addActionListener(e -> openNewContentDialog());
        // Nest in a plain panel so FlatLaf does NOT flatten it to a toolbar button (which would
        // drop the accent fill); this keeps the native default-button look, matching dialogs.
        JPanel newContentWrap = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        newContentWrap.setOpaque(false);
        newContentWrap.add(newContentButton);
        bar.add(newContentWrap);

        bar.add(Box.createHorizontalGlue());

        // Game-sync pair: green-tinted icons — "this talks to the running game".
        // Borderless like the mockup: lighter header, the tint does the signaling.
        reloadResourcesButton.putClientProperty("JButton.buttonType", "toolBarButton");
        reloadDataButton.putClientProperty("JButton.buttonType", "toolBarButton");
        reloadResourcesButton.setIcon(WorkbenchIcons.refreshTinted());
        reloadResourcesButton.setToolTipText("Reload the game's resource packs (F3+T) so saved files take effect");
        reloadResourcesButton.addActionListener(e -> triggerReload(Side.CLIENT_RESOURCES, reloadResourcesButton));
        reloadDataButton.setIcon(WorkbenchIcons.databaseTinted());
        reloadDataButton.setToolTipText("Reload the integrated server's datapacks (/reload)");
        reloadDataButton.addActionListener(e -> triggerReload(Side.SERVER_DATA, reloadDataButton));
        bar.add(reloadResourcesButton);
        bar.add(Box.createHorizontalStrut(UiScale.small()));
        bar.add(reloadDataButton);
        // Open-pack lives in the Files panel header, next to Re-scan — the two pack-folder actions
        // belong together (see PackTreePanel).

        // Zoom lives in the status bar (bottom-right, the conventional spot) — keeping the
        // header to context + actions only.
        bar.add(Box.createHorizontalStrut(UiScale.med()));
        bar.add(toolbarSeparator());
        bar.add(Box.createHorizontalStrut(UiScale.small()));
        bar.add(buildCompactToggle());
        bar.add(Box.createHorizontalStrut(UiScale.small()));
        bar.add(buildThemeToggle());
        return bar;
    }

    /** Toggle for the narrow-screen compact layout (field name stacked above its value). */
    private JToggleButton buildCompactToggle() {
        JToggleButton toggle = new JToggleButton("☰"); // ☰ rows/compact glyph
        toggle.putClientProperty("JButton.buttonType", "toolBarButton");
        toggle.setFocusable(false);
        toggle.setSelected(SwingSchemaEditor.isCompactMode());
        toggle.setToolTipText("Compact layout — stack each field's name above its value (saves width)");
        toggle.addActionListener(e -> SwingSchemaEditor.toggleCompact());
        return toggle;
    }

    private JButton zoomButton(javax.swing.Icon icon, String tooltip, int deltaPt) {
        JButton button = new JButton(icon);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setFocusable(false);
        button.setToolTipText(tooltip);
        button.addActionListener(e -> adjustZoom(deltaPt));
        return button;
    }

    private void adjustZoom(int deltaPt) {
        SwingSchemaEditor.adjustZoom(deltaPt);
        zoomResetButton.setText(SwingSchemaEditor.zoomPercent() + "%");
    }

    /** Vertical hairline dividing toolbar groups (actions | breadcrumb | game sync | theme). */
    private JComponent toolbarSeparator() {
        return new JSeparator(SwingConstants.VERTICAL) {
            @Override public Dimension getMaximumSize() {
                return new Dimension(UiScale.px(1), UiScale.px(24));
            }
        };
    }

    /**
     * Rounded breadcrumb chip showing the opened pack (folder glyph + name — kind); clicking
     * it opens another pack. A contained pill, so the current-location readout is visually
     * separate from the action buttons around it.
     */
    private JComponent buildPackChip() {
        JPanel chip = new JPanel() {
            @Override public void updateUI() {
                super.updateUI();
                setOpaque(true);
                setBackground(EditorOps.surface(0.06f));
                setBorder(new com.formdev.flatlaf.ui.FlatLineBorder(
                        new java.awt.Insets(4, 10, 4, 10), EditorOps.dividerColor(), 1f, 999));
            }
            @Override public Dimension getMaximumSize() {
                return getPreferredSize(); // hug the label — never stretch into a bar
            }
        };
        chip.setLayout(new BoxLayout(chip, BoxLayout.X_AXIS));
        JLabel icon = new JLabel(WorkbenchIcons.folder());
        icon.setForeground(EditorOps.mutedColor());
        chip.add(icon);
        chip.add(Box.createHorizontalStrut(UiScale.small()));
        chip.add(packLabel);
        chip.add(Box.createHorizontalStrut(UiScale.med()));
        chip.add(packKindLabel);
        chip.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        chip.setToolTipText("Click to open a different pack");
        chip.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { openPackChooser(); }
        });
        return chip;
    }

    /** Sun/moon button flipping the whole workbench between the light and dark FlatLaf themes. */
    private JButton buildThemeToggle() {
        JButton toggle = new JButton();
        toggle.putClientProperty("JButton.buttonType", "toolBarButton");
        toggle.setFocusable(false);
        Runnable sync = () -> {
            boolean dark = SwingSchemaEditor.isDarkTheme();
            // Show the destination: a sun while dark (click → light), a moon while light.
            toggle.setIcon(dark ? WorkbenchIcons.sun() : WorkbenchIcons.moon());
            toggle.setToolTipText(dark ? "Switch to light theme" : "Switch to dark theme");
        };
        sync.run();
        toggle.addActionListener(e -> {
            SwingSchemaEditor.toggleTheme();
            sync.run();
        });
        return toggle;
    }

    private JComponent buildCenter() {
        editorTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        // Card-style file tabs — the familiar code-editor look.
        editorTabs.putClientProperty("JTabbedPane.tabType", "card");
        editorTabs.putClientProperty("JTabbedPane.tabClosable", true);
        editorTabs.putClientProperty("JTabbedPane.tabCloseToolTipText", "Close (Ctrl+W)");
        editorTabs.putClientProperty("JTabbedPane.tabCloseCallback",
                (java.util.function.BiConsumer<JTabbedPane, Integer>) (tp, index) -> closeTab(index));

        centerHost.add(buildEmptyState(), CARD_EMPTY);
        centerHost.add(editorTabs, CARD_TABS);
        centerCards.show(centerHost, CARD_EMPTY);

        // Ctrl+W closes the selected tab.
        var im = centerHost.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_W, menuMask), "closeTab");
        centerHost.getActionMap().put("closeTab", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                int index = editorTabs.getSelectedIndex();
                if (index >= 0) closeTab(index);
            }
        });

        // Ctrl+= / Ctrl+- / Ctrl+0: UI zoom (base-font scaling).
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
        sidebarBody.add(buildCodecLibrary(), "codecs");

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
            setFocusable(false);
            setToolTipText(tip);
            putClientProperty("JButton.buttonType", "toolBarButton");
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

    private JComponent buildEmptyState() {
        JPanel empty = new JPanel();
        empty.setLayout(new BoxLayout(empty, BoxLayout.Y_AXIS));
        empty.add(Box.createVerticalGlue());
        JLabel art = new JLabel(WorkbenchIcons.sized("layers", 48));
        art.setAlignmentX(Component.CENTER_ALIGNMENT);
        art.setForeground(EditorOps.mutedColor()); // themed icon follows this
        empty.add(art);
        empty.add(Box.createVerticalStrut(UiScale.med()));
        JLabel title = new JLabel("No editors open");
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        title.setFont(UiScale.deriveFont(title.getFont(), Font.BOLD, 4f));
        JLabel hint = new JLabel("Open a pack folder and double-click a file, or pick a codec from the library");
        hint.setAlignmentX(Component.CENTER_ALIGNMENT);
        hint.setForeground(EditorOps.mutedColor());
        JButton open = new JButton("Open Pack", WorkbenchIcons.folderOpen());
        open.putClientProperty("JButton.buttonType", "default");
        open.setAlignmentX(Component.CENTER_ALIGNMENT);
        open.addActionListener(e -> openPackChooser());
        empty.add(title);
        empty.add(Box.createVerticalStrut(UiScale.med()));
        empty.add(hint);
        empty.add(Box.createVerticalStrut(UiScale.large()));
        empty.add(open);
        empty.add(Box.createVerticalGlue());
        return empty;
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
        zoomResetButton.putClientProperty("JButton.buttonType", "toolBarButton");
        zoomResetButton.setFocusable(false);
        zoomResetButton.setToolTipText("Reset zoom to 100% (Ctrl+0)");
        zoomResetButton.setText(SwingSchemaEditor.zoomPercent() + "%");
        zoomResetButton.addActionListener(e -> adjustZoom(0));

        Box east = Box.createHorizontalBox();
        east.add(pathLabel);
        east.add(Box.createHorizontalStrut(UiScale.med()));
        east.add(toolbarSeparator());
        east.add(Box.createHorizontalStrut(UiScale.small()));
        east.add(zoomButton(WorkbenchIcons.zoomOut(), "Zoom out (Ctrl+-)", -2));
        east.add(zoomResetButton);
        east.add(zoomButton(WorkbenchIcons.zoomIn(), "Zoom in (Ctrl+=)", +2));
        bar.add(east, BorderLayout.EAST);
        return bar;
    }

    // -------------------- Codec library sidebar --------------------

    private JComponent buildCodecLibrary() {
        JPanel panel = new JPanel(new BorderLayout(0, UiScale.small()));
        panel.setBorder(BorderFactory.createEmptyBorder(
                UiScale.small(), UiScale.small(), UiScale.small(), UiScale.small()));

        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", "Search codecs...");
        search.putClientProperty("JTextField.leadingIcon", WorkbenchIcons.search());
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
                // Flat hover rows (not full bordered buttons) — the library reads as a list.
                JButton button = new JButton(entry.label(), WorkbenchIcons.filePlus());
                button.putClientProperty("JButton.buttonType", "toolBarButton");
                button.setHorizontalAlignment(SwingConstants.LEFT);
                button.setIconTextGap(UiScale.med());
                button.setAlignmentX(Component.LEFT_ALIGNMENT);
                int rowH = Math.max(button.getPreferredSize().height, UiScale.px(34));
                button.setMaximumSize(new Dimension(Integer.MAX_VALUE, rowH));
                if (entry.containerDir() != null) {
                    button.setToolTipText("Pack folder: " + entry.containerDir());
                }
                button.addActionListener(e -> openEntryTab(entry));
                column.add(button);
            }
        }
        if (groups.isEmpty()) {
            JLabel none = new JLabel("No codecs match", WorkbenchIcons.search(), SwingConstants.LEFT);
            none.setAlignmentX(Component.LEFT_ALIGNMENT);
            none.setForeground(EditorOps.mutedColor());
            none.setBorder(BorderFactory.createEmptyBorder(UiScale.med(), UiScale.small(), 0, 0));
            column.add(none);
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
            statusError("Could not open pack: " + ex.getMessage());
        }
    }

    private void onWorkspaceChanged() {
        PackWorkspace ws = model.workspace();
        treePanel.setWorkspace(ws);
        newContentButton.setEnabled(ws != null
                && model.entries().stream().anyMatch(e -> e.containerDir() != null));
        if (ws == null) {
            packLabel.setText("No pack opened");
            packKindLabel.setText("");
            pathLabel.setText("");
        } else {
            packLabel.setText(ws.name());
            packKindLabel.setText(ws.kind().display());
            packLabel.setToolTipText(ws.root().toString());
            pathLabel.setText(ws.root().toString());
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
            if (error == null) statusSuccess("Reload complete — new files are now referenceable");
            else statusError("Reload failed: " + error);
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
        addTab(key, panel, created.entry().label());
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
        if (focusExisting(key)) return;
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
                statusError("File too large for the text editor: " + abs.getFileName());
                return;
            }
            addTab(key, new TextEditorPanel(abs), null);
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
        addTab(key, panel, entry.label());
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
            if (index >= 0) {
                editorTabs.setTitleAt(index, tab.title());
                // Amber dot = unsaved changes; icon slot stays empty on clean tabs.
                editorTabs.setIconAt(index, tab.isDirty() ? WorkbenchIcons.dirtyDot() : null);
            }
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
                    "Save changes to \"" + tab.title() + "\"?",
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

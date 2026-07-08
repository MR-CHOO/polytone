package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import net.mehvahdjukaar.polytone.common.codec_ui.workbench.CodecEntry;
import net.mehvahdjukaar.polytone.common.codec_ui.workbench.PackWorkspace;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Lazily-populated file tree of the opened {@link PackWorkspace}. Children are scanned on
 * expand (packs can be huge); Refresh rebuilds from the root. Double-clicking a file hands
 * it to the shell's open-file routing.
 */
final class PackTreePanel extends JPanel {

    private static final String CARD_EMPTY = "empty";
    private static final String CARD_TREE = "tree";

    private final CardLayout cards = new CardLayout();
    private final JPanel cardHost = new JPanel(cards);
    private final DefaultTreeModel treeModel;
    private final JTree tree;
    // Font/color set in updateUI() so zoom (FlatLaf.updateUI) re-derives them fresh —
    // deriving from getFont() here would compound the delta on every theme/zoom change.
    private final JLabel packLabel = StyledLabels.of(" ", l -> {
        l.setFont(UiScale.labelFont(Font.BOLD, -1f));
        l.setForeground(EditorOps.mutedColor());
    });
    private @Nullable PackWorkspace workspace;
    /** Resolves a directory to the codec content type it is the root of (colormaps, …), or null. */
    private final @Nullable Function<Path, CodecEntry> codecRootResolver;

    PackTreePanel(Consumer<Path> onOpenFile, Runnable openPackAction,
                  @Nullable Function<Path, CodecEntry> codecRootResolver) {
        super(new BorderLayout(0, UiScale.small()));
        this.codecRootResolver = codecRootResolver;
        setBorder(BorderFactory.createEmptyBorder(
                UiScale.small(), UiScale.small(), UiScale.small(), UiScale.small()));

        // ---- Header row: pack kind label + open-pack / re-scan actions ----
        JPanel header = new JPanel(new BorderLayout());
        header.add(packLabel, BorderLayout.CENTER);

        JButton openPack = Buttons.toolbar(WorkbenchIcons.folderOpen(),
                "Open a resource pack / datapack folder — any folder works",
                e -> openPackAction.run());

        JButton refresh = Buttons.toolbar(WorkbenchIcons.refresh(),
                "Re-scan the pack folder", e -> refresh());

        Box headerActions = Box.createHorizontalBox();
        headerActions.add(openPack);
        headerActions.add(refresh);
        header.add(headerActions, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        // ---- Tree ----
        treeModel = new DefaultTreeModel(new DefaultMutableTreeNode("(no pack)"));
        tree = new JTree(treeModel);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(0); // let the renderer decide (needed for L&F scaling)
        tree.setCellRenderer(new Renderer());
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override public void treeWillExpand(TreeExpansionEvent event) {
                if (event.getPath().getLastPathComponent() instanceof FileNode node) {
                    populate(node);
                }
            }

            @Override public void treeWillCollapse(TreeExpansionEvent event) {}
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path != null && path.getLastPathComponent() instanceof FileNode node && !node.directory) {
                    onOpenFile.accept(node.path);
                }
            }
        });

        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createLineBorder(
                javax.swing.UIManager.getColor("Component.borderColor")));
        cardHost.add(scroll, CARD_TREE);
        cardHost.add(buildEmptyState(openPackAction), CARD_EMPTY);
        add(cardHost, BorderLayout.CENTER);

        cards.show(cardHost, CARD_EMPTY);
    }

    private JComponent buildEmptyState(Runnable openPackAction) {
        JPanel empty = new JPanel();
        empty.setLayout(new javax.swing.BoxLayout(empty, javax.swing.BoxLayout.Y_AXIS));
        empty.add(Box.createVerticalGlue());
        JLabel art = new JLabel(WorkbenchIcons.sized("folder", 40));
        art.setAlignmentX(Component.CENTER_ALIGNMENT);
        art.setForeground(EditorOps.mutedColor()); // themed icon follows this
        empty.add(art);
        empty.add(Box.createVerticalStrut(UiScale.med()));
        JLabel line1 = new JLabel("No pack opened");
        line1.setAlignmentX(Component.CENTER_ALIGNMENT);
        line1.setForeground(EditorOps.mutedColor());
        JLabel line2 = new JLabel("Any folder works — packs load from odd places");
        line2.setAlignmentX(Component.CENTER_ALIGNMENT);
        line2.setForeground(EditorOps.mutedColor());
        line2.setFont(UiScale.deriveFont(line2.getFont(), Font.PLAIN, -2f));
        JButton open = new JButton("Open Pack", WorkbenchIcons.folderOpen());
        open.setAlignmentX(Component.CENTER_ALIGNMENT);
        open.addActionListener(e -> openPackAction.run());
        empty.add(line1);
        empty.add(Box.createVerticalStrut(UiScale.small()));
        empty.add(line2);
        empty.add(Box.createVerticalStrut(UiScale.large()));
        empty.add(open);
        empty.add(Box.createVerticalGlue());
        return empty;
    }

    void setWorkspace(@Nullable PackWorkspace workspace) {
        this.workspace = workspace;
        if (workspace == null) {
            cards.show(cardHost, CARD_EMPTY);
            return;
        }
        packLabel.setText(workspace.kind().display()
                + (workspace.hasPackMcmeta() ? "" : "  (no pack.mcmeta)"));
        FileNode root = new FileNode(workspace.root(), true, workspace.name());
        populate(root);
        treeModel.setRoot(root);
        cards.show(cardHost, CARD_TREE);
        SwingUtilities.invokeLater(() -> tree.expandPath(new TreePath(root)));
    }

    /** Full rebuild — expansion state is intentionally not preserved yet (foundation). */
    void refresh() {
        setWorkspace(workspace);
    }

    private void populate(FileNode node) {
        if (node.loaded || workspace == null) return;
        node.loaded = true;
        node.removeAllChildren();
        for (Path child : workspace.children(node.path)) {
            node.add(new FileNode(child, java.nio.file.Files.isDirectory(child), null));
        }
        treeModel.nodeStructureChanged(node);
    }

    /** Tree node for one path; directories carry a placeholder child until first expand. */
    private static final class FileNode extends DefaultMutableTreeNode {
        final Path path;
        final boolean directory;
        boolean loaded;

        FileNode(Path path, boolean directory, @Nullable String displayName) {
            super(displayName != null ? displayName : String.valueOf(path.getFileName()));
            this.path = path;
            this.directory = directory;
            if (directory) add(new DefaultMutableTreeNode("…"));
        }
    }

    /**
     * Folder icons keyed off the real directory flag (not child count). Directories we recognise
     * as a codec content root (colormaps, block modifiers, …) get an accent-tinted folder icon and
     * a trailing accent tag naming the content type — a clear "this is a Polytone folder" marker.
     */
    private final class Renderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                                                      boolean expanded, boolean leaf, int row, boolean focus) {
            boolean dir = value instanceof FileNode node && node.directory;
            CodecEntry codecRoot = dir && codecRootResolver != null
                    ? codecRootResolver.apply(((FileNode) value).path) : null;
            super.getTreeCellRendererComponent(tree, value, sel, expanded, dir ? false : leaf, row, focus);
            if (dir && codecRoot != null) {
                setIcon(WorkbenchIcons.folderAccent());
                String name = String.valueOf(((FileNode) value).path.getFileName());
                // Accent tag, but switch to the selection foreground when the row is selected
                // (accent-on-accent would vanish).
                String tagColor = hex(sel ? getTextSelectionColor() : EditorOps.accentColor());
                setText("<html>" + escapeHtml(name)
                        + " <font color='" + tagColor + "'>• " + escapeHtml(codecRoot.label())
                        + "</font></html>");
                setToolTipText(codecRoot.label() + " — Polytone codec folder");
            } else if (dir) {
                setIcon(expanded ? getOpenIcon() : getClosedIcon());
                setToolTipText(null);
            }
            return this;
        }
    }

    private static String hex(java.awt.Color c) {
        return String.format("#%06X", c.getRGB() & 0xFFFFFF);
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

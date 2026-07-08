package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Owns the editor tab strip: the {@link JTabbedPane}, the empty-state placeholder they card
 * between, and the bookkeeping that dedups tabs by an opaque key (file path / codec entry /
 * standalone label). The shell's various "open X" flows build a {@link WorkbenchTab} and hand it
 * here via {@link #add}; everything about tab lifecycle (close prompts, dirty tracking, disposal)
 * lives in this one place.
 */
final class WorkbenchTabs {

    private static final String CARD_EMPTY = "empty";
    private static final String CARD_TABS = "tabs";

    private final JFrame dialogParent;
    private final JTabbedPane editorTabs = new JTabbedPane();
    private final CardLayout cards = new CardLayout();
    private final JPanel host = new JPanel();

    /** Dedup key (file path string / CodecEntry / standalone label) → hosted tab. */
    private final Map<Object, WorkbenchTab> tabsByKey = new LinkedHashMap<>();
    private final Map<Component, Object> keysByComponent = new HashMap<>();

    WorkbenchTabs(JFrame dialogParent, Runnable onOpenPack) {
        this.dialogParent = dialogParent;
        host.setLayout(cards);

        editorTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        // Card-style file tabs — the familiar code-editor look.
        editorTabs.putClientProperty("JTabbedPane.tabType", "card");
        editorTabs.putClientProperty("JTabbedPane.tabClosable", true);
        editorTabs.putClientProperty("JTabbedPane.tabCloseToolTipText", "Close (Ctrl+W)");
        editorTabs.putClientProperty("JTabbedPane.tabCloseCallback",
                (BiConsumer<JTabbedPane, Integer>) (tp, index) -> closeTab(index));

        host.add(buildEmptyState(onOpenPack), CARD_EMPTY);
        host.add(editorTabs, CARD_TABS);
        cards.show(host, CARD_EMPTY);

        // Ctrl+W closes the selected tab.
        var im = host.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_W, menuMask), "closeTab");
        host.getActionMap().put("closeTab", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                int index = editorTabs.getSelectedIndex();
                if (index >= 0) closeTab(index);
            }
        });
    }

    /** The card panel (empty-state ↔ tab strip) to seat in the workbench center. */
    JComponent component() {
        return host;
    }

    /** Focus an already-open tab for {@code key}; false if none is open. */
    boolean focus(Object key) {
        WorkbenchTab existing = tabsByKey.get(key);
        if (existing == null) return false;
        editorTabs.setSelectedComponent(existing.component());
        return true;
    }

    void add(Object key, WorkbenchTab tab, @Nullable String tooltipPrefix) {
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
        cards.show(host, CARD_TABS);
    }

    /** How many open tabs have unsaved changes (for the window-close prompt). */
    long dirtyCount() {
        return tabsByKey.values().stream().filter(WorkbenchTab::isDirty).count();
    }

    /** Dispose every tab and clear the bookkeeping — called when the window closes. */
    void disposeAll() {
        tabsByKey.values().forEach(WorkbenchTab::dispose);
        tabsByKey.clear();
        keysByComponent.clear();
    }

    private void closeTab(int index) {
        Component comp = editorTabs.getComponentAt(index);
        Object key = keysByComponent.get(comp);
        WorkbenchTab tab = key != null ? tabsByKey.get(key) : null;
        if (tab != null && tab.isDirty()) {
            int choice = JOptionPane.showConfirmDialog(dialogParent,
                    "Save changes to \"" + tab.title() + "\"?",
                    "Unsaved changes", JOptionPane.YES_NO_CANCEL_OPTION);
            if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) return;
            if (choice == JOptionPane.YES_OPTION && !tab.save()) return;
        }
        if (tab != null) tab.dispose();
        if (key != null) tabsByKey.remove(key);
        keysByComponent.remove(comp);
        editorTabs.removeTabAt(index);
        if (editorTabs.getTabCount() == 0) cards.show(host, CARD_EMPTY);
    }

    private JComponent buildEmptyState(Runnable onOpenPack) {
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
        JButton open = Buttons.primary("Open Pack", WorkbenchIcons.folderOpen());
        open.setAlignmentX(Component.CENTER_ALIGNMENT);
        open.addActionListener(e -> onOpenPack.run());
        empty.add(title);
        empty.add(Box.createVerticalStrut(UiScale.med()));
        empty.add(hint);
        empty.add(Box.createVerticalStrut(UiScale.large()));
        empty.add(open);
        empty.add(Box.createVerticalGlue());
        return empty;
    }
}

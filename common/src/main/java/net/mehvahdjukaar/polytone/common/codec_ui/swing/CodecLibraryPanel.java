package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import net.mehvahdjukaar.polytone.common.codec_ui.SchemaEditor.Side;
import net.mehvahdjukaar.polytone.common.codec_ui.workbench.CodecEntry;
import net.mehvahdjukaar.polytone.common.codec_ui.workbench.Workbench;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The "Codecs" sidebar tool: a searchable, side-filterable list of the workbench's registered
 * {@link CodecEntry}s, grouped by {@link CodecEntry#group()}. Clicking a row opens that codec via
 * the supplied {@code onOpen} callback. The entry set is fixed for the workbench's lifetime, so
 * the column is rebuilt only in response to the search box / side filter.
 */
final class CodecLibraryPanel extends JPanel {

    private final Workbench model;
    private final Consumer<CodecEntry> onOpen;
    private final JPanel column = new JPanel();

    CodecLibraryPanel(Workbench model, Consumer<CodecEntry> onOpen) {
        super(new BorderLayout(0, UiScale.small()));
        this.model = model;
        this.onOpen = onOpen;
        setBorder(BorderFactory.createEmptyBorder(
                UiScale.small(), UiScale.small(), UiScale.small(), UiScale.small()));

        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", "Search codecs...");
        search.putClientProperty("JTextField.leadingIcon", WorkbenchIcons.search());
        search.putClientProperty("JTextField.showClearButton", Boolean.TRUE);

        JComboBox<String> sideFilter = new JComboBox<>(new String[]{"All", "Client", "Server"});

        JPanel north = new JPanel(new BorderLayout(UiScale.small(), 0));
        north.add(search, BorderLayout.CENTER);
        north.add(sideFilter, BorderLayout.EAST);
        add(north, BorderLayout.NORTH);

        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));

        JScrollPane scroll = new JScrollPane(column);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(UiScale.px(16));
        scroll.getViewport().setOpaque(false);
        add(scroll, BorderLayout.CENTER);

        Runnable rebuild = () -> {
            rebuildColumn(search.getText(), (String) sideFilter.getSelectedItem());
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
    }

    private void rebuildColumn(String query, @Nullable String sideChoice) {
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
                button.addActionListener(e -> onOpen.accept(entry));
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
}

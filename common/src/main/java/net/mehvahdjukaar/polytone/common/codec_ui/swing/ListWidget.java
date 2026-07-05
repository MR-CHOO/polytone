package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import net.mehvahdjukaar.polytone.common.codec_ui.Schema;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

public final class ListWidget implements SwingWidget {

    private final Schema<?> elementSchema;
    private final JPanel root = new JPanel();
    private final JPanel rowsHost = new JPanel();
    private final javax.swing.JLabel emptyHint = new javax.swing.JLabel("(empty)");
    private final List<SwingWidget> rowWidgets = new ArrayList<>();
    private final List<JPanel> rowPanels = new ArrayList<>();
    private final List<javax.swing.JLabel> indexLabels = new ArrayList<>();

    public ListWidget(Schema.ListOf<?> schema) {
        this.elementSchema = schema.element();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        // Let the list widget stretch to fill the parent's available horizontal width
        // so child rows can lay out flush with the form column.
        root.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        rowsHost.setLayout(new BoxLayout(rowsHost, BoxLayout.Y_AXIS));
        rowsHost.setAlignmentX(Component.LEFT_ALIGNMENT);
        rowsHost.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        root.add(rowsHost);

        emptyHint.setFont(UiScale.deriveFont(emptyHint.getFont(), java.awt.Font.ITALIC, -1f));
        emptyHint.setForeground(EditorOps.mutedColor());
        emptyHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        emptyHint.setBorder(BorderFactory.createEmptyBorder(0, UiScale.med(), UiScale.small(), 0));
        root.add(emptyHint);

        JPanel addBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        addBar.setOpaque(false);
        addBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton add = new JButton("Add", WorkbenchIcons.plusAccent());
        add.putClientProperty("JButton.buttonType", "roundRect");
        add.addActionListener(e -> {
            addRow(null);
            root.revalidate();
            root.repaint();
        });
        addBar.add(add);
        root.add(addBar);
    }

    private void addRow(@Nullable JsonElement initialValue) {
        SwingWidget child = SwingWidgetFactory.create(elementSchema);
        if (initialValue != null) {
            child.setJson(initialValue);
        }
        rowWidgets.add(child);

        // Rows report a live maximum height so children that grow later (collapsible raw
        // JSON / expression editors) re-flow instead of being clipped at built-time height.
        JPanel row = new JPanel() {
            @Override public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Thin left rail + indent groups each element with its index and remove button so
        // the array reads as a stack of list items; the empty bottom border replaces the
        // old inter-row struts (which leaked in rowsHost when a row was removed).
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0, 0, UiScale.med(), 0),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, UiScale.px(2), 0, 0, EditorOps.accentColor()),
                        BorderFactory.createEmptyBorder(0, UiScale.med(), 0, 0))));

        // Everything top-aligned: with tall children (records, pickers) a centered remove
        // button floats in the middle of the row, which is what made lists feel off.
        javax.swing.JLabel index = new javax.swing.JLabel();
        index.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, UiScale.px(12)));
        index.setForeground(EditorOps.mutedColor());
        index.setBorder(BorderFactory.createEmptyBorder(UiScale.px(6), 0, 0, UiScale.med()));
        index.setAlignmentY(Component.TOP_ALIGNMENT);
        indexLabels.add(index);
        row.add(index);

        JComponent childComp = child.component();
        childComp.setAlignmentY(Component.TOP_ALIGNMENT);
        row.add(childComp);
        Component gap = Box.createHorizontalStrut(UiScale.small());
        ((JComponent) gap).setAlignmentY(Component.TOP_ALIGNMENT);
        row.add(gap);

        JButton remove = new JButton(WorkbenchIcons.trash());
        remove.setToolTipText("Remove");
        remove.putClientProperty("JButton.buttonType", "borderless");
        remove.setMargin(UiScale.insets(0, 4, 0, 4));
        remove.setAlignmentY(Component.TOP_ALIGNMENT);
        remove.addActionListener(e -> {
            int idx = rowPanels.indexOf(row);
            if (idx >= 0) {
                rowsHost.remove(row);
                rowPanels.remove(idx);
                rowWidgets.remove(idx);
                indexLabels.remove(idx);
                refreshDecorations();
                root.revalidate();
                root.repaint();
            }
        });
        row.add(remove);

        rowPanels.add(row);
        rowsHost.add(row);
        refreshDecorations();
    }

    /** Renumber the index column and show the empty hint when there are no rows. */
    private void refreshDecorations() {
        for (int i = 0; i < indexLabels.size(); i++) {
            indexLabels.get(i).setText(String.valueOf(i + 1));
        }
        emptyHint.setVisible(rowWidgets.isEmpty());
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public DataResult<JsonElement> currentJson() {
        JsonArray array = new JsonArray();
        for (int i = 0; i < rowWidgets.size(); i++) {
            DataResult<JsonElement> r = rowWidgets.get(i).currentJson();
            var error = r.error();
            if (error.isPresent()) {
                int idx = i;
                String msg = error.get().message();
                return DataResult.error(() -> "List[" + idx + "]: " + msg);
            }
            r.result().ifPresent(array::add);
        }
        return DataResult.success(array);
    }

    @Override
    public void setJson(@Nullable JsonElement value) {
        // clear existing rows
        rowsHost.removeAll();
        rowPanels.clear();
        rowWidgets.clear();
        indexLabels.clear();
        if (value != null && value.isJsonArray()) {
            for (JsonElement el : value.getAsJsonArray()) {
                addRow(el);
            }
        }
        refreshDecorations();
        root.revalidate();
        root.repaint();
    }
}

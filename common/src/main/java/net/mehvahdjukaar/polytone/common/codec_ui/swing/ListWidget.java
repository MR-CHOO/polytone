package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import net.mehvahdjukaar.codecui.Schema;
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
    // ONE rounded hairline container wraps the whole list — rows, empty hint AND add
    // button — so empty and filled lists read as the same bounded thing (like AnyOf).
    private final JPanel root = new JPanel() {
        @Override public void updateUI() {
            super.updateUI();
            setOpaque(false);
            setBorder(new com.formdev.flatlaf.ui.FlatLineBorder(
                    new java.awt.Insets(8, 10, 8, 10), EditorOps.dividerColor(), 1f, 10));
        }
    };
    private final JPanel rowsHost = new JPanel();
    private final javax.swing.JLabel emptyHint = StyledLabels.of("(empty)", l -> {
        l.setFont(UiScale.labelFont(java.awt.Font.ITALIC, -1f));
        l.setForeground(EditorOps.mutedColor());
    });
    // Minimal "+" — contextual label goes in the tooltip via setItemLabel.
    private final JButton addButton = new JButton(WorkbenchIcons.plusAccent());
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

        emptyHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        emptyHint.setBorder(BorderFactory.createEmptyBorder(0, 0, UiScale.small(), 0));
        root.add(emptyHint);

        JPanel addBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        addBar.setOpaque(false);
        addBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        addButton.putClientProperty("JButton.buttonType", "roundRect");
        addButton.setToolTipText("Add item"); // refined by setItemLabel when nested in a record
        addButton.addActionListener(e -> {
            addRow(null);
            root.revalidate();
            root.repaint();
        });
        addBar.add(addButton);
        root.add(addBar);
    }

    /**
     * The owning record passes the (prettified) field name; the button stays a minimal
     * "+" — the context lives in its tooltip ("Add Sound Emitter").
     */
    void setItemLabel(String pluralPretty) {
        addButton.setToolTipText("Add " + singularize(pluralPretty));
    }

    private static String singularize(String s) {
        if (s.endsWith("ies") && s.length() > 3) return s.substring(0, s.length() - 3) + "y";
        if (s.endsWith("s") && !s.endsWith("ss") && s.length() > 1) return s.substring(0, s.length() - 1);
        return s;
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
                return UiScale.maxHeightHugging(this);
            }
        };
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        // No per-row box — the widget's outer container is the single frame; rows are
        // separated by spacing + the index column. (Bottom border instead of inter-row
        // struts, which leaked in rowsHost when a row was removed.)
        row.setBorder(BorderFactory.createEmptyBorder(0, 0, UiScale.med(), 0));

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

package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import net.mehvahdjukaar.codecui.Schema;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * Editor for {@link Schema.MapOf}: key → value rows. Styled EXACTLY like {@link ListWidget}
 * (rounded hairline row containers, top-aligned trash button, accent "+" add with tooltip,
 * "(empty)" hint, live row heights) so maps and lists read as the same kind of thing.
 */
public final class MapOfWidget implements SwingWidget {

    private final Schema<?> keySchema;
    private final Schema<?> valueSchema;
    // Same single-container structure as ListWidget: one rounded box around everything.
    private final JPanel root = new JPanel() {
        @Override public void updateUI() {
            super.updateUI();
            setOpaque(false);
            setBorder(new com.formdev.flatlaf.ui.FlatLineBorder(
                    new java.awt.Insets(8, 10, 8, 10), EditorOps.dividerColor(), 1f, 10));
        }
    };
    private final JPanel rowsHost = new JPanel();
    private final JLabel emptyHint = StyledLabels.of("(empty)", l -> {
        l.setFont(UiScale.labelFont(Font.ITALIC, -1f));
        l.setForeground(EditorOps.mutedColor());
    });
    private final JButton addButton = new JButton(WorkbenchIcons.plusAccent());
    private final List<SwingWidget> keyWidgets = new ArrayList<>();
    private final List<SwingWidget> valueWidgets = new ArrayList<>();
    private final List<JPanel> rowPanels = new ArrayList<>();

    public MapOfWidget(Schema.MapOf<?, ?> schema) {
        this.keySchema = schema.key();
        this.valueSchema = schema.value();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        // Allow the map widget to fill the parent's available horizontal width.
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
        addButton.setToolTipText("Add entry");
        addButton.addActionListener(e -> {
            addRow(null, null);
            root.revalidate();
            root.repaint();
        });
        addBar.add(addButton);
        root.add(addBar);
    }

    private void addRow(@Nullable JsonElement initialKey, @Nullable JsonElement initialValue) {
        SwingWidget keyWidget = SwingWidgetFactory.create(keySchema);
        SwingWidget valueWidget = SwingWidgetFactory.create(valueSchema);
        if (initialKey != null) keyWidget.setJson(initialKey);
        if (initialValue != null) valueWidget.setJson(initialValue);
        keyWidgets.add(keyWidget);
        valueWidgets.add(valueWidget);

        // Live max height so children that grow later (collapsibles) re-flow.
        JPanel row = new JPanel() {
            @Override public Dimension getMaximumSize() {
                return UiScale.maxHeightHugging(this);
            }
        };
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        // No per-row box — the widget's outer container is the single frame; the bottom
        // border spaces rows without leakable struts.
        row.setBorder(BorderFactory.createEmptyBorder(0, 0, UiScale.med(), 0));

        JComponent keyComp = keyWidget.component();
        keyComp.setAlignmentY(Component.TOP_ALIGNMENT);
        row.add(keyComp);
        row.add(topAlignedStrut());

        JComponent valueComp = valueWidget.component();
        valueComp.setAlignmentY(Component.TOP_ALIGNMENT);
        row.add(valueComp);
        row.add(topAlignedStrut());

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
                keyWidgets.remove(idx);
                valueWidgets.remove(idx);
                emptyHint.setVisible(keyWidgets.isEmpty());
                root.revalidate();
                root.repaint();
            }
        });
        row.add(remove);

        rowPanels.add(row);
        rowsHost.add(row);
        emptyHint.setVisible(false);
    }

    private Component topAlignedStrut() {
        Component strut = Box.createHorizontalStrut(UiScale.small());
        ((JComponent) strut).setAlignmentY(Component.TOP_ALIGNMENT);
        return strut;
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public DataResult<JsonElement> currentJson() {
        JsonObject out = new JsonObject();
        for (int i = 0; i < keyWidgets.size(); i++) {
            DataResult<JsonElement> keyResult = keyWidgets.get(i).currentJson();
            var keyError = keyResult.error();
            if (keyError.isPresent()) {
                int idx = i;
                String msg = keyError.get().message();
                return DataResult.error(() -> "Map key[" + idx + "]: " + msg);
            }
            JsonElement keyJson = keyResult.result().orElse(null);
            if (keyJson == null) {
                int idx = i;
                return DataResult.error(() -> "Map key[" + idx + "] missing");
            }
            String keyStr;
            if (keyJson.isJsonPrimitive()) {
                keyStr = keyJson.getAsJsonPrimitive().getAsString();
            } else {
                int idx = i;
                return DataResult.error(() -> "Map key[" + idx + "] is not stringifiable");
            }

            DataResult<JsonElement> valueResult = valueWidgets.get(i).currentJson();
            var valueError = valueResult.error();
            if (valueError.isPresent()) {
                int idx = i;
                String msg = valueError.get().message();
                return DataResult.error(() -> "Map value[" + idx + "]: " + msg);
            }
            JsonElement valueJson = valueResult.result().orElse(null);
            if (valueJson != null) {
                out.add(keyStr, valueJson);
            }
        }
        return DataResult.success(out);
    }

    @Override
    public void setJson(@Nullable JsonElement value) {
        rowsHost.removeAll();
        rowPanels.clear();
        keyWidgets.clear();
        valueWidgets.clear();
        if (value != null && value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) {
                addRow(new JsonPrimitive(entry.getKey()), entry.getValue());
            }
        }
        emptyHint.setVisible(keyWidgets.isEmpty());
        root.revalidate();
        root.repaint();
    }
}

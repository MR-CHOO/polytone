package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import net.mehvahdjukaar.polytone.common.codec_ui.Schema;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * One flat picker over N alternative shapes ({@link Schema.AnyOf}). The AnyOf factory
 * already spliced nested alternatives, so however deep the original either-chain was,
 * the user sees a single combo of labeled options with the active option's editor below.
 */
public final class AnyOfWidget implements SwingWidget {

    private final List<SwingWidget> widgets = new ArrayList<>();
    private final JComboBox<String> combo;
    private final JPanel root = new JPanel();
    private final JPanel subHost = new JPanel(new BorderLayout());
    private int selected = 0;

    public AnyOfWidget(Schema.AnyOf<?> schema) {
        String[] labels = new String[schema.options().size()];
        for (int i = 0; i < schema.options().size(); i++) {
            Schema.AnyOf.Option option = schema.options().get(i);
            widgets.add(SwingWidgetFactory.create(option.schema()));
            labels[i] = option.label() != null ? option.label() : "#" + (i + 1);
        }

        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Stretch in parent so the active sub-widget can fill the form width.
        root.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, UiScale.small(), 0));
        top.setAlignmentX(Component.LEFT_ALIGNMENT);
        combo = new JComboBox<>(labels);
        top.add(combo);
        root.add(top);
        root.add(javax.swing.Box.createVerticalStrut(UiScale.small()));
        subHost.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(subHost);

        combo.addActionListener(e -> {
            int idx = combo.getSelectedIndex();
            if (idx >= 0) swapSub(idx);
        });

        swapSub(0);
    }

    private void swapSub(int index) {
        selected = index;
        subHost.removeAll();
        subHost.add(widgets.get(index).component(), BorderLayout.CENTER);
        subHost.revalidate();
        subHost.repaint();
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public DataResult<JsonElement> currentJson() {
        return widgets.get(selected).currentJson();
    }

    @Override
    public void setJson(@Nullable JsonElement value) {
        if (value == null) {
            return;
        }
        // Pragmatic: first option that accepts the value wins (mirrors decode-try-each order).
        for (int i = 0; i < widgets.size(); i++) {
            if (trySet(widgets.get(i), value)) {
                combo.setSelectedIndex(i);
                return;
            }
        }
        combo.setSelectedIndex(0);
    }

    private static boolean trySet(SwingWidget widget, JsonElement value) {
        try {
            widget.setJson(value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}

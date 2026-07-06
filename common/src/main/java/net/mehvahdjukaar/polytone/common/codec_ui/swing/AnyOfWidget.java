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
import java.util.ArrayList;
import java.util.List;

/**
 * One flat picker over N alternative shapes ({@link Schema.AnyOf}). The AnyOf factory
 * already spliced nested alternatives, so however deep the original either-chain was,
 * the user sees a single combo of labeled options with the active option's editor below.
 *
 * <p>Selector and body live inside ONE rounded hairline container so the pair reads as a
 * single grouped unit — outline-only (no surface tint) to stay distinguishable from the
 * tinted record cards that often sit inside it. Both are flush-left: the old floating
 * combo-above-offset-box arrangement is what made these rows look broken.</p>
 */
public final class AnyOfWidget implements SwingWidget {

    private final List<SwingWidget> widgets = new ArrayList<>();
    private final JComboBox<String> combo;
    // Insets/arc are LOGICAL — FlatLaf scales them (same convention as RecordWidget).
    private final JPanel root = new JPanel() {
        @Override
        public void updateUI() {
            super.updateUI();
            setOpaque(false);
            setBorder(new com.formdev.flatlaf.ui.FlatLineBorder(
                    new java.awt.Insets(8, 10, 10, 10), EditorOps.dividerColor(), 1f, 10));
        }
    };
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

        combo = new JComboBox<>(labels);
        combo.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Compact selector: size to its longest option instead of stretching form-wide.
        UiScale.pinCompact(combo);
        root.add(combo);
        root.add(javax.swing.Box.createVerticalStrut(UiScale.med()));
        subHost.setOpaque(false);
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

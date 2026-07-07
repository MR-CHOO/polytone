package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import net.mehvahdjukaar.codecui.Schema;
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
import java.util.Map;

/**
 * Editor for {@link Schema.OneOf} (type-dispatched variants). Styled EXACTLY like
 * {@link AnyOfWidget} — one rounded hairline container holding a compact variant combo
 * above the active variant's editor. The dispatch key name stays out of the UI (it's
 * wire format, shown in the combo tooltip instead).
 */
public final class OneOfWidget implements SwingWidget {

    private final String typeField;
    private final Map<String, Schema<?>> variants;
    private final List<String> variantKeys = new ArrayList<>();
    private final JComboBox<String> combo;
    // Insets/arc are LOGICAL — FlatLaf scales them (same convention as AnyOfWidget).
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
    private @Nullable SwingWidget currentSub;
    private @Nullable String currentKey;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public OneOfWidget(Schema.OneOf<?> schema) {
        this.typeField = schema.typeField();
        this.variants = (Map<String, Schema<?>>) (Map) schema.variants();
        variantKeys.addAll(variants.keySet());

        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Stretch in parent so the active variant sub-widget fills the form width.
        root.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        combo = new JComboBox<>(variantKeys.toArray(new String[0]));
        combo.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Compact selector sized to its options; the dispatch key is tooltip metadata.
        UiScale.pinCompact(combo);
        combo.setToolTipText("Selects \"" + typeField + "\"");
        root.add(combo);
        root.add(javax.swing.Box.createVerticalStrut(UiScale.med()));
        subHost.setOpaque(false);
        subHost.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(subHost);

        combo.addActionListener(e -> {
            String sel = (String) combo.getSelectedItem();
            swapSub(sel);
        });

        if (!variantKeys.isEmpty()) {
            swapSub(variantKeys.get(0));
        }
    }

    private void swapSub(@Nullable String key) {
        subHost.removeAll();
        currentKey = key;
        if (key != null) {
            Schema<?> sub = variants.get(key);
            if (sub != null) {
                currentSub = SwingWidgetFactory.create(sub);
                subHost.add(currentSub.component(), BorderLayout.CENTER);
            } else {
                currentSub = null;
            }
        } else {
            currentSub = null;
        }
        subHost.revalidate();
        subHost.repaint();
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public DataResult<JsonElement> currentJson() {
        if (currentKey == null) {
            return DataResult.error(() -> "No variant selected for OneOf");
        }
        JsonObject out = new JsonObject();
        out.add(typeField, new JsonPrimitive(currentKey));
        if (currentSub != null) {
            DataResult<JsonElement> sub = currentSub.currentJson();
            var error = sub.error();
            if (error.isPresent()) {
                String msg = error.get().message();
                return DataResult.error(() -> "Variant '" + currentKey + "': " + msg);
            }
            JsonElement subJson = sub.result().orElse(null);
            if (subJson != null) {
                if (subJson.isJsonObject()) {
                    for (var entry : subJson.getAsJsonObject().entrySet()) {
                        out.add(entry.getKey(), entry.getValue());
                    }
                } else {
                    out.add("value", subJson);
                }
            }
        }
        return DataResult.success(out);
    }

    @Override
    public void setJson(@Nullable JsonElement value) {
        if (value == null || !value.isJsonObject()) return;
        JsonObject obj = value.getAsJsonObject();
        JsonElement tag = obj.get(typeField);
        if (tag == null || !tag.isJsonPrimitive() || !tag.getAsJsonPrimitive().isString()) return;
        String key = tag.getAsString();
        if (!variants.containsKey(key)) return;
        combo.setSelectedItem(key);
        // swapSub is triggered by the action listener.
        if (currentSub != null) {
            currentSub.setJson(obj);
        }
    }
}

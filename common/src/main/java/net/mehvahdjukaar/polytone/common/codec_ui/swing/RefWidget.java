package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.mojang.serialization.DataResult;
import net.mehvahdjukaar.codecui.Schema;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;

/**
 * Lazily-expanded editor for a recursive back-reference ({@link Schema.Ref}). The inner
 * widget tree is only materialized when the user expands it or when data is loaded into it
 * — eager materialization of a cyclic schema would build widgets forever. Unexpanded and
 * empty, it contributes {@code null} JSON (the parent record treats it like an unset field;
 * required fields surface a validation error from the codec, which is the honest signal).
 */
public final class RefWidget implements SwingWidget {

    private final Schema.Ref<?> schema;
    private final JPanel root = new JPanel(new BorderLayout());
    private @Nullable SwingWidget inner;
    private @Nullable JsonElement pending;

    public RefWidget(Schema.Ref<?> schema) {
        this.schema = schema;
        root.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        root.setOpaque(false);

        JPanel collapsed = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        collapsed.setOpaque(false);
        JButton expand = new JButton("edit (" + Schema.kindName(schema) + ")…");
        expand.setFont(UiScale.uiFont("Button.font"));
        expand.addActionListener(e -> materialize());
        collapsed.add(expand);
        root.add(collapsed, BorderLayout.CENTER);
    }

    private void materialize() {
        if (inner != null) return;
        Schema<?> target = schema.target();
        // Unbound / ref-to-ref (shouldn't happen once a resolve completed): raw JSON fallback.
        inner = (target == null || target instanceof Schema.Ref<?>)
                ? new OpaqueWidget()
                : SwingWidgetFactory.create(target);
        root.removeAll();
        root.add(inner.component(), BorderLayout.CENTER);
        if (pending != null) inner.setJson(pending);
        root.revalidate();
        root.repaint();
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public DataResult<JsonElement> currentJson() {
        if (inner != null) return inner.currentJson();
        return DataResult.success(pending != null ? pending : JsonNull.INSTANCE);
    }

    @Override
    public void setJson(@Nullable JsonElement value) {
        if (inner != null) {
            inner.setJson(value);
            return;
        }
        pending = value;
        // Real data is finite — expanding on load only materializes as deep as the data goes.
        if (value != null && !value.isJsonNull()) {
            materialize();
        }
    }
}

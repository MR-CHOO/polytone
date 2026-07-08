package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import net.mehvahdjukaar.codecui.Schema;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RecordWidget implements SwingWidget {

    /**
     * {@code unsetBaseline} is the JSON the widget produces in its pristine "not set" state
     * (after the declared default was pre-filled, when representable). An optional field is
     * omitted from the output while its current JSON still equals this baseline — this is
     * what makes "matches the default ⇒ don't write it" work for EVERY widget type (bools,
     * numbers, enums, colors, nested records), not just primitive defaults.
     */
    private static final class FieldEntry {
        final Schema.Field<?, ?> field;
        final SwingWidget widget;
        @Nullable JsonElement unsetBaseline;
        /** Red "*" shown next to a REQUIRED field while it has no picked value (see {@link #isUnset}). */
        @Nullable JLabel requiredMark;
        /** Prettified field-name label — reused across the regular and compact layouts. */
        JLabel name;
        /** Trailing metadata next to the name: the "opt" pill or the required "*" (may be null). */
        @Nullable JComponent badge;

        FieldEntry(Schema.Field<?, ?> field, SwingWidget widget) {
            this.field = field;
            this.widget = widget;
        }
    }

    // Live registry so SwingSchemaEditor.toggleCompact() can re-lay every open form. Weak so a
    // closed editor tab's widget tree is collected.
    private static final List<WeakReference<RecordWidget>> INSTANCES = new CopyOnWriteArrayList<>();

    /** Re-lay every open record form after a compact-mode toggle. */
    static void relayoutAll() {
        INSTANCES.removeIf(ref -> {
            RecordWidget w = ref.get();
            if (w == null) return true;
            w.layoutFields();
            return false;
        });
    }

    // Rounded "card": a subtly elevated surface + hairline outline so a record reads as one
    // grouped unit — nested records/lists then visibly stack, like a settings panel. Styling
    // lives in updateUI() so a live light/dark theme switch recomputes the surface colors.
    // Insets/arc are LOGICAL here; FlatLaf scales them (do not pre-scale with UiScale).
    private final JPanel panel = new JPanel(new GridBagLayout()) {
        @Override
        public void updateUI() {
            super.updateUI();
            setOpaque(true);
            setBackground(EditorOps.surface(0.03f));
            setBorder(new com.formdev.flatlaf.ui.FlatLineBorder(
                    new java.awt.Insets(10, 12, 10, 12), EditorOps.dividerColor(), 1f, 12));
        }
    };
    private final List<FieldEntry> entries = new ArrayList<>();

    public RecordWidget(Schema.Record<?> schema) {
        // Allow horizontal stretch when nested inside another record / list / map row.
        // BoxLayout in particular will only stretch a child up to its maximumSize, so
        // without this nested records stay at their preferred (narrow) width.
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        for (Schema.Field<?, ?> field : schema.fields()) {
            SwingWidget child = SwingWidgetFactory.create(field.schema());
            FieldEntry entry = new FieldEntry(field, child);
            entries.add(entry);

            // Lists get their element name for a contextual add button ("Add Emitter").
            if (child instanceof ListWidget list) list.setItemLabel(prettyName(field.name()));

            // Optional fields start out showing their declared default (when representable);
            // snapshot that pristine output as the field's "unset" baseline.
            if (field.optional()) {
                JsonElement defJson = primitiveToJson(field.defaultValue());
                if (defJson != null) child.setJson(defJson);
                entry.unsetBaseline = snapshot(child);
            }

            // Prettified name (raw JSON key in the tooltip). Font set in updateUI() — re-derived
            // FRESH from the L&F default so it tracks a zoom/theme change (a construction-time
            // deriveFont() freezes) — and BOLDER in compact mode where the name acts as a title.
            JLabel name = StyledLabels.of(prettyName(field.name()), l ->
                    l.setFont(UiScale.labelFont(
                            SwingSchemaEditor.isCompactMode() ? Font.BOLD : Font.PLAIN, 0f)));
            name.setToolTipText(field.name());
            entry.name = name;

            if (field.optional()) {
                // Tiny outlined pill badge — quieter than text, clearly metadata.
                JLabel opt = StyledLabels.of("opt", l -> {
                    l.setFont(UiScale.labelFont(Font.PLAIN, -3f));
                    l.setForeground(EditorOps.mutedColor());
                    l.setBorder(new com.formdev.flatlaf.ui.FlatLineBorder(
                            new Insets(1, 6, 1, 6), EditorOps.dividerColor(), 1f, 999));
                });
                entry.badge = opt;
            } else {
                // Required field: a red asterisk that appears only while nothing is picked
                // (null/no selection) — the field will fail to load in that state. Visibility
                // is driven by isUnset() in currentJson()/setJson(); color re-derived in
                // updateUI() so a live theme switch keeps it readable.
                JLabel star = StyledLabels.of("*", l -> {
                    l.setFont(UiScale.labelFont(Font.BOLD, 1f));
                    l.setForeground(EditorOps.errorColor());
                });
                star.setToolTipText("Required — pick a value or this will fail to load");
                star.setVisible(false);
                entry.requiredMark = star;
                entry.badge = star;
            }
        }

        INSTANCES.add(new WeakReference<>(this));
        layoutFields();
        refreshRequiredMarks();
    }

    /**
     * (Re)build the field layout for the current {@link SwingSchemaEditor#isCompactMode()} state.
     * Regular = two columns (name right | value left). Compact = name stacked ABOVE the value with
     * a hairline separator between fields, trading vertical space for horizontal (narrow screens).
     */
    private void layoutFields() {
        panel.removeAll();
        boolean compact = SwingSchemaEditor.isCompactMode();
        int row = 0;
        for (int i = 0; i < entries.size(); i++) {
            FieldEntry e = entries.get(i);
            // Keep the name style in step with the mode even outside a theme-driven updateUI().
            e.name.setFont(UiScale.labelFont(compact ? Font.BOLD : Font.PLAIN, 0f));
            row = compact
                    ? addCompactField(e, row, i == entries.size() - 1)
                    : addRegularField(e, row);
        }
        // Bottom filler so fields stick to the top.
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = 0;
        filler.gridy = row;
        filler.weighty = 1.0;
        filler.fill = GridBagConstraints.VERTICAL;
        panel.add(new JLabel(""), filler);

        panel.revalidate();
        panel.repaint();
    }

    /** Two-column row: right-aligned name cell | stretched value. */
    private int addRegularField(FieldEntry e, int row) {
        JPanel labelCell = new JPanel(new GridBagLayout());
        labelCell.setOpaque(false);
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0;
        lc.anchor = GridBagConstraints.LINE_END;
        labelCell.add(e.name, lc);
        if (e.badge != null) {
            lc.gridx = 1;
            lc.insets = new Insets(0, UiScale.small(), 0, 0);
            labelCell.add(e.badge, lc);
        }

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = row;
        gc.anchor = GridBagConstraints.LINE_END;
        gc.insets = new Insets(UiScale.small(), UiScale.small(), UiScale.small(), UiScale.med());
        panel.add(labelCell, gc);

        gc = new GridBagConstraints();
        gc.gridx = 1;
        gc.gridy = row;
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.LINE_START;
        gc.insets = new Insets(UiScale.small(), 0, UiScale.small(), UiScale.small());
        panel.add(e.widget.component(), gc);
        return row + 1;
    }

    /** Stacked block: name (title) + badge on top, value below, hairline separator between fields. */
    private int addCompactField(FieldEntry e, int row, boolean last) {
        JPanel header = new JPanel(new GridBagLayout());
        header.setOpaque(false);
        GridBagConstraints hc = new GridBagConstraints();
        hc.gridx = 0;
        hc.anchor = GridBagConstraints.LINE_START;
        header.add(e.name, hc);
        if (e.badge != null) {
            hc.gridx = 1;
            hc.insets = new Insets(0, UiScale.small(), 0, 0);
            header.add(e.badge, hc);
        }

        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = row++;
        g.gridwidth = 2;
        g.weightx = 1.0;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.LINE_START;
        g.insets = new Insets(UiScale.med(), UiScale.small(), UiScale.small(), UiScale.small());
        panel.add(header, g);

        g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = row++;
        g.gridwidth = 2;
        g.weightx = 1.0;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.LINE_START;
        g.insets = new Insets(0, UiScale.small(), UiScale.med(), UiScale.small());
        panel.add(e.widget.component(), g);

        if (!last) {
            GridBagConstraints sc = new GridBagConstraints();
            sc.gridx = 0;
            sc.gridy = row++;
            sc.gridwidth = 2;
            sc.weightx = 1.0;
            sc.fill = GridBagConstraints.HORIZONTAL;
            panel.add(new JSeparator(), sc);
        }
        return row;
    }

    @Override
    public JComponent component() {
        return panel;
    }

    @Override
    public DataResult<JsonElement> currentJson() {
        JsonObject obj = new JsonObject();
        // Evaluate every child once, updating the required-field markers as we go and
        // remembering the first required error to return (we still keep iterating so all
        // markers reflect the current state, rather than stopping at the first bad field).
        DataResult<JsonElement> firstError = null;
        for (FieldEntry e : entries) {
            DataResult<JsonElement> r = e.widget.currentJson();
            updateMark(e, r);
            var error = r.error();
            if (error.isPresent()) {
                if (e.field.optional()) {
                    // Optional field with invalid child: omit so codec default applies
                    continue;
                }
                if (firstError == null) {
                    String name = e.field.name();
                    String msg = error.get().message();
                    firstError = DataResult.error(() -> "Field '" + name + "': " + msg);
                }
                continue;
            }
            JsonElement json = r.result().orElseThrow();
            // Optional field still matching its unset/default baseline → omit from the
            // output; the codec's own default applies on load.
            if (e.field.optional() && json.equals(e.unsetBaseline)) {
                continue;
            }
            obj.add(e.field.name(), json);
        }
        return firstError != null ? firstError : DataResult.success(obj);
    }

    @Override
    public void setJson(@Nullable JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            for (FieldEntry e : entries) {
                e.widget.setJson(e.field.optional() ? primitiveToJson(e.field.defaultValue()) : null);
            }
            refreshRequiredMarks();
            return;
        }
        JsonObject obj = value.getAsJsonObject();
        for (FieldEntry e : entries) {
            JsonElement sub = obj.get(e.field.name());
            if (sub == null && e.field.optional()) {
                // Missing optional key: show the declared default so output stays omitted.
                sub = primitiveToJson(e.field.defaultValue());
            }
            e.widget.setJson(sub);
        }
        refreshRequiredMarks();
    }

    /** Re-evaluate every required field's marker (used outside the {@link #currentJson()} poll). */
    private void refreshRequiredMarks() {
        for (FieldEntry e : entries) {
            if (e.requiredMark != null) updateMark(e, e.widget.currentJson());
        }
    }

    /** Show the required-marker iff the field currently has no picked value. */
    private static void updateMark(FieldEntry e, DataResult<JsonElement> current) {
        if (e.requiredMark != null) e.requiredMark.setVisible(isUnset(current));
    }

    /**
     * A field is "not picked" when its widget can't yield a real value: either it errors
     * (e.g. no dispatch variant / enum selected) or it produces {@code null} (an empty
     * resource / ref picker). Both fail codec validation, so the field is flagged required.
     */
    private static boolean isUnset(DataResult<JsonElement> r) {
        if (r.error().isPresent()) return true;
        JsonElement json = r.result().orElse(null);
        return json != null && json.isJsonNull();
    }

    /** Widget's current JSON, or null when it can't produce one (opaque mid-edit, ...). */
    private static @Nullable JsonElement snapshot(SwingWidget widget) {
        try {
            return widget.currentJson().result().orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code sound_emitters} → {@code Sound Emitters}; the raw key stays in the tooltip. */
    private static String prettyName(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (String part : raw.split("[_\\s]+")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.length() > 0 ? sb.toString() : raw;
    }

    /** JSON form of a primitive default value; null for complex/absent defaults. */
    private static @Nullable JsonElement primitiveToJson(@Nullable Object def) {
        if (def instanceof Boolean b) return new JsonPrimitive(b);
        if (def instanceof Number n) return new JsonPrimitive(n);
        if (def instanceof String s) return new JsonPrimitive(s);
        return null;
    }
}

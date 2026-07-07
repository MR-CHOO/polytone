package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import com.mojang.serialization.Codec;
import net.mehvahdjukaar.codecui.Schema;
import net.mehvahdjukaar.codecui.SchemaCodec;

/**
 * Named, reusable factory for a custom widget bound to a codec via {@link #bind}.
 *
 * <p>Idiomatic usage: declare a {@code public static final SwingWidgetDef<X> DEF = ...}
 * on the widget class itself, then {@code MyWidget.DEF.bind(codec)} in codec declarations.
 * No global registry, no Identifier strings. This mirrors how vanilla Minecraft separates
 * {@code BlockEntityType<>} / {@code RuleTestType<>} defs from their instances.</p>
 *
 * @param <A> the data type the widget edits
 */
@FunctionalInterface
public interface SwingWidgetDef<A> {
    SwingWidget create(Schema.Custom<A> schema);

    /**
     * Pair a codec with this widget def: the editor renders this widget instead of the
     * schema-derived one. Lives here rather than on the core facade so the
     * backend-agnostic API never references Swing types.
     */
    default SchemaCodec<A> bind(Codec<A> codec) {
        return SchemaCodec.of(codec, new Schema.Custom<>(this));
    }
}

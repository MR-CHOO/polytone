package net.mehvahdjukaar.polytone.common.codec_ui.example;

import net.mehvahdjukaar.polytone.common.codec_ui.Schema;
import net.mehvahdjukaar.polytone.common.codec_ui.SchemaCodecs;
import net.mehvahdjukaar.polytone.common.expressions.impl.IBlockExp;
import net.mehvahdjukaar.polytone.common.expressions.impl.ISimpleExp;
import net.mehvahdjukaar.polytone.content.colormap.ColormapExpressionProvider;

/**
 * "Convert our own codecs" showcase: a handful of small polytone codecs annotated with
 * GUI-friendly schemas via the public companion API — the same pattern any mod uses for
 * its weird codecs. Lives in {@code example} because the launcher is currently the only
 * consumer; once an in-game editor exists these registrations move to polytone init
 * (ideally right next to each codec's declaration).
 *
 * <p>Colormap itself is deliberately NOT converted here (huge); its leaf codecs are —
 * which already fixes its worst spots, since companions apply wherever the codec appears
 * as a field.</p>
 */
public final class PolytoneSchemas {

    private static boolean bootstrapped = false;

    private PolytoneSchemas() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    static synchronized void bootstrap() {
        if (bootstrapped) return;
        bootstrapped = true;

        // NOTE: ColorUtils.COLOR is no longer registered here — it's now DECLARED as a
        // SchemaCodec with a Color schema at its definition site (owned codec, owned schema).
        // This class only keeps the Swing WIDGET bindings, which must not leak into content code.

        // ColormapExpressionProvider / IBlockExp: STRING.flatXmap into compiled MVEL —
        // inference can only say "text". Bind the dedicated expression editor widget.
        SchemaCodecs.registerCompanion(ColormapExpressionProvider.CODEC,
                (Schema) new Schema.Custom<>(ExampleExpressionWidget.DEF));
        SchemaCodecs.registerCompanion(IBlockExp.CODEC,
                (Schema) new Schema.Custom<>(ExampleExpressionWidget.DEF));

        // ISimpleExp (shader uniforms etc.): constant number or MVEL expression. Inference
        // yields AnyOf("#1 number", "#2 text"); name the options and use the right widgets.
        SchemaCodecs.registerCompanion(ISimpleExp.CODEC, (Schema) Schema.anyOf(
                Schema.option("constant", Schema.doubleRange(-Double.MAX_VALUE, Double.MAX_VALUE)),
                Schema.option("expression", new Schema.Custom<>(ExampleExpressionWidget.DEF))));
    }
}

package net.mehvahdjukaar.polytone.editor;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.mehvahdjukaar.polytone.common.codec_ui.Schema;
import net.mehvahdjukaar.polytone.common.codec_ui.SchemaCodecs;
import net.mehvahdjukaar.polytone.common.codec_ui.swing.ExpressionWidget;
import net.mehvahdjukaar.polytone.common.expressions.impl.IBlockExp;
import net.mehvahdjukaar.polytone.common.expressions.impl.ISimpleExp;
import net.mehvahdjukaar.polytone.content.colormap.ColormapExpressionProvider;

/**
 * Polytone's schema companions and Swing widget bindings for codecs that can't carry their
 * schema at the declaration site — widget bindings must never leak into content code, so
 * they live here and are registered once at editor bootstrap. When no widget is involved,
 * the preferred home for a schema remains the codec's own declaration
 * (SchemaRecord / SchemaCodecs.alt).
 *
 * <p>Colormap itself is deliberately NOT converted here (huge); its leaf codecs are —
 * which already fixes its worst spots, since companions apply wherever the codec appears
 * as a field.</p>
 */
public final class PolytoneSchemas {

    private static boolean bootstrapped = false;

    /**
     * Variables of the exp4j-based {@code PolytoneExpression} family, kept in sync with
     * {@code PolytoneExpression.buildVars} (its constants are protected, hence the copy).
     */
    private static final String[] POLYTONE_EXP_VARS = {
            "POS_X", "POS_Y", "POS_Z", "TIME", "DAY_TIME", "SUN_TIME", "RAIN", "SEASON",
            "TEMPERATURE", "DOWNFALL", "BLOCK_LIGHT", "SKY_LIGHT", "DISTANCE_SQUARED",
            "PLAYER_X", "PLAYER_Y", "PLAYER_Z", "PLAYER_SPEED_SQUARED", "RENDER_DISTANCE"};

    private PolytoneSchemas() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        bootstrapped = true;

        // NOTE: ColorUtils.COLOR is no longer registered here — it's now DECLARED as a
        // SchemaCodec with a Color schema at its definition site (owned codec, owned schema).
        // This class only keeps the Swing WIDGET bindings, which must not leak into content code.

        // ColormapExpressionProvider: STRING.flatXmap into a compiled expression — inference
        // can only say "text". Bind the big expression editor, validated by the real codec
        // (i.e. the actual expression compiler), with this dialect's variable/function chips.
        SchemaCodecs.registerCompanion(ColormapExpressionProvider.CODEC,
                (Schema) new Schema.Custom<>(ExpressionWidget.define()
                        .variables(POLYTONE_EXP_VARS)
                        .variables("BIOME_VALUE", "DAMAGE")
                        .functions("state_prop")
                        .validator(compileCheck(ColormapExpressionProvider.CODEC))));

        // IBlockExp (expressions/ MVEL system): variable set differs per context and isn't
        // enumerable from here — no chips, but the compile check is still exact.
        SchemaCodecs.registerCompanion(IBlockExp.CODEC,
                (Schema) new Schema.Custom<>(ExpressionWidget.define()
                        .validator(compileCheck(IBlockExp.CODEC))));

        // ISimpleExp (shader uniforms etc.): constant number or expression. Inference yields
        // AnyOf("#1 number", "#2 text"); name the options and use the right widgets.
        SchemaCodecs.registerCompanion(ISimpleExp.CODEC, (Schema) Schema.anyOf(
                Schema.option("constant", Schema.doubleRange(-Double.MAX_VALUE, Double.MAX_VALUE)),
                Schema.option("expression", new Schema.Custom<>(ExpressionWidget.define()
                        .validator(compileCheck(ISimpleExp.CODEC))))));
    }

    /** Widget validator that parses the raw text through the expression codec itself. */
    private static ExpressionWidget.Validator compileCheck(Codec<?> codec) {
        return text -> {
            if (text.isBlank()) return "empty expression";
            return codec.parse(JsonOps.INSTANCE, new JsonPrimitive(text))
                    .error().map(e -> e.message()).orElse(null);
        };
    }
}

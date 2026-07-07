package net.mehvahdjukaar.polytone.editor;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.mehvahdjukaar.codecui.Schema;
import net.mehvahdjukaar.polytone.common.codec_ui.SchemaCodecs;
import net.mehvahdjukaar.polytone.common.codec_ui.swing.ExpressionWidget;
import net.mehvahdjukaar.polytone.common.exp.impl.BlockContextExpression;
import net.mehvahdjukaar.polytone.common.expressions.PolyExpType;
import net.mehvahdjukaar.polytone.common.expressions.impl.BlockExp;
import net.mehvahdjukaar.polytone.common.expressions.impl.ColormapExp;
import net.mehvahdjukaar.polytone.common.expressions.impl.SimpleExp;
import net.mehvahdjukaar.polytone.content.colormap.ColormapExpressionProvider;

/**
 * Polytone's schema companions + Swing widget bindings for codecs that can't carry their
 * schema at the declaration site (widget bindings must never leak into content code).
 * Registered once at editor bootstrap; the long-term home for a registration is still the
 * codec's own declaration (SchemaRecord / SchemaCodecs.alt) whenever no widget is involved.
 *
 * <p>Union codecs (IColormapExp / IBlockExp / ISimpleExp) are labeled at their declaration
 * sites; here we only bind the big expression editor to the LEAF codecs — the MVEL
 * {@code PolyExpType} codecs (chips from their declared inputs, compile-check through the
 * real parser) and the legacy exp4j ones.</p>
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

        // ---- MVEL expressions (the current system): one binding per PolyExpType leaf.
        // Chips come from the type's declared inputs; validation IS the MVEL compiler.
        SchemaCodecs.registerCompanion(ColormapExp.TYPE.codec(),
                (Schema) new Schema.Custom<>(mvelEditor(ColormapExp.TYPE)));
        SchemaCodecs.registerCompanion(BlockExp.TYPE.codec(),
                (Schema) new Schema.Custom<>(mvelEditor(BlockExp.TYPE)));
        SchemaCodecs.registerCompanion(SimpleExp.TYPE.codec(),
                (Schema) new Schema.Custom<>(mvelEditor(SimpleExp.TYPE)));

        // ---- Legacy exp4j expressions: same editor, exp4j variable chips.
        SchemaCodecs.registerCompanion(ColormapExpressionProvider.CODEC,
                (Schema) new Schema.Custom<>(ExpressionWidget.define()
                        .variables(POLYTONE_EXP_VARS)
                        .variables("BIOME_VALUE", "DAMAGE")
                        .functions("state_prop")
                        .validator(compileCheck(ColormapExpressionProvider.CODEC))));
        SchemaCodecs.registerCompanion(BlockContextExpression.CODEC,
                (Schema) new Schema.Custom<>(ExpressionWidget.define()
                        .variables(POLYTONE_EXP_VARS)
                        .validator(compileCheck(BlockContextExpression.CODEC))));
    }

    /** Expression editor for an MVEL {@link PolyExpType}: input chips + real compile check. */
    private static ExpressionWidget.Def mvelEditor(PolyExpType<?> type) {
        return ExpressionWidget.define()
                .variables(type.inputNames().toArray(String[]::new))
                .validator(compileCheck(type.codec()));
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

package net.mehvahdjukaar.polytone.content.blockmodel;

import net.mehvahdjukaar.codecui.SchemaCodec;
import net.mehvahdjukaar.codecui.SchemaRecord;
import net.mehvahdjukaar.polytone.common.Targets;
import net.mehvahdjukaar.polytone.common.expressions.impl.BlockExp;
import net.mehvahdjukaar.polytone.content.expmodel.ExpressionModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;

import java.util.List;
import java.util.Optional;

// A polytone:expression selector applied to every block in targets instead of through a blockstate file, so
// it also reaches blocks whose blockstates belong to other mods. Without a fallback, a block that matches no
// case renders its own model.
public record BlockModelModifier(Targets targets, Optional<BlockExp> selector, List<ExpressionModel.Case> cases,
                                 Optional<BlockStateModel.Unbaked> fallback) {

    public static final SchemaCodec<BlockModelModifier> CODEC = SchemaRecord.create(BlockModelModifier.class, i -> i.group(
            i.optional("targets", Targets.CODEC, Targets.EMPTY, BlockModelModifier::targets),
            i.optional("selector", BlockExp.TYPE.codec(), BlockModelModifier::selector),
            i.field("cases", ExpressionModel.Case.CODEC.listOf(), BlockModelModifier::cases),
            i.optional("fallback", BlockStateModel.Unbaked.CODEC, BlockModelModifier::fallback)
    ).apply(i, BlockModelModifier::new));
}

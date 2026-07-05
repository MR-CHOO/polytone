package net.mehvahdjukaar.polytone.common.expressions.impl;

import com.mojang.serialization.Codec;
import net.mehvahdjukaar.polytone.common.codec.CodecUtils;
import net.mehvahdjukaar.polytone.common.exp.impl.BlockContextExpression;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface IBlockExp {

    Codec<IBlockExp> CONSTANT_CODEC = Codec.DOUBLE.xmap(
            aDouble -> (level, pos, state) -> aDouble,
            iBlockExp -> 0.0);

    // Same wire codec; labels name the editor's picker options.
    Codec<IBlockExp> CODEC = Codec.lazyInitialized(() -> net.mehvahdjukaar.polytone.common.codec_ui.SchemaCodecs.labeled(
            CodecUtils.alternatives(CONSTANT_CODEC, BlockContextExpression.CODEC, BlockExp.TYPE.codec()),
            net.mehvahdjukaar.polytone.common.codec_ui.SchemaCodecs.alt("constant", CONSTANT_CODEC),
            net.mehvahdjukaar.polytone.common.codec_ui.SchemaCodecs.alt("legacy expression", BlockContextExpression.CODEC),
            net.mehvahdjukaar.polytone.common.codec_ui.SchemaCodecs.alt("expression", BlockExp.TYPE.codec())));

    double evaluate(LevelReader level, Vec3 pos, @Nullable BlockState state);

    IBlockExp ZERO = (a, b, c) -> 0.0;
    IBlockExp ONE = (a, b, c) -> 1.0;
    IBlockExp PARTICLE_RAND = (a, b, c) -> (Math.random() * 2 - 1) * 0.4;

}

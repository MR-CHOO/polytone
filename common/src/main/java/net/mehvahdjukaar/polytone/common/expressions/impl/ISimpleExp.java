package net.mehvahdjukaar.polytone.common.expressions.impl;

import com.mojang.serialization.Codec;
import net.mehvahdjukaar.polytone.common.codec.CodecUtils;

public interface ISimpleExp {

    Codec<ISimpleExp> CONSTANT_CODEC = Codec.DOUBLE.xmap(
            aDouble -> () -> aDouble,
            iBlockExp -> 0.0);

    // Same wire codec; labels name the editor's picker options.
    Codec<ISimpleExp> CODEC = Codec.lazyInitialized(() -> net.mehvahdjukaar.polytone.common.codec_ui.SchemaCodecs.labeled(
            CodecUtils.alternatives(CONSTANT_CODEC, SimpleExp.TYPE.codec()),
            net.mehvahdjukaar.polytone.common.codec_ui.SchemaCodecs.alt("constant", CONSTANT_CODEC),
            net.mehvahdjukaar.polytone.common.codec_ui.SchemaCodecs.alt("expression", SimpleExp.TYPE.codec())));

    double evaluate();

    ISimpleExp ZERO = () -> 0.0;
    ISimpleExp ONE = () -> 1.0;

}

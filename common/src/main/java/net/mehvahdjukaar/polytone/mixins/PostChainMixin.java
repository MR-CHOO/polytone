package net.mehvahdjukaar.polytone.mixins;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.mehvahdjukaar.polytone.Polytone;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PostChain.class)
public class PostChainMixin {

    // Every pass pipeline is built for the default RGBA8, and 26.2 refuses to draw into an attachment of another
    // format, so a pass writing a post_target declared with a "format" needs its pipeline built for that format
    @ModifyExpressionValue(method = "createPass", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;withBindGroupLayout(Lcom/mojang/blaze3d/pipeline/BindGroupLayout;)Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;"))
    private static RenderPipeline.Builder polytone$matchOutputFormat(RenderPipeline.Builder builder,
                                                                     @Local(argsOnly = true) PostChainConfig.Pass pass) {
        GpuFormat format = Polytone.POST_TARGETS.formatOf(pass.outputTarget());
        ColorTargetState def = ColorTargetState.DEFAULT;
        if (format == null || format == def.format()) return builder;
        return builder.withColorTargetState(new ColorTargetState(def.blendFunction(), format, def.writeMask()));
    }
}

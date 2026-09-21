package net.mehvahdjukaar.polytone.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.mehvahdjukaar.polytone.content.shaders.IScaledTarget;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

@Mixin(PostChain.class)
public class PostChainMixin {

    // A "scale" multiplies whatever size the target would otherwise have: its absolute width/height where it gives
    // one, else the screen size vanilla is handed here - so a sizeless target follows a window resize exactly as
    // vanilla's own does, and {128x128, 0.5} is 64x64. Above 1 is just a larger target, which vanilla already allows
    // for an absolute size without clamping, so nothing is clamped here either.
    @WrapOperation(method = "addToFrame", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/PostChainConfig$InternalTarget;width()Ljava/util/Optional;"))
    private Optional<Integer> polytone$scaledWidth(PostChainConfig.InternalTarget target,
                                                   Operation<Optional<Integer>> original,
                                                   @Local(argsOnly = true, ordinal = 0) int screenWidth) {
        return polytone$scaled(target, original.call(target), screenWidth);
    }

    @WrapOperation(method = "addToFrame", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/PostChainConfig$InternalTarget;height()Ljava/util/Optional;"))
    private Optional<Integer> polytone$scaledHeight(PostChainConfig.InternalTarget target,
                                                    Operation<Optional<Integer>> original,
                                                    @Local(argsOnly = true, ordinal = 1) int screenHeight) {
        return polytone$scaled(target, original.call(target), screenHeight);
    }

    @Unique
    private static Optional<Integer> polytone$scaled(PostChainConfig.InternalTarget target, Optional<Integer> size,
                                                     int screenSize) {
        Float scale = ((IScaledTarget) (Object) target).polytone$getScale();
        return scale == null ? size : Optional.of(IScaledTarget.resolve(scale, size.orElse(screenSize)));
    }
}

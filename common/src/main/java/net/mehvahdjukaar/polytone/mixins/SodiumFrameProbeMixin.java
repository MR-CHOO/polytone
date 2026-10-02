package net.mehvahdjukaar.polytone.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.mehvahdjukaar.polytone.common.FrameProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// TEMPORARY stutter probe - not for upstream, never commit. Mesh uploads and the terrain update are the usual
// suspects for a spike while chunks stream in, and both happen inside setupTerrain.
@Mixin(SodiumWorldRenderer.class)
public abstract class SodiumFrameProbeMixin {

    @Unique
    private long polytone$terrainStart;

    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void polytone$probeTerrainStart(CallbackInfo ci) {
        polytone$terrainStart = FrameProbe.now();
    }

    @Inject(method = "setupTerrain", at = @At("RETURN"))
    private void polytone$probeTerrainEnd(CallbackInfo ci) {
        FrameProbe.lap(FrameProbe.SODIUM_TERRAIN, polytone$terrainStart);
    }

    @WrapOperation(method = "setupTerrain", at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;processChunkBuilds(Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V"))
    private void polytone$probeUpload(RenderSectionManager instance, Viewport viewport, UniformBufferManager buffers, Operation<Void> op) {
        long t = FrameProbe.now();
        op.call(instance, viewport, buffers);
        FrameProbe.lap(FrameProbe.SODIUM_UPLOAD, t);
    }

    @WrapOperation(method = "setupTerrain", at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;updateChunks(Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;Z)V"))
    private void polytone$probeUpdateChunks(RenderSectionManager instance, Viewport viewport, boolean flag, Operation<Void> op) {
        long t = FrameProbe.now();
        op.call(instance, viewport, flag);
        FrameProbe.lap(FrameProbe.SODIUM_UPDATE, t);
    }

    @WrapOperation(method = "setupTerrain", at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;cleanupAndFlip(Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V"))
    private void polytone$probeFlip(RenderSectionManager instance, UniformBufferManager buffers, Operation<Void> op) {
        long t = FrameProbe.now();
        op.call(instance, buffers);
        FrameProbe.lap(FrameProbe.SODIUM_FLIP, t);
    }
}

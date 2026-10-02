package net.mehvahdjukaar.polytone.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.mehvahdjukaar.polytone.common.FrameProbe;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.PacketProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// TEMPORARY stutter probe - not for upstream, never commit. See FrameProbe.
@Mixin(Minecraft.class)
public abstract class FrameProbeMixin {

    @Unique
    private long polytone$tickStart;

    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void polytone$probeFrame(boolean advanceGameTime, CallbackInfo ci) {
        FrameProbe.frame();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void polytone$probeTickStart(CallbackInfo ci) {
        polytone$tickStart = FrameProbe.now();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void polytone$probeTickEnd(CallbackInfo ci) {
        FrameProbe.lap(FrameProbe.TICK, polytone$tickStart);
    }

    // --- runTick ---

    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/PacketProcessor;processQueuedPackets()V"))
    private void polytone$probePackets(PacketProcessor instance, Operation<Void> op) {
        long t = FrameProbe.now();
        op.call(instance);
        FrameProbe.lap(FrameProbe.PACKETS, t);
    }

    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V"))
    private void polytone$probeTasks(Minecraft instance, Operation<Void> op) {
        long t = FrameProbe.now();
        op.call(instance);
        FrameProbe.lap(FrameProbe.TASKS, t);
    }

    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureManager;tick()V"))
    private void polytone$probeTextures(TextureManager instance, Operation<Void> op) {
        long t = FrameProbe.now();
        op.call(instance);
        FrameProbe.lap(FrameProbe.TEXTURES, t);
    }

    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/SoundManager;updateSource(Lnet/minecraft/client/Camera;)V"))
    private void polytone$probeSound(SoundManager instance, Camera camera, Operation<Void> op) {
        long t = FrameProbe.now();
        op.call(instance, camera);
        FrameProbe.lap(FrameProbe.SOUND, t);
    }

    // --- renderFrame ---

    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;acquireNextTexture()V"))
    private void polytone$probeAcquire(GpuSurface instance, Operation<Void> op) {
        long t = FrameProbe.now();
        op.call(instance);
        FrameProbe.lap(FrameProbe.ACQUIRE, t);
    }

    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;update()V"))
    private void polytone$probeLevelUpdate(ClientLevel instance, Operation<Void> op) {
        long t = FrameProbe.now();
        op.call(instance);
        FrameProbe.lap(FrameProbe.LEVEL_UPDATE, t);
    }

    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;executePendingTasks()V"))
    private void polytone$probeExecPending(Operation<Void> op) {
        long t = FrameProbe.now();
        op.call();
        FrameProbe.lap(FrameProbe.EXEC_PENDING, t);
    }

    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private void polytone$probeGameRender(GameRenderer instance, DeltaTracker deltaTracker, boolean advance, Operation<Void> op) {
        long t = FrameProbe.now();
        op.call(instance, deltaTracker, advance);
        FrameProbe.lap(FrameProbe.GAME_RENDER, t);
    }

    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;blitFromTexture(Lcom/mojang/blaze3d/systems/CommandEncoder;Lcom/mojang/blaze3d/textures/GpuTextureView;)V"))
    private void polytone$probeBlit(GpuSurface instance, CommandEncoder encoder, GpuTextureView view, Operation<Void> op) {
        long t = FrameProbe.now();
        op.call(instance, encoder, view);
        FrameProbe.lap(FrameProbe.BLIT, t);
    }

    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;submit()V"))
    private void polytone$probeGpuWait(CommandEncoder instance, Operation<Void> op) {
        long t = FrameProbe.now();
        op.call(instance);
        FrameProbe.lap(FrameProbe.GPU_WAIT, t);
    }

    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;present()V"))
    private void polytone$probePresent(GpuSurface instance, Operation<Void> op) {
        long t = FrameProbe.now();
        op.call(instance);
        FrameProbe.lap(FrameProbe.PRESENT, t);
    }

    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;endFrame()V"))
    private void polytone$probeEndFrame(LevelRenderer instance, Operation<Void> op) {
        long t = FrameProbe.now();
        op.call(instance);
        FrameProbe.lap(FrameProbe.END_FRAME, t);
    }

    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V"))
    private void polytone$probeLimiter(int fps, Operation<Void> op) {
        long t = FrameProbe.now();
        op.call(fps);
        FrameProbe.lap(FrameProbe.LIMITER, t);
    }
}

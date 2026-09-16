package net.mehvahdjukaar.polytone.mixins;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds {@code RENDER_PASS_SOLID} / {@code RENDER_PASS_CUTOUT} / {@code RENDER_PASS_TRANSLUCENT} defines to
 * Sodium's terrain shader compilation.
 *
 * <p>All three terrain passes compile from the SAME source pair ({@code sodium:blocks/block_layer_opaque}), and
 * the defines Sodium adds here ({@code USE_VERTEX_COMPRESSION}, {@code USE_FOG}) are identical for every pass. So
 * without this a pack shader cannot tell solid from translucent, which makes most useful overrides impossible to
 * write (anything that should only affect water, or only opaque terrain).</p>
 *
 * <p>Additive on purpose: appending to the returned list keeps Sodium's own defines authoritative across
 * Sodium bumps. Each entry is a bare name that Sodium hands to {@code RenderPipeline.Builder#withShaderDefine}.</p>
 *
 * <p>Safe against the program cache: {@code ShaderChunkRenderer.programs} is keyed by the
 * {@link TerrainRenderPass}, so per-pass sources are always distinct cache entries.</p>
 */
@Pseudo
@Mixin(ShaderChunkRenderer.class)
public class SodiumShaderConstantsMixin {

    @ModifyReturnValue(method = "createShaderConstants", at = @At("RETURN"), remap = false, require = 0)
    private static List<String> polytone$addRenderPassDefines(List<String> original, TerrainRenderPass pass) {
        String passDefine = polytone$passDefine(pass);
        // Unknown pass (another mod registered one) - leave Sodium's defines exactly as they were.
        if (passDefine == null) return original;

        List<String> defines = new ArrayList<>(original);
        defines.add(passDefine);
        return defines;
    }

    @Unique
    private static String polytone$passDefine(TerrainRenderPass pass) {
        if (pass == DefaultTerrainRenderPasses.SOLID) return "RENDER_PASS_SOLID";
        if (pass == DefaultTerrainRenderPasses.CUTOUT) return "RENDER_PASS_CUTOUT";
        if (pass == DefaultTerrainRenderPasses.TRANSLUCENT) return "RENDER_PASS_TRANSLUCENT";
        return null;
    }
}

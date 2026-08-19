package net.mehvahdjukaar.polytone.mixins;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderConstants;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Adds {@code RENDER_PASS_SOLID} / {@code RENDER_PASS_CUTOUT} / {@code RENDER_PASS_TRANSLUCENT} defines to
 * Sodium's terrain shader compilation.
 *
 * <p>All three terrain passes compile from the SAME source pair ({@code blocks/block_layer_opaque}), and the
 * only define Sodium emits that varies between them is {@code USE_FRAGMENT_DISCARD} - which cutout and
 * translucent BOTH set. So without this a pack shader cannot tell solid from translucent, which makes most
 * useful overrides impossible to write (anything that should only affect water, or only opaque terrain).</p>
 *
 * <p>This is additive on purpose. Sodium builds these constants in a private static helper, and replacing
 * that method wholesale would mean restating its {@code USE_FRAGMENT_DISCARD} /
 * {@code USE_VERTEX_COMPRESSION} / fog logic here and re-checking it on every Sodium bump - a
 * duplication that silently rots. Appending to the return value keeps Sodium's own logic authoritative.</p>
 *
 * <p>Safe against the program cache: {@code ShaderChunkRenderer.programs} is keyed by
 * {@link ChunkShaderOptions}, which already carries the pass, so per-pass sources were always distinct
 * cache entries.</p>
 */
@Pseudo
@Mixin(ShaderChunkRenderer.class)
public class SodiumShaderConstantsMixin {

    @ModifyReturnValue(method = "createShaderConstants", at = @At("RETURN"), remap = false, require = 0)
    private static ShaderConstants polytone$addRenderPassDefines(ShaderConstants original, ChunkShaderOptions options) {
        String passDefine = polytone$passDefine(options.pass());
        // Unknown pass (another mod registered one) - leave Sodium's constants exactly as they were.
        if (passDefine == null) return original;

        ShaderConstants.Builder builder = ShaderConstants.builder();
        // ShaderConstants exposes no copy path: build() hands back an unmodifiable list of already-FORMATTED
        // strings ("#define NAME" / "#define NAME VALUE") while the builder wants the bare name and value.
        // So parse them back apart. Kept here rather than hidden in a util because it is only correct for
        // exactly the format ShaderConstants.Builder#build writes.
        for (String define : original.getDefineStrings()) {
            String body = define.startsWith("#define ") ? define.substring("#define ".length()) : define;
            int split = body.indexOf(' ');
            if (split < 0) {
                builder.add(body);
            } else {
                builder.add(body.substring(0, split), body.substring(split + 1));
            }
        }
        builder.add(passDefine);
        return builder.build();
    }

    @Unique
    private static String polytone$passDefine(TerrainRenderPass pass) {
        if (pass == DefaultTerrainRenderPasses.SOLID) return "RENDER_PASS_SOLID";
        if (pass == DefaultTerrainRenderPasses.CUTOUT) return "RENDER_PASS_CUTOUT";
        if (pass == DefaultTerrainRenderPasses.TRANSLUCENT) return "RENDER_PASS_TRANSLUCENT";
        return null;
    }
}

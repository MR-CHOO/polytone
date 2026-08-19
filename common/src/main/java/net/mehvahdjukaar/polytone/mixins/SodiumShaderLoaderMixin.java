package net.mehvahdjukaar.polytone.mixins;

import net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader;
import net.mehvahdjukaar.polytone.Polytone;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.apache.commons.io.IOUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes Sodium's terrain shaders overridable by resource packs.
 *
 * <p>Sodium reads every shader with {@code getResourceAsStream("/assets/<ns>/shaders/<path>")}, which
 * sees only the classpath and so is blind to resource packs. This redirects that one method through the
 * vanilla {@link ResourceManager}. It is the ONLY interception point needed: {@code ShaderParser}
 * resolves every {@code #import <ns:path>} by calling straight back into {@code getShaderSource}, so
 * root shaders and includes both arrive here. Overriding an include therefore works with no extra
 * machinery, and a pack may add entirely new includes ({@code #import <mypack:include/foo.glsl>})
 * simply by shipping them.</p>
 *
 * <p><b>Why this needs no shader cache, index or reload listener.</b> Fabric already exposes a mod's
 * own {@code assets/} through the ResourceManager, so Sodium's built-in files resolve as ordinary
 * resources at {@code sodium:shaders/blocks/block_layer_opaque.fsh}. A pack overrides one by shipping
 * that same path and wins by plain vanilla pack precedence. The ResourceManager is already the cache
 * and is already reload-aware, so mirroring it into our own map would only add a second thing to keep
 * in sync. Recompilation is likewise free: {@code LevelRenderer.allChanged()} (which a resource reload
 * triggers) makes Sodium call {@code SodiumWorldRenderer.reload()}, which rebuilds
 * {@code RenderSectionManager} and deletes the cached {@code GlProgram}s, so F3+T picks up an edited
 * shader with nothing to do on our side.</p>
 *
 * <p><b>Uniform blocks.</b> Nothing here binds uniforms. Polytone's expression-driven UBOs are already
 * bound by name in {@code SodiumChunkRendererMixin} via
 * {@code ShaderUniformsManager.bindToCurrentGlProgram()}, which walks the linked program with
 * {@code glGetUniformBlockIndex} and binds only blocks the program actually declares, starting at
 * binding point 1 (Sodium itself owns only point 0, {@code u_Globals}). So a pack declares a block in
 * its overridden shader - or in an include it imports - and it binds with no Java change. That is also
 * why there is no generated-include machinery: packs already hand-declare these blocks for post
 * shaders, and binding is by block NAME, not by where the declaration came from.</p>
 *
 * <p>Note this is the one place a custom UBO CAN be bound to a chunk shader: Sodium's terrain program is
 * raw GL, so {@code glUniformBlockBinding} applies directly and Mojang's pipeline validation - which
 * rejects custom UBOs on post chains in this version - never sees it.</p>
 *
 * <p><b>Failure mode to know about.</b> Sodium's {@code GlProgram.bindUniform} throws when a uniform is
 * absent, and GLSL strips uniforms that are declared but never read. An override must therefore keep
 * all of {@code u_BlockTex}, {@code u_LightTex}, {@code u_SectionTimeInfo}, {@code u_RegionOffset},
 * {@code u_CurrentTime}, {@code u_RegionID} and the {@code u_Globals} block genuinely USED - most of
 * them in the vertex stage. We deliberately do not weaken {@code bindUniform} to paper over this; the
 * pack-side preflight tool is the right place to catch it, and a real exception naming the missing
 * uniform beats a silently mis-linked program.</p>
 */
@Pseudo
@Mixin(ShaderLoader.class)
public class SodiumShaderLoaderMixin {

    /**
     * Shader ids already reported as pack-overridden. Sodium recompiles on every reload and for each
     * render pass, so without this the log would repeat the same handful of lines indefinitely.
     */
    @Unique
    private static final Set<String> polytone$reported = ConcurrentHashMap.newKeySet();

    @Inject(method = "getShaderSource", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void polytone$resolveThroughResourcePacks(Identifier name, CallbackInfoReturnable<String> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        ResourceManager manager = mc.getResourceManager();
        if (manager == null) return;

        // Sodium's classpath layout is /assets/<ns>/shaders/<path>, so the resource id is <ns>:shaders/<path>
        Identifier id = Identifier.fromNamespaceAndPath(name.getNamespace(), "shaders/" + name.getPath());

        Optional<Resource> found = manager.getResource(id);
        if (found.isEmpty()) {
            // Not in the resource stack at all. Do NOT cancel - let Sodium's own classpath read run, which
            // also covers the window before the first resource reload has completed.
            return;
        }

        Resource resource = found.get();
        String source;
        try (InputStream in = resource.open()) {
            source = IOUtils.toString(in, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // A broken override must not take terrain rendering down with it - fall back to the built-in.
            Polytone.LOGGER.error("Failed to read Sodium shader override {}, falling back to the built-in", id, e);
            return;
        }

        // Only announce genuine overrides. Sodium's own files resolve through here too (Fabric exposes mod
        // assets), and logging those would be noise rather than information.
        if (!"sodium".equals(resource.sourcePackId()) && polytone$reported.add(id.toString())) {
            Polytone.LOGGER.info("Sodium shader {} overridden by pack '{}'", id, resource.sourcePackId());
        }

        cir.setReturnValue(source);
    }
}

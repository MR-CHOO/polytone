package net.mehvahdjukaar.polytone.content.viewpoint;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.mehvahdjukaar.polytone.Polytone;
import net.mehvahdjukaar.polytone.common.reloader.ContentManager;
import net.mehvahdjukaar.polytone.common.struc.AssetsFiles;
import net.mehvahdjukaar.polytone.content.shaders.PolytoneBuiltInUniformsSet;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads {@code polytone/viewpoints/<name>.json} and drives the per-viewpoint renderers.
 *
 * <p><b>Gating:</b> a viewpoint renders only when some active post chain names it in
 * {@code uses_viewpoints} AND its own {@code activation_condition} passes. An authored-but-unreferenced
 * viewpoint costs exactly nothing, which is what makes it safe for a pack to ship several.</p>
 */
public class ViewpointsManager extends ContentManager<Viewpoint> {

    private final Map<Identifier, Viewpoint> viewpoints = new LinkedHashMap<>();
    // GPU state, kept ACROSS reloads where the id survives, so a reload doesn't drop every texture
    // and re-render everything on the next frame.
    private final Map<Identifier, ViewpointInstance> instances = new HashMap<>();
    // Every depth_sampler named by a viewpoint json, read raw at prepare time like uniform_block below.
    // GlProgramMixin / GlslCompilerMixin read it (via PostChainsManager.dynamicSamplers) when a program is
    // compiled to give the sampler a real binding - otherwise it silently falls back to unit 0 and reads
    // the scene texture. Programs can compile before a level exists, so this can't wait for parsing.
    private volatile List<String> declaredSamplerNames = List.of();
    private GpuBuffer emptyUniformBlock = null;

    public ViewpointsManager() {
        super(Spec.of("Viewpoint", () -> Viewpoint.CODEC)
                .wikiPage("Viewpoints")
                .folders("viewpoints"));
    }

    @Override
    protected AssetsFiles prepare(PreparableReloadListener.SharedState sharedState) {
        AssetsFiles resources = super.prepare(sharedState);
        // uniform_block names must be known BEFORE any program declaring them compiles, or GlProgram
        // logs "Found unknown and unsupported uniform" and never gives the block a binding point.
        // Parsing happens later, with the level, so read the raw json here - the same trick
        // PostChainsManager uses for expression uniforms.
        List<String> declared = new ArrayList<>();
        for (JsonElement e : resources.jsons().values()) {
            if (!(e instanceof JsonObject obj)) continue;
            if (obj.get("uniform_block") instanceof JsonPrimitive p && p.isString()) {
                PolytoneBuiltInUniformsSet.register(p.getAsString());
            }
            if (obj.get("depth_sampler") instanceof JsonPrimitive p && p.isString()
                    && !p.getAsString().isEmpty() && !declared.contains(p.getAsString())) {
                declared.add(p.getAsString());
            }
        }
        declaredSamplerNames = List.copyOf(declared);
        return resources;
    }

    /** Sampler names any viewpoint json declares, for registering on programs before parsing happens. */
    public List<String> declaredSamplerNames() {
        return declaredSamplerNames;
    }

    @Override
    protected void parseWithLevel(AssetsFiles resources, RegistryOps<JsonElement> ops, HolderLookup.Provider access) {
        for (var j : parseEnabledJsons(resources.jsons(), ops)) {
            if (j == null) continue;
            Viewpoint vp = j.getValue();
            if (!vp.isSamplable()) {
                Polytone.LOGGER.warn("Viewpoint {} declares no depth_sampler, so nothing can ever read it - skipping", j.getKey());
                continue;
            }
            if (vp.hasUniformBlock()) PolytoneBuiltInUniformsSet.register(vp.uniformBlock());
            viewpoints.put(j.getKey(), vp);
        }
    }

    @Override
    protected void applyWithLevel(HolderLookup.Provider access, boolean isLogIn) {
    }

    @Override
    protected void resetWithLevel(boolean logOff) {
        viewpoints.clear();
        // Instances are NOT closed here: parse repopulates viewpoints immediately and surviving ids
        // reuse their textures. Orphans are collected in renderActive().
    }

    /**
     * Binds each viewpoint's depth texture under its {@code depth_sampler} name, only on programs that
     * declare it. Until the viewpoint has rendered, the missing texture stands in, the same way
     * {@code InShadow} does, since a declared binding may not be left empty. The first viewpoint that
     * names a sampler owns it.
     */
    public void bindSamplers(RenderPass pass, Set<String> declaredUniforms) {
        if (viewpoints.isEmpty()) return;
        Set<String> bound = new HashSet<>();
        for (var e : viewpoints.entrySet()) {
            String name = e.getValue().depthSampler();
            if (!declaredUniforms.contains(name) || !bound.add(name)) continue;
            ViewpointInstance inst = instances.get(e.getKey());
            GpuTextureView texture = inst == null ? null : inst.getDepthTexture();
            if (texture == null) {
                texture = Minecraft.getInstance().getTextureManager()
                        .getTexture(TextureManager.INTENTIONAL_MISSING_TEXTURE).getTextureView();
            }
            pass.bindTexture(name, texture, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        }
    }

    /**
     * Binds each viewpoint's {@code uniform_block} to a pass whose program declares it - same gating
     * as the samplers, never a block the program doesn't have. Until that viewpoint has completed a
     * render the block reads as zeros, so {@code Update.x} (rendered) is 0.
     */
    public void bindUniformBlocks(RenderPass pass, Set<String> declaredUniforms) {
        if (viewpoints.isEmpty()) return;
        for (var e : viewpoints.entrySet()) {
            Viewpoint vp = e.getValue();
            if (!vp.hasUniformBlock() || !declaredUniforms.contains(vp.uniformBlock())) continue;
            ViewpointInstance inst = instances.get(e.getKey());
            GpuBufferSlice slice = inst == null ? null : inst.getUniformsSlice();
            pass.setUniform(vp.uniformBlock(), slice != null ? slice : emptyUniformBlock());
        }
    }

    private GpuBufferSlice emptyUniformBlock() {
        if (emptyUniformBlock == null) {
            emptyUniformBlock = RenderSystem.getDevice().createBuffer(() -> "Polytone empty viewpoint UBO",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM, ViewpointUniforms.UBO_SIZE);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                RenderSystem.getDevice().createCommandEncoder()
                        .writeToBuffer(emptyUniformBlock.slice(), stack.calloc(ViewpointUniforms.UBO_SIZE));
            }
        }
        return emptyUniformBlock.slice();
    }

    public boolean isEmpty() {
        return viewpoints.isEmpty();
    }

    /**
     * Renders every viewpoint that is both requested by an active chain and active itself. Called
     * from {@code LevelRenderer.render} HEAD, where no render pass is open (the UBO writes need that) and
     * last frame's section meshes are still current.
     */
    public void renderActive(GpuBufferSlice shaderFog, Camera cam) {
        if (viewpoints.isEmpty()) return;
        var wanted = Polytone.POST_CHAINS.wantedViewpoints();
        if (wanted.isEmpty()) return;

        for (var e : viewpoints.entrySet()) {
            if (!wanted.contains(e.getKey())) continue;
            if (!e.getValue().isActive()) continue;
            instances.computeIfAbsent(e.getKey(), k -> new ViewpointInstance())
                    .renderIfNeeded(e.getValue(), shaderFog, cam);
        }

        // Drop GPU state for viewpoints a reload removed, rather than leaking their textures.
        if (instances.size() > viewpoints.size()) {
            instances.entrySet().removeIf(e -> {
                if (viewpoints.containsKey(e.getKey())) return false;
                e.getValue().close();
                return true;
            });
        }
    }

    public void onClose() {
        for (ViewpointInstance inst : instances.values()) inst.close();
        instances.clear();
        if (emptyUniformBlock != null) {
            emptyUniformBlock.close();
            emptyUniformBlock = null;
        }
    }
}

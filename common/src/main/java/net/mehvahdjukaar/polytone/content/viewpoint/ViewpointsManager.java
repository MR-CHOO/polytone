package net.mehvahdjukaar.polytone.content.viewpoint;

import com.google.gson.JsonElement;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.mehvahdjukaar.polytone.Polytone;
import net.mehvahdjukaar.polytone.common.reloader.ContentManager;
import net.mehvahdjukaar.polytone.common.struc.AssetsFiles;
import net.minecraft.client.Camera;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    // Sampler names of all loaded viewpoints. GlProgramMixin reads this when a program is compiled to
    // allocate a real texture unit, so it MUST be populated before shaders compile on reload -
    // otherwise the sampler silently falls back to unit 0 and reads the scene texture instead.
    private final List<String> samplerNames = new ArrayList<>();

    public ViewpointsManager() {
        super(Spec.of("Viewpoint", () -> Viewpoint.CODEC)
                .wikiPage("Viewpoints")
                .folders("viewpoints"));
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
            viewpoints.put(j.getKey(), vp);
        }
        rebuildSamplerNames();
    }

    @Override
    protected void applyWithLevel(HolderLookup.Provider access, boolean isLogIn) {
    }

    @Override
    protected void resetWithLevel(boolean logOff) {
        viewpoints.clear();
        rebuildSamplerNames();
        // Instances are NOT closed here: parse repopulates viewpoints immediately and surviving ids
        // reuse their textures. Orphans are collected in renderActive().
    }

    private void rebuildSamplerNames() {
        samplerNames.clear();
        for (Viewpoint vp : viewpoints.values()) {
            if (!samplerNames.contains(vp.depthSampler())) samplerNames.add(vp.depthSampler());
        }
    }

    /** Sampler names to register on programs that declare them (see {@code GlProgramMixin}). */
    public List<String> samplerNames() {
        return samplerNames;
    }

    /** The texture behind a sampler name, or null if that viewpoint hasn't rendered yet. */
    @Nullable
    public GpuTextureView textureForSampler(String samplerName) {
        for (var e : viewpoints.entrySet()) {
            if (e.getValue().depthSampler().equals(samplerName)) {
                ViewpointInstance inst = instances.get(e.getKey());
                return inst == null ? null : inst.getDepthTexture();
            }
        }
        return null;
    }

    public boolean isEmpty() {
        return viewpoints.isEmpty();
    }

    /**
     * Renders every viewpoint that is both requested by an active chain and active itself. Called
     * from {@code renderLevel} HEAD, where no render pass is open (the UBO writes need that) and
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
    }
}

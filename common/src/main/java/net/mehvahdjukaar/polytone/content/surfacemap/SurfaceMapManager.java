package net.mehvahdjukaar.polytone.content.surfacemap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.mehvahdjukaar.polytone.Polytone;
import net.mehvahdjukaar.polytone.common.reloader.SingleFileContentManager;
import net.mehvahdjukaar.polytone.common.struc.AssetsFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads {@code polytone/surface_map.json} and keeps {@link SurfaceMap} fed. Registered before
 * POST_CHAINS so its sampler names exist when programs link, the same reason viewpoints are.
 *
 * <p>A layer is only allocated once some linked program declares its sampler name, so shipping the
 * json without a shader that reads it costs nothing.</p>
 */
public class SurfaceMapManager extends SingleFileContentManager<SurfaceMapSettings> {

    private final SurfaceMap map = new SurfaceMap();

    private volatile List<String> samplerNames = List.of();
    private final Set<String> declaredSamplers = new HashSet<>();
    private SurfaceMapSettings parsedSettings = SurfaceMapSettings.NONE;

    public SurfaceMapManager() {
        super("Surface Map", "surface_map.properties", "surface_map.json", Polytone.MOD_ID);
    }

    public SurfaceMap map() {
        return map;
    }

    @Override
    protected AssetsFiles prepare(PreparableReloadListener.SharedState sharedState) {
        AssetsFiles resources = super.prepare(sharedState);
        // The names have to be known BEFORE any program declaring them compiles, and parsing happens
        // later, with the level - so read them straight out of the raw json, as viewpoints do.
        List<String> names = new ArrayList<>();
        for (JsonElement e : resources.jsons().values()) {
            if (e instanceof JsonObject obj && obj.get("heights") instanceof JsonObject h) {
                for (String name : h.keySet()) {
                    if (!name.isEmpty() && !names.contains(name)) names.add(name);
                }
            }
        }
        samplerNames = List.copyOf(names);
        return resources;
    }

    /** Sampler names any surface_map.json declares, for registering on programs before parsing happens. */
    public List<String> samplerNames() {
        return samplerNames;
    }

    public void onSamplerDeclared(String name) {
        if (samplerNames.contains(name)) declaredSamplers.add(name);
    }

    @Override
    protected void parseWithLevel(AssetsFiles resources, RegistryOps<JsonElement> ops, HolderLookup.Provider access) {
        SurfaceMapSettings result = SurfaceMapSettings.NONE;
        for (var entry : resources.jsons().entrySet()) {
            try {
                result = SurfaceMapSettings.CODEC.parse(ops, entry.getValue()).getOrThrow();
            } catch (Exception e) {
                Polytone.LOGGER.error("Failed to parse surface_map.json in file {}", entry.getKey(), e);
            }
        }
        this.parsedSettings = result;
    }

    @Override
    protected void applyWithLevel(HolderLookup.Provider access, boolean isLogIn) {
        map.setSettings(parsedSettings);
    }

    @Override
    protected void resetWithLevel(boolean logOff) {
        this.parsedSettings = SurfaceMapSettings.NONE;
        map.setSettings(SurfaceMapSettings.NONE);
    }

    /** From {@code LevelRenderer.render} HEAD, where no render pass is open. */
    public void update(ClientLevel level, Vec3 camPos) {
        if (map.isEmpty() || declaredSamplers.isEmpty()) return;
        int renderDistance = Minecraft.getInstance().options.renderDistance().get();
        map.update(level, camPos, renderDistance, List.copyOf(declaredSamplers));
    }

    public void markChunkDirty(int chunkX, int chunkZ) {
        map.markChunkDirty(chunkX, chunkZ);
    }

    public void markColumnDirty(BlockPos pos) {
        map.markColumnDirty(pos);
    }

    public boolean hasDeclaredSamplers() {
        return !declaredSamplers.isEmpty();
    }

    /**
     * Binds each layer under the sampler name the pack gave it, only on programs that declare it. A layer
     * that has not been allocated yet stands in with the missing texture, as {@code InShadow} does.
     */
    public void bindSamplers(RenderPass pass, Set<String> declaredUniforms) {
        if (samplerNames.isEmpty()) return;
        for (String name : samplerNames) {
            if (!declaredUniforms.contains(name)) continue;
            GpuTextureView texture = map.texture(name);
            if (texture == null) {
                texture = Minecraft.getInstance().getTextureManager()
                        .getTexture(TextureManager.INTENTIONAL_MISSING_TEXTURE).getTextureView();
            }
            pass.bindTexture(name, texture, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        }
    }

    public void onClose() {
        map.close();
    }

    public Map<String, SurfaceMapSettings.HeightLayer> heightLayers() {
        return parsedSettings.heights();
    }
}

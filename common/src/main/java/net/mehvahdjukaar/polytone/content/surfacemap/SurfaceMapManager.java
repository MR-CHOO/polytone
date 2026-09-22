package net.mehvahdjukaar.polytone.content.surfacemap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.mehvahdjukaar.polytone.Polytone;
import net.mehvahdjukaar.polytone.common.reloader.SingleFileContentManager;
import net.mehvahdjukaar.polytone.common.struc.AssetsFiles;
import net.mehvahdjukaar.polytone.content.shaders.PolytoneBuiltInUniformsSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryStack;

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
    private GpuBuffer emptyPalette = null;
    private DynamicTexture emptyLayer = null;

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
            if (!(e instanceof JsonObject obj)) continue;
            if (obj.get("heights") instanceof JsonObject h) {
                for (String name : h.keySet()) {
                    if (!name.isEmpty() && !names.contains(name)) names.add(name);
                }
            }
            if (obj.has("biome")) {
                if (!names.contains(SurfaceMap.BIOME_SAMPLER)) names.add(SurfaceMap.BIOME_SAMPLER);
                PolytoneBuiltInUniformsSet.register(SurfaceBiomePalette.UBO_NAME);
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
        // MERGED, not last-wins. SingleFileContentManager hands over one file per namespace and its own
        // comment says it does not merge them, which is right for content keyed by file name and wrong
        // here: there is ONE map shared by every pack, so taking the last file silently deletes the
        // layers and the coverage every other pack asked for - and which file is last is HashMap order.
        // Enabling a second pack would then change what the first one sees.
        SurfaceMapSettings result = SurfaceMapSettings.NONE;
        for (var entry : resources.jsons().entrySet()) {
            try {
                result = result.mergedWith(SurfaceMapSettings.CODEC.parse(ops, entry.getValue()).getOrThrow(),
                        entry.getKey());
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
    public void update(ClientLevel level, Vec3 camPos, float partialTick) {
        if (map.isEmpty() || declaredSamplers.isEmpty()) return;
        int renderDistance = Minecraft.getInstance().options.renderDistance().get();
        map.update(level, camPos, partialTick, renderDistance, List.copyOf(declaredSamplers));
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
     * that has not been allocated yet stands in with {@link #emptyLayer()}.
     */
    public void bindSamplers(RenderPass pass, Set<String> declaredUniforms) {
        if (samplerNames.isEmpty()) return;
        for (String name : samplerNames) {
            if (!declaredUniforms.contains(name)) continue;
            GpuTextureView texture = map.texture(name);
            if (texture == null) texture = emptyLayer();
            pass.bindTexture(name, texture, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        }
    }

    /**
     * Binds the biome palette to a pass whose program declares it; zeros until the first evaluation.
     *
     * <p>A declared block MUST be given a buffer, which is why the empty one exists. There is always
     * at least one frame where the palette does not: the layer is only allocated once a program
     * declares its sampler, and that declaration happens at link time, DURING the frame — after the
     * update at {@code LevelRenderer.render} HEAD has already run and returned early. So the first
     * frame a chain reading the map is active is guaranteed to reach here with a null slice, and
     * leaving the block unbound is a render-pass error rather than a shader reading zeros. Both the
     * shadow map and the viewpoints already stand in an empty block for exactly this reason.</p>
     */
    public void bindUniformBlocks(RenderPass pass, Set<String> declaredUniforms) {
        if (!declaredUniforms.contains(SurfaceBiomePalette.UBO_NAME)) return;
        GpuBufferSlice palette = map.paletteSlice();
        pass.setUniform(SurfaceBiomePalette.UBO_NAME, palette != null ? palette : emptyPalette());
    }

    /** Zeros, so a shader sees "slots in use = 0" and falls back to the camera path on its own. */
    private GpuBufferSlice emptyPalette() {
        if (emptyPalette == null) {
            emptyPalette = RenderSystem.getDevice().createBuffer(() -> "Polytone empty surface biome palette",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM, SurfaceBiomePalette.UBO_SIZE);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                RenderSystem.getDevice().createCommandEncoder()
                        .writeToBuffer(emptyPalette.slice(), stack.calloc(SurfaceBiomePalette.UBO_SIZE));
            }
        }
        return emptyPalette.slice();
    }

    /**
     * Stands in for a layer that has no texture yet, on the same first frame {@link #emptyPalette()}
     * covers - a program declares its sampler at link time, after the update that would allocate it has
     * already run.
     *
     * <p>Deliberately NOT the missing texture the shadow map and the viewpoints stand in. Every layer
     * here says "no data" with alpha 0, and the missing texture is opaque magenta: a pack doing the
     * documented alpha test would read it as real data, then decode its red channel as palette slot 248
     * or as a height of 63000 blocks. Zeros are the only stand-in that the contract survives.</p>
     */
    private GpuTextureView emptyLayer() {
        if (emptyLayer == null) {
            emptyLayer = new DynamicTexture(() -> "Polytone empty surface map layer", 1, 1, true);
            emptyLayer.upload();
        }
        return emptyLayer.getTextureView();
    }

    public void onClose() {
        map.close();
        if (emptyPalette != null) {
            emptyPalette.close();
            emptyPalette = null;
        }
        if (emptyLayer != null) {
            emptyLayer.close();
            emptyLayer = null;
        }
    }

    public Map<String, SurfaceMapSettings.HeightLayer> heightLayers() {
        return parsedSettings.heights();
    }
}

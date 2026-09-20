package net.mehvahdjukaar.polytone.content.surfacemap;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.mehvahdjukaar.polytone.Polytone;
import net.minecraft.client.multiplayer.ClientLevel;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The live state of the surface map: one texture per layer, each a window of the world locked to the
 * block grid and addressed toroidally, so walking never shifts what is already stored - only cells
 * entering the window, and chunks that changed, are written again.
 *
 * <p>Filled from the client's own heightmaps, which arrive with the chunk and are kept current as
 * blocks change, so this is a read, not a render.</p>
 */
public class SurfaceMap implements AutoCloseable {

    // chunks written per layer per frame while catching up; joining at render distance 32 is ~4500 chunks
    private static final int FILL_BUDGET = 48;

    private final Map<String, HeightTexture> heights = new HashMap<>();
    private SurfaceMapSettings settings = SurfaceMapSettings.NONE;
    private boolean layersStale = true;
    @Nullable
    private ClientLevel lastLevel = null;
    private List<String> lastWanted = List.of();

    public void setSettings(SurfaceMapSettings settings) {
        this.settings = settings;
        this.layersStale = true;
    }

    public SurfaceMapSettings settings() {
        return settings;
    }

    /** The texture a shader declaring this sampler name reads, or null when nothing provides it. */
    @Nullable
    public GpuTextureView texture(String samplerName) {
        HeightTexture layer = heights.get(samplerName);
        return layer == null ? null : layer.view();
    }

    public boolean isEmpty() {
        return settings.isEmpty();
    }

    /** Called once per frame, with no render pass open. */
    public void update(ClientLevel level, Vec3 camPos, int renderDistanceChunks, List<String> wantedSamplers) {
        if (settings.isEmpty()) {
            if (!heights.isEmpty()) close();
            return;
        }
        // also when the wanted set grows: a program may declare a sampler after the first frame
        if (layersStale || level != lastLevel || !wantedSamplers.equals(lastWanted)) {
            rebuildLayers(level, renderDistanceChunks, wantedSamplers);
            lastLevel = level;
            lastWanted = List.copyOf(wantedSamplers);
            layersStale = false;
        }
        int camX = Mth.floor(camPos.x);
        int camZ = Mth.floor(camPos.z);
        for (HeightTexture layer : heights.values()) {
            layer.update(level, camX, camZ);
        }
    }

    // A layer nothing declares is never allocated, so a pack that ships the json but no shader pays nothing
    private void rebuildLayers(ClientLevel level, int renderDistanceChunks, List<String> wantedSamplers) {
        close();
        settings.heights().forEach((sampler, layer) -> {
            if (!wantedSamplers.contains(sampler)) return;
            int radius = layer.coverage().orElse(settings.coverage()).resolve(renderDistanceChunks);
            heights.put(sampler, new HeightTexture(sampler, layer.heightmap(), radius, level.getMinY()));
        });
        if (!heights.isEmpty()) {
            Polytone.LOGGER.info("Surface map: {}", heights.values().stream()
                    .map(HeightTexture::describe).toList());
        }
    }

    public void markChunkDirty(int chunkX, int chunkZ) {
        for (HeightTexture layer : heights.values()) layer.markDirty(chunkX, chunkZ);
    }

    public void markColumnDirty(BlockPos pos) {
        markChunkDirty(SectionPosX(pos.getX()), SectionPosX(pos.getZ()));
    }

    private static int SectionPosX(int block) {
        return block >> 4;
    }

    @Override
    public void close() {
        for (HeightTexture layer : heights.values()) layer.close();
        heights.clear();
        lastLevel = null;
    }

    /**
     * One height layer. R = low byte, G = high byte of (height - minY), A = 255 once written, so a
     * shader can tell "nothing here yet" from "height 0".
     */
    private static class HeightTexture {
        private final String sampler;
        private final Heightmap.Types type;
        private final int size;      // texels per side, a multiple of 16 so a chunk never straddles the wrap
        private final int minY;
        private final DynamicTexture texture;
        private final LongOpenHashSet dirty = new LongOpenHashSet();

        // one chunk's worth of texels, blitted on its own so a single changed column never re-uploads the
        // whole window (a render-distance-sized layer is megabytes)
        private final NativeImage scratch = new NativeImage(16, 16, true);

        private int originX = Integer.MIN_VALUE; // window min block
        private int originZ = Integer.MIN_VALUE;

        HeightTexture(String sampler, Heightmap.Types type, int radiusBlocks, int minY) {
            this.sampler = sampler;
            this.type = type;
            this.size = Mth.roundToward(radiusBlocks * 2, 16);
            this.minY = minY;
            this.texture = new DynamicTexture(() -> "Polytone surface map " + sampler, size, size, true);
            this.texture.upload(); // zeroed: alpha 0 everywhere means "nothing written here yet"
        }

        String describe() {
            return sampler + "=" + type.getSerializedName() + " " + size + "x" + size;
        }

        GpuTextureView view() {
            return texture.getTextureView();
        }

        void markDirty(int chunkX, int chunkZ) {
            if (!contains(chunkX << 4, chunkZ << 4)) return;
            dirty.add(ChunkPos.pack(chunkX, chunkZ));
        }

        private boolean contains(int blockX, int blockZ) {
            return blockX >= originX && blockX < originX + size && blockZ >= originZ && blockZ < originZ + size;
        }

        void update(ClientLevel level, int camX, int camZ) {
            moveWindow(camX, camZ);
            fill(level);
        }

        // The window is locked to the block grid: it only ever jumps in whole chunks, and only the chunks
        // that just entered are rewritten. Everything already stored keeps its texel.
        private void moveWindow(int camX, int camZ) {
            int newOriginX = (camX - size / 2) & ~15;
            int newOriginZ = (camZ - size / 2) & ~15;
            if (newOriginX == originX && newOriginZ == originZ) return;

            int oldX = originX;
            int oldZ = originZ;
            boolean hadWindow = oldX != Integer.MIN_VALUE;
            originX = newOriginX;
            originZ = newOriginZ;
            for (int bx = newOriginX; bx < newOriginX + size; bx += 16) {
                for (int bz = newOriginZ; bz < newOriginZ + size; bz += 16) {
                    boolean wasInside = hadWindow && bx >= oldX && bx < oldX + size && bz >= oldZ && bz < oldZ + size;
                    if (!wasInside) dirty.add(ChunkPos.pack(bx >> 4, bz >> 4));
                }
            }
        }

        private void fill(ClientLevel level) {
            if (dirty.isEmpty()) return;
            NativeImage image = texture.getPixels();
            if (image == null) return;
            int done = 0;
            LongIterator it = dirty.iterator();
            List<Long> written = new ArrayList<>();
            while (it.hasNext() && done < FILL_BUDGET) {
                long key = it.nextLong();
                int cx = ChunkPos.getX(key);
                int cz = ChunkPos.getZ(key);
                if (!contains(cx << 4, cz << 4)) {
                    written.add(key); // left the window while queued
                    continue;
                }
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue; // not loaded yet; try again next frame
                writeChunk(image, chunk, cx, cz);
                written.add(key);
                done++;
            }
            for (long key : written) dirty.remove(key);
        }

        private void writeChunk(NativeImage image, LevelChunk chunk, int chunkX, int chunkZ) {
            int destX = Math.floorMod(chunkX << 4, size);
            int destZ = Math.floorMod(chunkZ << 4, size);
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    int blockX = (chunkX << 4) + dx;
                    int blockZ = (chunkZ << 4) + dz;
                    int h = Mth.clamp(chunk.getHeight(type, blockX, blockZ) - minY, 0, 0xFFFF);
                    // NativeImage packs a pixel as 0xAABBGGRR
                    int pixel = 0xFF000000 | ((h >> 8) << 8) | (h & 0xFF);
                    image.setPixel(destX + dx, destZ + dz, pixel);
                    scratch.setPixel(dx, dz, pixel);
                }
            }
            // the window is chunk-aligned and its side is a multiple of 16, so a chunk never straddles the wrap
            RenderSystem.getDevice().createCommandEncoder()
                    .writeToTexture(texture.getTexture(), scratch, 0, 0, destX, destZ);
        }

        void close() {
            texture.close();
            scratch.close();
        }
    }
}

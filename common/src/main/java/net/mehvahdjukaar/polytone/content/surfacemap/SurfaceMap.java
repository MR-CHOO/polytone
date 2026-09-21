package net.mehvahdjukaar.polytone.content.surfacemap;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.mehvahdjukaar.polytone.Polytone;
import net.minecraft.client.multiplayer.ClientLevel;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
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

    public static final String BIOME_SAMPLER = "InSurfaceBiome";

    private final Map<String, HeightTexture> heights = new HashMap<>();
    private final SurfaceBiomePalette palette = new SurfaceBiomePalette();
    @Nullable
    private BiomeTexture biome = null;
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
        if (BIOME_SAMPLER.equals(samplerName)) return biome == null ? null : biome.view();
        HeightTexture layer = heights.get(samplerName);
        return layer == null ? null : layer.view();
    }

    /** The palette block, or null until the biome layer has been built and evaluated once. */
    @Nullable
    public GpuBufferSlice paletteSlice() {
        return biome == null ? null : palette.slice();
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
        if (biome != null) {
            biome.update(level, camX, camZ, palette);
            // every frame: a palette value can be an expression, and biome_modifiers may lerp with rain
            settings.biome().ifPresent(layer -> palette.update(level, camPos, layer.attributes(),
                    biome.originX, biome.originZ, BiomeTexture.TEXEL_SIZE, biome.size));
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
        settings.biome().ifPresent(layer -> {
            if (!wantedSamplers.contains(BIOME_SAMPLER)) return;
            biome = new BiomeTexture(layer.coverage().orElse(settings.coverage()).resolve(renderDistanceChunks));
        });
        List<String> layers = new ArrayList<>();
        heights.values().forEach(l -> layers.add(l.describe()));
        if (biome != null) layers.add(biome.describe());
        if (!layers.isEmpty()) Polytone.LOGGER.info("Surface map: {}", layers);
    }

    public void markChunkDirty(int chunkX, int chunkZ) {
        for (HeightTexture layer : heights.values()) layer.markDirty(chunkX, chunkZ);
        if (biome != null) biome.markDirty(chunkX, chunkZ);
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
        if (biome != null) {
            biome.close();
            biome = null;
        }
        palette.close();
        lastLevel = null;
    }

    /**
     * The biome layer: R = the cell's palette slot, 0 until written, 255 when the window held more than
     * the palette can carry. One texel per stored biome cell, so this is exact, not sampled.
     */
    private static class BiomeTexture {
        static final int TEXEL_SIZE = 4;   // vanilla stores one biome per 4x4x4 cell
        private static final int CELLS_PER_CHUNK = 16 / TEXEL_SIZE;

        private final int size;            // texels per side, a multiple of 4 so a chunk covers whole texels
        private final DynamicTexture texture;
        private final NativeImage scratch = new NativeImage(CELLS_PER_CHUNK, CELLS_PER_CHUNK, true);
        private final LongOpenHashSet dirty = new LongOpenHashSet();

        private int originX = Integer.MIN_VALUE;  // window min block
        private int originZ = Integer.MIN_VALUE;

        BiomeTexture(int radiusBlocks) {
            this.size = Mth.roundToward(radiusBlocks * 2 / TEXEL_SIZE, CELLS_PER_CHUNK);
            this.texture = new DynamicTexture(() -> "Polytone surface map biome", size, size, true);
            this.texture.upload();
        }

        String describe() {
            return BIOME_SAMPLER + "=biome " + size + "x" + size;
        }

        GpuTextureView view() {
            return texture.getTextureView();
        }

        void markDirty(int chunkX, int chunkZ) {
            if (!contains(chunkX << 4, chunkZ << 4)) return;
            dirty.add(ChunkPos.pack(chunkX, chunkZ));
        }

        private boolean contains(int blockX, int blockZ) {
            int span = size * TEXEL_SIZE;
            return blockX >= originX && blockX < originX + span && blockZ >= originZ && blockZ < originZ + span;
        }

        void update(ClientLevel level, int camX, int camZ, SurfaceBiomePalette palette) {
            moveWindow(camX, camZ);
            fill(level, palette);
        }

        private void moveWindow(int camX, int camZ) {
            int span = size * TEXEL_SIZE;
            int newOriginX = (camX - span / 2) & ~15;
            int newOriginZ = (camZ - span / 2) & ~15;
            if (newOriginX == originX && newOriginZ == originZ) return;

            int oldX = originX;
            int oldZ = originZ;
            boolean hadWindow = oldX != Integer.MIN_VALUE;
            originX = newOriginX;
            originZ = newOriginZ;
            // A chunk entering the window inherits the texels of the one that just left - that is the toroid.
            // Left alone they would keep the OLD place's slots at full alpha until the new chunk streams in,
            // which a shader cannot tell from real data, and which also pins the old place's palette slots
            // so retainOnly cannot free them. So an entering chunk reads "not written yet" from the moment it
            // enters. A jump of a whole span (a teleport) keeps nothing, so it is one wipe, not a blit per chunk.
            boolean overlaps = hadWindow && Math.abs(newOriginX - oldX) < span && Math.abs(newOriginZ - oldZ) < span;
            if (hadWindow && !overlaps) clearAll();
            for (int bx = newOriginX; bx < newOriginX + span; bx += 16) {
                for (int bz = newOriginZ; bz < newOriginZ + span; bz += 16) {
                    boolean wasInside = hadWindow && bx >= oldX && bx < oldX + span && bz >= oldZ && bz < oldZ + span;
                    if (wasInside) continue;
                    dirty.add(ChunkPos.pack(bx >> 4, bz >> 4));
                    if (overlaps) clearChunk(bx >> 4, bz >> 4);
                }
            }
        }

        private void clearAll() {
            NativeImage image = texture.getPixels();
            if (image == null) return;
            image.fillRect(0, 0, size, size, 0);
            texture.upload();
        }

        // scratch is the one-chunk staging image writeChunk uploads through; zeroed, it uploads a blank chunk
        private void clearChunk(int chunkX, int chunkZ) {
            NativeImage image = texture.getPixels();
            if (image == null) return;
            int destX = Math.floorMod((chunkX << 4) / TEXEL_SIZE, size);
            int destZ = Math.floorMod((chunkZ << 4) / TEXEL_SIZE, size);
            image.fillRect(destX, destZ, CELLS_PER_CHUNK, CELLS_PER_CHUNK, 0);
            scratch.fillRect(0, 0, CELLS_PER_CHUNK, CELLS_PER_CHUNK, 0);
            RenderSystem.getDevice().createCommandEncoder()
                    .writeToTexture(texture.getTexture(), scratch, 0, 0, destX, destZ);
        }

        private void fill(ClientLevel level, SurfaceBiomePalette palette) {
            if (dirty.isEmpty()) return;
            NativeImage image = texture.getPixels();
            if (image == null) return;
            // The palette has no idea which of its slots still matter - this texture is the only record.
            // Sweep it before the last slot goes, or a long walk fills all 63 with biomes that left the
            // window and every cell after that reads as overflow.
            if (palette.isFull()) palette.retainOnly(usedSlots(image));
            int done = 0;
            List<Long> written = new ArrayList<>();
            LongIterator it = dirty.iterator();
            while (it.hasNext() && done < FILL_BUDGET) {
                long key = it.nextLong();
                int cx = ChunkPos.getX(key);
                int cz = ChunkPos.getZ(key);
                if (!contains(cx << 4, cz << 4)) {
                    written.add(key);
                    continue;
                }
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                writeChunk(image, chunk, cx, cz, palette);
                written.add(key);
                done++;
            }
            for (long key : written) dirty.remove(key);
        }

        // Every slot any texel still holds. Cheap enough to be worth no cleverness: 136x136 is 18k reads
        // and it only runs when the palette is one biome from full.
        private IntSet usedSlots(NativeImage image) {
            IntOpenHashSet used = new IntOpenHashSet();
            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    int pixel = image.getPixel(x, z);        // ARGB, and the slot lives in RED
                    if ((pixel >>> 24) == 0) continue;       // never written
                    used.add((pixel >> 16) & 0xFF);
                }
            }
            return used;
        }

        private void writeChunk(NativeImage image, LevelChunk chunk, int chunkX, int chunkZ,
                                SurfaceBiomePalette palette) {
            int destX = Math.floorMod((chunkX << 4) / TEXEL_SIZE, size);
            int destZ = Math.floorMod((chunkZ << 4) / TEXEL_SIZE, size);
            for (int cellX = 0; cellX < CELLS_PER_CHUNK; cellX++) {
                for (int cellZ = 0; cellZ < CELLS_PER_CHUNK; cellZ++) {
                    int blockX = (chunkX << 4) + cellX * TEXEL_SIZE + TEXEL_SIZE / 2;
                    int blockZ = (chunkZ << 4) + cellZ * TEXEL_SIZE + TEXEL_SIZE / 2;
                    // Just above the local ground: above the surface vanilla's biomes are column-constant,
                    // so this is the biome of the air a consumer is asking about.
                    int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ) + 1;
                    int slot = palette.slotFor(chunk.getNoiseBiome(
                            QuartPos.fromBlock(blockX), QuartPos.fromBlock(y), QuartPos.fromBlock(blockZ)));
                    int pixel = 0xFF000000 | ((slot & 0xFF) << 16);  // ARGB: the slot lives in RED
                    image.setPixel(destX + cellX, destZ + cellZ, pixel);
                    scratch.setPixel(cellX, cellZ, pixel);
                }
            }
            RenderSystem.getDevice().createCommandEncoder()
                    .writeToTexture(texture.getTexture(), scratch, 0, 0, destX, destZ);
        }

        void close() {
            texture.close();
            scratch.close();
        }
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
            // entering chunks read "not written yet" until they are - see BiomeTexture.moveWindow
            boolean overlaps = hadWindow && Math.abs(newOriginX - oldX) < size && Math.abs(newOriginZ - oldZ) < size;
            if (hadWindow && !overlaps) clearAll();
            for (int bx = newOriginX; bx < newOriginX + size; bx += 16) {
                for (int bz = newOriginZ; bz < newOriginZ + size; bz += 16) {
                    boolean wasInside = hadWindow && bx >= oldX && bx < oldX + size && bz >= oldZ && bz < oldZ + size;
                    if (wasInside) continue;
                    dirty.add(ChunkPos.pack(bx >> 4, bz >> 4));
                    if (overlaps) clearChunk(bx >> 4, bz >> 4);
                }
            }
        }

        private void clearAll() {
            NativeImage image = texture.getPixels();
            if (image == null) return;
            image.fillRect(0, 0, size, size, 0);
            texture.upload();
        }

        private void clearChunk(int chunkX, int chunkZ) {
            NativeImage image = texture.getPixels();
            if (image == null) return;
            int destX = Math.floorMod(chunkX << 4, size);
            int destZ = Math.floorMod(chunkZ << 4, size);
            image.fillRect(destX, destZ, 16, 16, 0);
            scratch.fillRect(0, 0, 16, 16, 0);
            RenderSystem.getDevice().createCommandEncoder()
                    .writeToTexture(texture.getTexture(), scratch, 0, 0, destX, destZ);
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
                    // setPixel takes ARGB and does the swap to the native layout itself, so the low
                    // byte goes in RED and the high byte in GREEN. Same packing as GpuParticleHeightmap.
                    int pixel = 0xFF000000 | ((h & 0xFF) << 16) | ((h >> 8) << 8);
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

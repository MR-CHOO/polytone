package net.mehvahdjukaar.polytone.content.colormap;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// inherit_nearest_biome: the listed biomes are sampled as the nearest surface biome outside the list,
// probed on the 4 block biome grid just above MOTION_BLOCKING_NO_LEAVES
public final class NearestBiomeInheritance {

    public static final Codec<NearestBiomeInheritance> CODEC = RecordCodecBuilder.create(i -> i.group(
            RegistryCodecs.homogeneousList(Registries.BIOME).fieldOf("biomes").forGetter(c -> c.biomes),
            ExtraCodecs.intRange(4, 128).optionalFieldOf("radius", 32).forGetter(c -> c.radius),
            RegistryCodecs.homogeneousList(Registries.BIOME).optionalFieldOf("skip", HolderSet.empty()).forGetter(c -> c.skip)
    ).apply(i, NearestBiomeInheritance::new));

    private static final Object NONE = new Object();
    // only inheriting cells are stored
    private static final int MAX_CACHED = 1 << 16;

    private final HolderSet<Biome> biomes;
    private final int radius;
    private final HolderSet<Biome> skip;
    // dx, dz interleaved, nearest first, ties broken the same way on every thread
    private final int[] offsets;

    // resolved lazily: tags are bound by the time anything samples a colour, not necessarily at parse
    private volatile Set<Biome> inheriting;
    private volatile Set<Biome> skipped;
    private volatile LevelCache cache;

    private record LevelCache(ClientLevel level, ConcurrentHashMap<Long, Object> cells) {
    }

    public NearestBiomeInheritance(HolderSet<Biome> biomes, int radius, HolderSet<Biome> skip) {
        this.biomes = biomes;
        this.radius = radius;
        this.skip = skip;

        int r = Mth.ceil(radius / 4f);
        List<int[]> cells = new ArrayList<>();
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dz * dz <= r * r) cells.add(new int[]{dx, dz});
            }
        }
        cells.sort((a, b) -> {
            int d = (a[0] * a[0] + a[1] * a[1]) - (b[0] * b[0] + b[1] * b[1]);
            if (d != 0) return d;
            return a[1] != b[1] ? Integer.compare(a[1], b[1]) : Integer.compare(a[0], b[0]);
        });
        this.offsets = new int[cells.size() * 2];
        for (int k = 0; k < cells.size(); k++) {
            offsets[2 * k] = cells.get(k)[0];
            offsets[2 * k + 1] = cells.get(k)[1];
        }
    }

    public Biome resolve(Biome biome, Vec3 pos) {
        if (!inheriting().contains(biome)) return biome;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return biome;

        LevelCache c = this.cache;
        if (c == null || c.level != level) {
            c = new LevelCache(level, new ConcurrentHashMap<>());
            this.cache = c;
        }

        int qx = QuartPos.fromBlock(Mth.floor(pos.x));
        int qz = QuartPos.fromBlock(Mth.floor(pos.z));
        long key = ChunkPos.pack(qx, qz);
        Object hit = c.cells.get(key);
        if (hit != null) return hit == NONE ? biome : (Biome) hit;

        Set<Biome> inheriting = inheriting();
        Set<Biome> skipped = skipped();
        boolean complete = true;
        Object found = NONE;
        for (int k = 0; k < offsets.length; k += 2) {
            int cellX = qx + offsets[k];
            int cellZ = qz + offsets[k + 1];
            int blockX = QuartPos.toBlock(cellX) + 2;
            int blockZ = QuartPos.toBlock(cellZ) + 2;
            LevelChunk chunk = level.getChunkSource().getChunk(SectionPos.blockToSectionCoord(blockX),
                    SectionPos.blockToSectionCoord(blockZ), ChunkStatus.FULL, false);
            if (chunk == null) {
                complete = false;
                continue;
            }
            int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ) + 1;
            Biome candidate = chunk.getNoiseBiome(cellX, QuartPos.fromBlock(y), cellZ).value();
            if (inheriting.contains(candidate) || skipped.contains(candidate)) continue;
            found = candidate;
            break;
        }
        if (complete) {
            if (c.cells.size() > MAX_CACHED) c.cells.clear();
            c.cells.put(key, found);
        }
        return found == NONE ? biome : (Biome) found;
    }

    private Set<Biome> inheriting() {
        Set<Biome> s = inheriting;
        if (s == null) inheriting = s = identitySet(biomes);
        return s;
    }

    private Set<Biome> skipped() {
        Set<Biome> s = skipped;
        if (s == null) skipped = s = identitySet(skip);
        return s;
    }

    private static Set<Biome> identitySet(HolderSet<Biome> set) {
        Set<Biome> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Holder<Biome> h : set) out.add(h.value());
        return out;
    }
}

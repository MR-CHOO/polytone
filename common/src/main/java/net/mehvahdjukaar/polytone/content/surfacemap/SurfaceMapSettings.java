package net.mehvahdjukaar.polytone.content.surfacemap;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.mehvahdjukaar.polytone.Polytone;
import net.minecraft.resources.Identifier;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@code polytone/surface_map.json}: a world-locked map of what is at the ground surface at each XZ,
 * filled from the chunks the client already has. No file means no map and no cost.
 *
 * <pre>
 * {
 *   "coverage": "render_distance",           // or a radius in blocks
 *   "biome": { "attributes": ["minecraft:visual/fog_end_distance"] },
 *   "heights": {
 *     "InSurfaceGround": "motion_blocking_no_leaves",
 *     "InSurfaceCanopy": { "heightmap": "motion_blocking", "coverage": 128 }
 *   }
 * }
 * </pre>
 */
public record SurfaceMapSettings(Coverage coverage, Optional<BiomeLayer> biome, Map<String, HeightLayer> heights) {

    public static final SurfaceMapSettings NONE = new SurfaceMapSettings(Coverage.RENDER_DISTANCE, Optional.empty(), Map.of());

    public static final Codec<SurfaceMapSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
            Coverage.CODEC.optionalFieldOf("coverage", Coverage.RENDER_DISTANCE).forGetter(SurfaceMapSettings::coverage),
            BiomeLayer.CODEC.optionalFieldOf("biome").forGetter(SurfaceMapSettings::biome),
            Codec.unboundedMap(Codec.STRING, HeightLayer.CODEC).optionalFieldOf("heights", Map.of())
                    .forGetter(SurfaceMapSettings::heights)
    ).apply(i, SurfaceMapSettings::new));

    public boolean isEmpty() {
        return biome.isEmpty() && heights.isEmpty();
    }

    /**
     * Folds another pack's file into this one. Every pack shares a single map, so a file adds to it
     * rather than replacing it: height layers union by sampler name, and where two files ask for
     * different amounts of the same thing the larger wins - a window that is too small is a wrong
     * answer, while one that is too big only costs fill time.
     */
    public SurfaceMapSettings mergedWith(SurfaceMapSettings other, Identifier from) {
        if (this == NONE) return other;   // NONE is "nothing asked yet", not a request for render_distance
        if (other == NONE) return this;
        Map<String, HeightLayer> merged = new LinkedHashMap<>(this.heights);
        merged.putAll(other.heights);     // same sampler named twice: one layer, later file's heightmap
        return new SurfaceMapSettings(coverage.mergedWith(other.coverage),
                mergeBiome(this.biome, other.biome, from), Map.copyOf(merged));
    }

    private static Optional<BiomeLayer> mergeBiome(Optional<BiomeLayer> first, Optional<BiomeLayer> second,
                                                   Identifier from) {
        if (first.isEmpty()) return second;
        if (second.isEmpty()) return first;
        List<EnvironmentAttribute<?>> attributes = new ArrayList<>(first.get().attributes());
        for (EnvironmentAttribute<?> a : second.get().attributes()) {
            if (!attributes.contains(a)) attributes.add(a);
        }
        if (attributes.size() > BiomeLayer.MAX_ATTRIBUTES) {
            Polytone.LOGGER.error("Surface map: the packs together ask for {} biome attributes and the "
                            + "palette carries {}. Dropping the ones {} added last.",
                    attributes.size(), BiomeLayer.MAX_ATTRIBUTES, from);
            attributes = attributes.subList(0, BiomeLayer.MAX_ATTRIBUTES);
        }
        // A shader addresses the palette BY POSITION, so a union can move another pack's indices under
        // it. Keeping the first file's order leaves that file correct whatever happens; the second is
        // only safe when its own list survives as a prefix, so say so out loud when it does not.
        List<EnvironmentAttribute<?>> theirs = second.get().attributes();
        if (!attributes.subList(0, Math.min(attributes.size(), theirs.size())).equals(theirs)) {
            Polytone.LOGGER.warn("Surface map: {} lists its biome attributes in an order another pack's "
                    + "file already fixed, so its own palette indices have shifted. Attributes are read by "
                    + "position - list them in the same order, or ship only one surface_map.json.", from);
        }
        return Optional.of(new BiomeLayer(List.copyOf(attributes),
                first.get().coverage().isPresent() || second.get().coverage().isPresent()
                        ? Optional.of(first.get().coverage().orElse(Coverage.RENDER_DISTANCE)
                        .mergedWith(second.get().coverage().orElse(Coverage.RENDER_DISTANCE)))
                        : Optional.empty()));
    }

    /** Half-width of a layer's window: the client's render distance, or a fixed radius in blocks. */
    public record Coverage(Optional<Integer> blocks, int floorBlocks) {
        public static final Coverage RENDER_DISTANCE = new Coverage(Optional.empty(), 0);

        // floorBlocks only ever comes from a merge, so it is not part of the json
        public Coverage(Optional<Integer> blocks) {
            this(blocks, 0);
        }

        // one field, so "use the render distance" and "use 256 blocks" cannot contradict each other
        public static final Codec<Coverage> CODEC = Codec.either(Codec.STRING, Codec.intRange(16, 4096))
                .comapFlatMap(e -> e.map(
                                s -> s.equals("render_distance") ? DataResult.success(RENDER_DISTANCE)
                                        : DataResult.error(() -> "Coverage must be \"render_distance\" or a radius in blocks"),
                                b -> DataResult.success(new Coverage(Optional.of(b)))),
                        c -> c.blocks().<Either<String, Integer>>map(Either::right)
                                .orElseGet(() -> Either.left("render_distance")));

        public int resolve(int renderDistanceChunks) {
            // EXACTLY the render distance, never a chunk more. The client holds a square of chunks
            // centred on the player's OWN chunk, so the loaded area reaches at least renderDistance * 16
            // blocks past the player in every direction but is not guaranteed one block further. A radius
            // of (renderDistance + 1) * 16 therefore always includes a ring of chunks that never load, and
            // those cells never get written - a permanent unfilled edge on the map rather than a wrong one.
            return Math.max(floorBlocks, blocks.orElseGet(() -> renderDistanceChunks * 16));
        }

        /**
         * The larger of the two. {@code render_distance} and a fixed radius cannot be compared until the
         * render distance is known, so merging them keeps the render distance and carries the radius as a
         * floor; {@link #resolve} takes the max at the point where it finally can.
         */
        public Coverage mergedWith(Coverage other) {
            int floor = Math.max(floorBlocks, other.floorBlocks);
            if (blocks.isEmpty() || other.blocks.isEmpty()) {
                return new Coverage(Optional.empty(),
                        Math.max(floor, Math.max(blocks.orElse(0), other.blocks.orElse(0))));
            }
            return new Coverage(Optional.of(Math.max(blocks.get(), other.blocks.get())), floor);
        }
    }

    /** @param attributes what the palette carries per biome, in this order */
    public record BiomeLayer(List<EnvironmentAttribute<?>> attributes, Optional<Coverage> coverage) {

        public static final int MAX_ATTRIBUTES = 8;

        // Only attributes vanilla blends between biomes make sense per cell; the rest are dimension-wide
        // or gameplay flags, and a pack asking for one is a mistake worth saying out loud.
        private static final Codec<EnvironmentAttribute<?>> ATTRIBUTE_CODEC = EnvironmentAttributes.CODEC
                .validate(a -> !a.isSpatiallyInterpolated()
                        ? DataResult.error(() -> "Environment attribute is not spatially interpolated, so it has no per-biome value")
                        : !SurfaceBiomePalette.isSupported(a)
                        ? DataResult.error(() -> "Environment attribute is not a float, colour or boolean, so it cannot go in the palette")
                        : DataResult.success(a));

        public static final Codec<BiomeLayer> CODEC = RecordCodecBuilder.create(i -> i.group(
                ATTRIBUTE_CODEC.listOf(1, MAX_ATTRIBUTES).fieldOf("attributes").forGetter(BiomeLayer::attributes),
                Coverage.CODEC.optionalFieldOf("coverage").forGetter(BiomeLayer::coverage)
        ).apply(i, BiomeLayer::new));
    }

    /** @param heightmap one of the three the client actually has; the rest never leave the server */
    public record HeightLayer(Heightmap.Types heightmap, Optional<Coverage> coverage) {

        // Vanilla's own codec spells these MOTION_BLOCKING_NO_LEAVES and answers a typo with
        // "Unknown element name". Every other name a Polytone json takes is lowercase, so these are
        // keyed the way a pack would write them, and a wrong one says what the choices are.
        private static final Codec<Heightmap.Types> TYPE_CODEC = Codec.STRING.comapFlatMap(s -> {
            for (Heightmap.Types t : Heightmap.Types.values()) {
                if (t.sendToClient() && t.getSerializedName().equalsIgnoreCase(s)) return DataResult.success(t);
            }
            return DataResult.error(() -> "Unknown heightmap '" + s + "': the client only has " + clientHeightmaps());
        }, t -> t.getSerializedName().toLowerCase(Locale.ROOT));

        private static String clientHeightmaps() {
            return Arrays.stream(Heightmap.Types.values())
                    .filter(Heightmap.Types::sendToClient)
                    .map(t -> t.getSerializedName().toLowerCase(Locale.ROOT))
                    .collect(Collectors.joining(", "));
        }

        private static final Codec<HeightLayer> FULL = RecordCodecBuilder.create(i -> i.group(
                TYPE_CODEC.fieldOf("heightmap").forGetter(HeightLayer::heightmap),
                Coverage.CODEC.optionalFieldOf("coverage").forGetter(HeightLayer::coverage)
        ).apply(i, HeightLayer::new));

        // the bare heightmap name when it needs nothing else
        public static final Codec<HeightLayer> CODEC = Codec.either(TYPE_CODEC, FULL)
                .xmap(e -> e.map(t -> new HeightLayer(t, Optional.empty()), h -> h),
                        h -> h.coverage().isPresent() ? Either.right(h) : Either.left(h.heightmap()));
    }
}

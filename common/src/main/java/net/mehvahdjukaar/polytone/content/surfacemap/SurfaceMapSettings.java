package net.mehvahdjukaar.polytone.content.surfacemap;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /** Half-width of a layer's window: the client's render distance, or a fixed radius in blocks. */
    public record Coverage(Optional<Integer> blocks) {
        public static final Coverage RENDER_DISTANCE = new Coverage(Optional.empty());

        // one field, so "use the render distance" and "use 256 blocks" cannot contradict each other
        public static final Codec<Coverage> CODEC = Codec.either(Codec.STRING, Codec.intRange(16, 4096))
                .comapFlatMap(e -> e.map(
                                s -> s.equals("render_distance") ? DataResult.success(RENDER_DISTANCE)
                                        : DataResult.error(() -> "Coverage must be \"render_distance\" or a radius in blocks"),
                                b -> DataResult.success(new Coverage(Optional.of(b)))),
                        c -> c.blocks().<Either<String, Integer>>map(Either::right)
                                .orElseGet(() -> Either.left("render_distance")));

        public int resolve(int renderDistanceChunks) {
            return blocks.orElseGet(() -> (renderDistanceChunks + 1) * 16);
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

        private static final Codec<Heightmap.Types> TYPE_CODEC = Heightmap.Types.CODEC
                .validate(t -> t.sendToClient() ? DataResult.success(t)
                        : DataResult.error(() -> "Heightmap " + t.getSerializedName() + " is not sent to the client"));

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

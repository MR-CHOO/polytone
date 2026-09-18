package net.mehvahdjukaar.polytone.content.viewpoint;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import net.mehvahdjukaar.codecui.SchemaCodec;
import net.mehvahdjukaar.codecui.SchemaRecord;
import net.mehvahdjukaar.polytone.common.Targets;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a viewpoint captures beyond terrain. Both fields accept {@code true} (everything), {@code false}
 * (nothing - the default) or an object, so the original boolean form keeps working unchanged.
 *
 * <pre>
 * "entities": {
 *   "include": "#minecraft:boat",      // id, #tag, regex or a list; absent = every entity
 *   "exclude": ["minecraft:bat"],      // applied after include
 *   "features": false,                 // drop name tags, held items, armour, skulls, fire, blob shadow
 *   "camera_entity": false             // drop the entity the camera views from (you, usually)
 * },
 * "block_entities": { "include": "#minecraft:signs" }   // matched against the BLOCK, so block tags work
 * </pre>
 *
 * <p>Filters are resolved against the registries once per reload ({@link #resolve}), so the per-frame
 * cost is a set lookup per candidate.</p>
 */
public final class ViewpointFilters {

    private ViewpointFilters() {
    }

    public record EntityFilter(Targets include, Targets exclude, boolean features, boolean cameraEntity) {

        public static final EntityFilter ALL = new EntityFilter(Targets.EMPTY, Targets.EMPTY, true, true);

        private static final SchemaCodec<EntityFilter> OBJECT_CODEC = SchemaRecord.create(EntityFilter.class,
                i -> i.group(
                        i.optional("include", Targets.CODEC, Targets.EMPTY, EntityFilter::include),
                        i.optional("exclude", Targets.CODEC, Targets.EMPTY, EntityFilter::exclude),
                        // Armour, held items and name tags are pure cost for an occlusion-only capture,
                        // and a name tag floating above a mob is the wrong height in a height field.
                        i.optional("features", Codec.BOOL, true, EntityFilter::features),
                        // Default true: reflections want you in them. A top-down height map usually
                        // doesn't want a pillar where the player stands.
                        i.optional("camera_entity", Codec.BOOL, true, EntityFilter::cameraEntity)
                ).apply(i, EntityFilter::new));

        public static final Codec<Optional<EntityFilter>> CODEC = boolOrObject(OBJECT_CODEC, ALL);
    }

    public record BlockEntityFilter(Targets include, Targets exclude) {

        public static final BlockEntityFilter ALL = new BlockEntityFilter(Targets.EMPTY, Targets.EMPTY);

        private static final SchemaCodec<BlockEntityFilter> OBJECT_CODEC = SchemaRecord.create(BlockEntityFilter.class,
                i -> i.group(
                        i.optional("include", Targets.CODEC, Targets.EMPTY, BlockEntityFilter::include),
                        i.optional("exclude", Targets.CODEC, Targets.EMPTY, BlockEntityFilter::exclude)
                ).apply(i, BlockEntityFilter::new));

        public static final Codec<Optional<BlockEntityFilter>> CODEC = boolOrObject(OBJECT_CODEC, ALL);
    }

    // true -> the all-inclusive filter, false -> absent, object -> that filter. Encodes back to the
    // shortest form that round-trips.
    private static <F> Codec<Optional<F>> boolOrObject(Codec<F> objectCodec, F all) {
        return Codec.either(Codec.BOOL, objectCodec).xmap(
                e -> e.map(b -> b ? Optional.of(all) : Optional.empty(), Optional::of),
                o -> o.<Either<Boolean, F>>map(f -> f.equals(all) ? Either.left(true) : Either.right(f))
                        .orElse(Either.left(false)));
    }

    /** A viewpoint's filters with every id and tag resolved. A null filter captures nothing. */
    public record Resolved(@Nullable ResolvedEntities entities, @Nullable ResolvedBlockEntities blockEntities) {
        public static final Resolved NONE = new Resolved(null, null);
    }

    public static Resolved resolve(Viewpoint vp, Identifier fileId, HolderLookup.Provider access) {
        var entityTypes = access.lookupOrThrow(Registries.ENTITY_TYPE);
        var blocks = access.lookupOrThrow(Registries.BLOCK);
        ResolvedEntities entities = vp.entities().map(f -> new ResolvedEntities(
                f.include().isEmpty() ? null : values(f.include().resolveExplicit(fileId, entityTypes)),
                values(f.exclude().resolveExplicit(fileId, entityTypes)),
                f.features(), f.cameraEntity())).orElse(null);
        ResolvedBlockEntities blockEntities = vp.blockEntities().map(f -> new ResolvedBlockEntities(
                f.include().isEmpty() ? null : values(f.include().resolveExplicit(fileId, blocks)),
                values(f.exclude().resolveExplicit(fileId, blocks)))).orElse(null);
        return new Resolved(entities, blockEntities);
    }

    private static <T> Set<T> values(Set<Holder<T>> holders) {
        return holders.stream().map(Holder::value).collect(Collectors.toUnmodifiableSet());
    }

    /** @param include null = no include constraint (every type), as opposed to an empty set = nothing */
    public record ResolvedEntities(@Nullable Set<EntityType<?>> include, Set<EntityType<?>> exclude,
                                   boolean features, boolean cameraEntity) {

        public boolean test(Entity entity, @Nullable Entity cameraEntity) {
            if (!this.cameraEntity && entity == cameraEntity) return false;
            EntityType<?> type = entity.getType();
            if (include != null && !include.contains(type)) return false;
            return !exclude.contains(type);
        }

        /**
         * Strips everything that isn't the entity's own body off an already-extracted state. Done on the
         * render STATE rather than by skipping render layers, because the state is ours - a per-capture
         * copy the main pass never sees - while the layers are shared with every other draw.
         */
        public void stripFeatures(EntityRenderState state) {
            state.nameTag = null;
            state.scoreText = null;
            state.displayFireAnimation = false;
            state.shadowRadius = 0; // the blob shadow is a decal on the ground, not part of the entity
            if (state instanceof LivingEntityRenderState living) {
                living.headItem.clear();
                living.wornHeadType = null;
            }
            if (state instanceof ArmedEntityRenderState armed) {
                armed.rightHandItemState.clear();
                armed.leftHandItemState.clear();
            }
            if (state instanceof HumanoidRenderState humanoid) {
                // chestEquipment also drives the elytra
                humanoid.headEquipment = ItemStack.EMPTY;
                humanoid.chestEquipment = ItemStack.EMPTY;
                humanoid.legsEquipment = ItemStack.EMPTY;
                humanoid.feetEquipment = ItemStack.EMPTY;
            }
        }
    }

    public record ResolvedBlockEntities(@Nullable Set<Block> include, Set<Block> exclude) {
        public boolean test(BlockEntity be) {
            Block block = be.getBlockState().getBlock();
            if (include != null && !include.contains(block)) return false;
            return !exclude.contains(block);
        }
    }
}

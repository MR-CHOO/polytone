package net.mehvahdjukaar.polytone.content.blockmodel;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.mehvahdjukaar.polytone.Polytone;
import net.mehvahdjukaar.polytone.SpecialModelsHandler;
import net.mehvahdjukaar.polytone.common.reloader.ContentManager;
import net.mehvahdjukaar.polytone.common.struc.AssetsFiles;
import net.mehvahdjukaar.polytone.content.expmodel.ExpressionModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Block models are baked on resource reload, but targets can name tags, which a client only has once it joins a
// world. So the two halves are split: the loader bakes every modifier's cases during the model reload
// (loadForBaking), and the swap happens at model lookup (BlockStateModelSetMixin) against targets resolved here
// whenever tags arrive. Only targeted blocks are ever wrapped.
public class BlockModelModifiersManager extends ContentManager<BlockModelModifier> {

    private static final Snapshot EMPTY = new Snapshot(Map.of());

    private final Map<Block, List<Identifier>> pending = new IdentityHashMap<>();
    private volatile Snapshot snapshot = EMPTY;
    private boolean needsRemesh = false;

    public BlockModelModifiersManager() {
        super(Spec.of("Block model modifier", () -> BlockModelModifier.CODEC)
                .folders("block_model_modifiers"));
    }

    // Model reload side: every modifier file regardless of its conditions or targets, since neither can be
    // evaluated yet. Never throws - a broken file just bakes nothing.
    public Map<Identifier, BlockModelModifier> loadForBaking(ResourceManager resourceManager) {
        Map<Identifier, BlockModelModifier> result = new LinkedHashMap<>();
        for (var e : getJsonsInDirectories(resourceManager).entrySet()) {
            BlockModelModifier.CODEC.decode(JsonOps.INSTANCE, e.getValue())
                    .ifSuccess(p -> result.put(e.getKey(), p.getFirst()))
                    .ifError(err -> Polytone.LOGGER.error("Failed to load block model modifier {}: {}",
                            e.getKey(), err.message()));
        }
        return result;
    }

    @Override
    protected void parseWithLevel(AssetsFiles resources, RegistryOps<JsonElement> ops, HolderLookup.Provider access) {
        for (var e : parseEnabledJsons(resources.jsons(), ops)) {
            Identifier id = e.getKey();
            for (var block : e.getValue().targets().compute(id, BuiltInRegistries.BLOCK)) {
                pending.computeIfAbsent(block.value(), b -> new ArrayList<>()).add(id);
            }
        }
    }

    @Override
    protected void applyWithLevel(HolderLookup.Provider access, boolean isLogIn) {
        if (!pending.isEmpty()) {
            Map<Block, List<Identifier>> byBlock = new IdentityHashMap<>();
            pending.forEach((block, ids) -> byBlock.put(block, List.copyOf(ids)));
            snapshot = new Snapshot(byBlock);
            needsRemesh = true;
        }
        if (needsRemesh) {
            needsRemesh = false;
            // no-op before the level is set; chunks built after this read the new snapshot anyway
            Minecraft.getInstance().levelExtractor.allChanged();
        }
    }

    @Override
    protected void resetWithLevel(boolean logOff) {
        pending.clear();
        if (!snapshot.isEmpty() && !logOff) needsRemesh = true;
        snapshot = EMPTY;
    }

    // Immutable, so a chunk-build thread always sees one consistent set of targets
    public record Snapshot(Map<Block, List<Identifier>> byBlock) {

        public boolean isEmpty() {
            return byBlock.isEmpty();
        }

        // Every modifier on the block wraps the result of the previous one, in file order. Returns original when
        // any of them has no baked cases yet (mid-reload), so a half-applied model is never cached.
        BlockStateModel wrap(List<Identifier> ids, BlockStateModel original) {
            BlockStateModel model = original;
            for (Identifier id : ids) {
                ExpressionModel.Selector cases = SpecialModelsHandler.getBlockModelModifier(id);
                if (cases == null) return original;
                model = SpecialModelsHandler.wrapBlockModel(cases.hasFallback() ? cases : cases.withFallback(model));
            }
            return model;
        }
    }

    // One per BlockStateModelSet, so a resource reload starts from an empty cache. A new snapshot (world join,
    // tag reload) starts a new generation. Misses are never cached: a state looked up before its cases finished
    // baking must not be pinned to its unwrapped model.
    public static final class ModelCache {

        private volatile Generation generation = new Generation(EMPTY);

        public BlockStateModel get(BlockState state, BlockStateModel original) {
            Snapshot snap = Polytone.BLOCK_MODEL_MODIFIERS.snapshot;
            List<Identifier> ids = snap.byBlock.get(state.getBlock());
            if (ids == null) return original;

            Generation gen = generation;
            if (gen.snapshot != snap) {
                gen = new Generation(snap);
                generation = gen;
            }
            BlockStateModel cached = gen.models.get(state);
            if (cached != null) return cached;

            BlockStateModel wrapped = snap.wrap(ids, original);
            if (wrapped != original) gen.models.put(state, wrapped);
            return wrapped;
        }

        private record Generation(Snapshot snapshot, Map<BlockState, BlockStateModel> models) {
            Generation(Snapshot snapshot) {
                this(snapshot, new ConcurrentHashMap<>());
            }
        }
    }
}

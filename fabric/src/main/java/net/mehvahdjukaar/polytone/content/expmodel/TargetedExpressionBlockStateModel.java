package net.mehvahdjukaar.polytone.content.expmodel;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.function.Predicate;

// A block's own model wrapped by a block model modifier. Unlike ExpressionBlockStateModel the fallback is an
// arbitrary existing model (possibly another mod's), so the no-match path defers to it entirely, geometry key
// included, and the material flags cover every case the selector can pick.
public record TargetedExpressionBlockStateModel(ExpressionModel.Selector selector) implements BlockStateModel {

    // distinct type, so a case can never share a merge key with whatever the fallback returns
    private record CaseKey(int index) {
    }

    @Override
    public void emitQuads(QuadEmitter emitter, BlockAndTintGetter blockView, BlockPos pos, BlockState state, RandomSource random, Predicate<Direction> cullTest) {
        selector.select(pos, state).emitQuads(emitter, blockView, pos, state, random, cullTest);
    }

    @Override
    public Object createGeometryKey(BlockAndTintGetter blockView, BlockPos pos, BlockState state, RandomSource random) {
        int index = selector.selectIndex(pos, state);
        return index < 0 ? selector.fallback().createGeometryKey(blockView, pos, state, random) : new CaseKey(index);
    }

    @Override
    public Material.Baked particleMaterial(BlockAndTintGetter blockView, BlockPos pos, BlockState state) {
        return selector.select(pos, state).particleMaterial(blockView, pos, state);
    }

    @Override
    public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
        // no world context (e.g. baking probes) -> the block's own model
        selector.fallback().collectParts(random, parts);
    }

    @Override
    public Material.Baked particleMaterial() {
        return selector.fallback().particleMaterial();
    }

    @Override
    public int materialFlags() {
        return selector.allMaterialFlags();
    }
}

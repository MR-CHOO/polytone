package net.mehvahdjukaar.polytone.mixins;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.mehvahdjukaar.polytone.content.blockmodel.BlockModelModifiersManager;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

// Applies block model modifiers at lookup time, the same way FluidStateModelSetMixin applies fluid tints.
// Vanilla and Sodium chunk meshing, block particles and block markers all resolve models through get.
@Mixin(BlockStateModelSet.class)
public class BlockStateModelSetMixin {

    @Unique
    private final BlockModelModifiersManager.ModelCache polytone$modifiedModels = new BlockModelModifiersManager.ModelCache();

    @ModifyReturnValue(method = "get", at = @At("RETURN"))
    private BlockStateModel polytone$applyBlockModelModifiers(BlockStateModel original, BlockState state) {
        return polytone$modifiedModels.get(state, original);
    }
}

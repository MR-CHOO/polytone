package net.mehvahdjukaar.polytone.mixins;

import net.mehvahdjukaar.polytone.Polytone;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Placing or breaking a block moves the column's height, and vanilla has already updated the chunk's
// heightmaps by the time this returns. Biomes never change from a block edit.
@Mixin(LevelChunk.class)
public class LevelChunkSurfaceMapMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void polytone$surfaceMapColumnChanged(BlockPos pos, BlockState state, int flags,
                                                  CallbackInfoReturnable<BlockState> cir) {
        if (((LevelChunk) (Object) this).getLevel().isClientSide()) {
            Polytone.SURFACE_MAP.markColumnDirty(pos);
        }
    }
}

package net.mehvahdjukaar.polytone;

import net.mehvahdjukaar.candlelight.api.PlatformImpl;
import net.mehvahdjukaar.polytone.content.expmodel.ExpressionModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
public class SpecialModelsHandler {

    @PlatformImpl
    public static void clear() {
        throw new AssertionError();
    }

    @PlatformImpl
    public static void addSpecialModel(Identifier id) {
        throw new AssertionError();
    }

    @Contract
    @PlatformImpl
    @Nullable
    public static QuadCollection getSpecialModel(Identifier id) {
        throw new AssertionError();
    }

    @PlatformImpl
    public static void finalizeAdditions() {
        throw new AssertionError();
    }

    // Cases baked for the block model modifier file id, or null if none are baked (yet)
    @Contract
    @PlatformImpl
    @Nullable
    public static ExpressionModel.Selector getBlockModelModifier(Identifier id) {
        throw new AssertionError();
    }

    // The loader's baked model for a block model modifier; selector must already have its fallback
    @Contract
    @PlatformImpl
    public static BlockStateModel wrapBlockModel(ExpressionModel.Selector selector) {
        throw new AssertionError();
    }
}

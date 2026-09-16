package net.mehvahdjukaar.polytone.platform;

import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.SimpleUnbakedExtraModel;
import net.fabricmc.fabric.api.client.model.loading.v1.UnbakedExtraModel;
import net.mehvahdjukaar.polytone.Polytone;
import net.mehvahdjukaar.polytone.content.blockmodel.BlockModelModifier;
import net.mehvahdjukaar.polytone.content.expmodel.ExpressionModel;
import net.mehvahdjukaar.polytone.content.expmodel.TargetedExpressionBlockStateModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class SpecialModelsHandlerImpl {

    //DUMB
    private static final Map<Identifier, ExtraModelKey<QuadCollection>> SPECIAL_MODELS = new HashMap<>();

    public static void clear() {
        SPECIAL_MODELS.clear();
    }

    public static void addSpecialModel(Identifier id) {
        SPECIAL_MODELS.put(id, ExtraModelKey.create(id::toString));
    }

    @Nullable
    public static QuadCollection getSpecialModel(Identifier id) {
        var key = SPECIAL_MODELS.get(id);
        if (key != null) {
            var mm = Minecraft.getInstance().getModelManager();
            return mm.getModel(key);
        }
        return null;
    }

    private static ModelLoadingPlugin.Context hack = null;

    public static void init() {
        // safely sets hack
        ModelLoadingPlugin.register(context -> hack = context);
        PreparableModelLoadingPlugin.register(
                (state, executor) -> CompletableFuture.supplyAsync(
                        () -> Polytone.BLOCK_MODEL_MODIFIERS.loadForBaking(state.resourceManager()), executor),
                SpecialModelsHandlerImpl::addBlockModelModifiers);
    }

    // Replaced wholesale on each model reload; lookups against the previous bake just miss until it lands
    private static volatile Map<Identifier, ExtraModelKey<ExpressionModel.Selector>> blockModelModifiers = Map.of();

    private static void addBlockModelModifiers(Map<Identifier, BlockModelModifier> modifiers,
                                               ModelLoadingPlugin.Context context) {
        Map<Identifier, ExtraModelKey<ExpressionModel.Selector>> keys = new HashMap<>();
        for (var entry : modifiers.entrySet()) {
            Identifier id = entry.getKey();
            BlockModelModifier modifier = entry.getValue();
            ExtraModelKey<ExpressionModel.Selector> key = ExtraModelKey.create(() -> "block model modifier " + id);
            context.addModel(key, new UnbakedExtraModel<>() {
                @Override
                public ExpressionModel.Selector bake(ModelBaker baker) {
                    return ExpressionModel.bakeCases(modifier.cases(), modifier.selector(), modifier.fallback(), baker);
                }

                @Override
                public void resolveDependencies(ResolvableModel.Resolver resolver) {
                    ExpressionModel.resolveCaseDependencies(modifier.cases(), modifier.fallback(), resolver);
                }
            });
            keys.put(id, key);
        }
        blockModelModifiers = keys;
    }

    @Nullable
    public static ExpressionModel.Selector getBlockModelModifier(Identifier id) {
        var key = blockModelModifiers.get(id);
        return key == null ? null : Minecraft.getInstance().getModelManager().getModel(key);
    }

    public static BlockStateModel wrapBlockModel(ExpressionModel.Selector selector) {
        return new TargetedExpressionBlockStateModel(selector);
    }

    public static void finalizeAdditions() {
        if (hack == null) return;
        // Wait for hack to be initialized, up to a timeout if desired
        for (var entry : SPECIAL_MODELS.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();

            hack.addModel(value, new SimpleUnbakedExtraModel<>(
                    key,
                    (model, baker) -> model.bakeTopGeometry(
                            model.getTopTextureSlots(),
                            baker,
                            BlockModelRotation.IDENTITY
                    )
            ));
        }


    }

}

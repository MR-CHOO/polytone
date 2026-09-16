package net.mehvahdjukaar.polytone.platform;

import net.mehvahdjukaar.polytone.content.expmodel.ExpressionModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.ModelDebugName;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

public class SpecialModelsHandlerImpl {

    //DUMB
    private static final Map<Identifier, StandaloneModelKey<QuadCollection>> SPECIAL_MODELS = new HashMap<>();

    public static void clear() {
        SPECIAL_MODELS.clear();
    }

    public static void addSpecialModel(Identifier id) {
        SPECIAL_MODELS.put(id, new StandaloneModelKey<>(id::toString));

    }

    @Nullable
    public static QuadCollection getSpecialModel(Identifier id) {
        var key = SPECIAL_MODELS.get(id);
        if (key != null) {
            ModelManager mm = Minecraft.getInstance().getModelManager();
            return mm.getStandaloneModel(key);
        }
        return null;
    }

    public static void init(IEventBus bus) {
        bus.addListener(SpecialModelsHandlerImpl::registerExtraModels);
    }

    public static void registerExtraModels(ModelEvent.RegisterStandalone event) {
        for (var entry : SPECIAL_MODELS.entrySet()) {
            event.register(entry.getValue(), SimpleUnbakedStandaloneModel.quadCollection(entry.getKey()));
        }
    }

    public static void finalizeAdditions() {
    }

    // Block model modifiers aren't wired up on NeoForge yet: nothing is baked, so no block is ever wrapped
    @Nullable
    public static ExpressionModel.Selector getBlockModelModifier(Identifier id) {
        return null;
    }

    public static BlockStateModel wrapBlockModel(ExpressionModel.Selector selector) {
        return selector.fallback();
    }
}

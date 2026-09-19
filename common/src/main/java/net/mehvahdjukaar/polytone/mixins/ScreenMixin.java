package net.mehvahdjukaar.polytone.mixins;

import net.mehvahdjukaar.polytone.Polytone;
import net.mehvahdjukaar.polytone.content.slotify.ScreenModifier;
import net.mehvahdjukaar.polytone.content.slotify.SlotifyScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(Screen.class)
public abstract class ScreenMixin implements SlotifyScreen {

    @Shadow
    protected abstract void rebuildWidgets();

    @Shadow
    @Final
    private List<Renderable> renderables;

    @Shadow
    @Final
    private List<GuiEventListener> children;

    @Unique
    private ScreenModifier polytone$modifier = null;

    //we cant access screen title during consturciton so we delay
    @Inject(method = "init(II)V", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        polytone$modifier = Polytone.SLOTIFY.getGuiModifier((Screen) (Object) this);
    }

    @Override
    public void polytone$renderExtraSprites(GuiGraphicsExtractor poseStack, int mouseX, int mouseY, float partialTicks) {
        if (polytone$modifier != null) {
            polytone$modifier.renderExtras(poseStack, mouseX, mouseY, partialTicks);
        }
    }

    @Override
    public boolean polytone$hasSprites() {
        return polytone$modifier != null && !polytone$modifier.extraRenderables().isEmpty();
    }

    @Override
    public ScreenModifier polytone$getModifier() {
        return polytone$modifier;
    }

    @Override
    public void polytone$refreshModifier() {
        polytone$modifier = Polytone.SLOTIFY.getGuiModifier((Screen) (Object) this);
    }

    @Override
    public void polytone$rebuild() {
        this.rebuildWidgets();
    }

    // Widgets are also modified when added, but some screens move them afterwards (TitleScreen) or re-lay
    // them out on resize without rebuilding (layout screens). Run again once layout is done; modifyWidgets
    // only re-adds what moved.
    @Inject(method = {"init(II)V", "rebuildWidgets", "resize(II)V"}, at = @At("TAIL"))
    private void polytone$modifyWidgetsAfterLayout(CallbackInfo ci) {
        var mod = Polytone.SLOTIFY.getGuiModifier((Screen) (Object) this);
        if (mod == null) return;
        for (Renderable r : this.renderables) {
            if (r instanceof AbstractWidget aw) mod.modifyWidgets(aw);
        }
        for (GuiEventListener c : this.children) {
            if (c instanceof AbstractWidget aw) mod.modifyWidgets(aw);
        }
    }

    @Inject(method = "addWidget", at = @At("HEAD"))
    public <T extends GuiEventListener & NarratableEntry> void modifyWidget2(T listener, CallbackInfoReturnable<T> cir) {
        //gets it new as it might not have been init yet
        var mod = Polytone.SLOTIFY.getGuiModifier((Screen) (Object) this);
        if (mod != null && listener instanceof AbstractWidget aw) {
            mod.modifyWidgets(aw);
        }
    }

    @Inject(method = "addRenderableOnly", at = @At("HEAD"))
    public <T extends Renderable> void modifyRenderable(T listener, CallbackInfoReturnable<T> cir) {
        var mod = Polytone.SLOTIFY.getGuiModifier((Screen) (Object) this);
        if (mod != null && listener instanceof AbstractWidget aw) {
            mod.modifyWidgets(aw);
        }
    }

}

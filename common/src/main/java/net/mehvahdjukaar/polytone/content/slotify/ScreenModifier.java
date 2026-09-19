package net.mehvahdjukaar.polytone.content.slotify;

import net.mehvahdjukaar.polytone.common.expressions.impl.SimpleExp;
import net.mehvahdjukaar.polytone.common.struc.ListUtils;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public record ScreenModifier(int titleX, int titleY, int labelX, int labelY,
                             int xOff, int yOff, int wOff, int hOff,
                             @Nullable Integer titleColor, @Nullable Integer labelColor,
                             List<Renderable> extraRenderables,
                             List<WidgetModifier> widgetModifiers,
                             Map<String, SpecialOffset> specialOffsets,
                             @Nullable SimpleExp condition) {

    public static ScreenModifier fromGuiMod(GuiModifier original) {
        List<Renderable> lis = new ArrayList<>(original.sprites());
        lis.addAll(original.textList());
        return new ScreenModifier(original.titleX(), original.titleY(), original.labelX(), original.labelY(),
                original.xOff(), original.yOff(), original.wOff(), original.hOff(),
                original.titleColor(), original.labelColor(),
                lis,
                new ArrayList<>(original.widgetModifiers()),
                Map.copyOf(original.specialOffsets()),
                original.condition());
    }

    public boolean passesCondition() {
        if (condition == null) return true;
        try {
            return condition.evaluate() != 0;
        } catch (Exception e) {
            // screens outside a world, like the title screen, have no player or level to read
            return false;
        }
    }

    public ScreenModifier merge(ScreenModifier newMod) {
        return new ScreenModifier(
                newMod.titleX != 0 ? newMod.titleX : this.titleX,
                newMod.titleY != 0 ? newMod.titleY : this.titleY,
                newMod.labelX != 0 ? newMod.labelX : this.labelX,
                newMod.labelY != 0 ? newMod.labelY : this.labelY,
                newMod.xOff != 0 ? newMod.xOff : this.xOff,
                newMod.yOff != 0 ? newMod.yOff : this.yOff,
                newMod.wOff != 0 ? newMod.wOff : this.wOff,
                newMod.hOff != 0 ? newMod.hOff : this.hOff,
                newMod.titleColor != null ? newMod.titleColor : this.titleColor,
                newMod.labelColor != null ? newMod.labelColor : this.labelColor,
                ListUtils.mergeList(newMod.extraRenderables, this.extraRenderables),
                ListUtils.mergeList(newMod.widgetModifiers, this.widgetModifiers),
                ListUtils.mergedMap(newMod.specialOffsets, this.specialOffsets),
                // conditions are already evaluated before merging, so the merged result carries none
                null
        );
    }

    @Nullable
    public SpecialOffset getSpecial(String key) {
        return this.specialOffsets.get(key);
    }

    // widget -> {dx, dy, dw, x, y, w}: what our modifiers added, and where that left it
    private static final Map<AbstractWidget, int[]> MODIFIED = new WeakHashMap<>();

    // Safe to call more than once per widget: vanilla may move a widget after adding it or re-lay it out on
    // resize, so this runs again once layout is done and only re-adds the offsets a layout pass reset
    public void modifyWidgets(AbstractWidget button) {
        int[] m = MODIFIED.get(button);
        if (m == null) {
            int x = button.getX(), y = button.getY(), w = button.getWidth();
            boolean matched = false;
            for (var mod : this.widgetModifiers) {
                matched |= mod.maybeModify(button);
            }
            if (!matched) return;
            m = new int[]{button.getX() - x, button.getY() - y, button.getWidth() - w, 0, 0, 0};
            MODIFIED.put(button, m);
        } else {
            if (button.getX() != m[3]) button.setX(button.getX() + m[0]);
            if (button.getY() != m[4]) button.setY(button.getY() + m[1]);
            if (button.getWidth() != m[5]) button.setWidth(button.getWidth() + m[2]);
        }
        m[3] = button.getX();
        m[4] = button.getY();
        m[5] = button.getWidth();
    }

    public void renderExtras(GuiGraphicsExtractor poseStack, int mouseX, int mouseY, float partialTicks) {
        this.extraRenderables.forEach(r -> r.extractRenderState(poseStack, mouseX, mouseY, partialTicks));
    }
}

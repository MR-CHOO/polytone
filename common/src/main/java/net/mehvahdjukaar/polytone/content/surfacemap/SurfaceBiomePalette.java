package net.mehvahdjukaar.polytone.content.surfacemap;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderSystem;
import net.mehvahdjukaar.polytone.Polytone;
import net.mehvahdjukaar.polytone.common.ColorUtils;
import net.mehvahdjukaar.polytone.common.attributes.IExtendedAttrInterpolator;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.attribute.AttributeTypes;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.SpatialAttributeInterpolator;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryStack;

import it.unimi.dsi.fastutil.ints.IntSet;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Slot numbers for the biomes currently in the window, and the values each of them resolves the
 * pack's listed attributes to. The texture stores a slot per cell; this block stores what a slot means,
 * because a handful of biomes cover millions of texels.
 *
 * <pre>
 * layout(std140) uniform PolySurfaceBiome {
 *     ivec4 SurfaceBiomeWindow;   // xy = window min block, z = blocks per texel, w = texels per side
 *     ivec4 SurfaceBiomeInfo;     // x = attributes per slot, y = slots in use, zw = 0
 *     vec4  SurfaceBiomePalette[MAX_SLOTS * MAX_ATTRIBUTES];  // [slot * MAX_ATTRIBUTES + i]
 * };
 * </pre>
 *
 * <p>A value is a float in x, a colour as rgb 0..1 with alpha in w, or a boolean as 0/1 in x.</p>
 *
 * <p><b>CLIMATE, in the LAST entry of every slot</b> ({@code [slot * MAX_ATTRIBUTES + MAX_ATTRIBUTES - 1]}):
 * {@code (base temperature, downfall, has precipitation ? 1 : 0, 1)}. Climate is a field of the biome,
 * not an environment attribute, so no pack could list it; weather is the consumer (rain that stops at a
 * desert's edge, snow above the snow line). It is written only while the pack lists fewer than
 * {@code MAX_ATTRIBUTES} attributes, into what was always zero padding, so no index a pack already
 * reads moves. {@code SurfaceBiomeInfo.z} is 1 when it is there; w = 1 marks a written entry, so a
 * shader can tell "no climate" (older jar, or eight attributes listed) from a real 0-degree biome.</p>
 *
 * <p>The stride is always {@code MAX_ATTRIBUTES}, never {@code SurfaceBiomeInfo.x}: every slot is
 * written full width and the attributes the pack did not ask for are zeros, so a shader indexes with
 * a constant and {@code SurfaceBiomeInfo.x} only says how many of them mean anything.</p>
 */
public class SurfaceBiomePalette implements AutoCloseable {

    public static final String UBO_NAME = "PolySurfaceBiome";
    public static final int MAX_SLOTS = 64;   // slot 0 means "not filled yet", 255 means "ran out"
    public static final int OVERFLOW_SLOT = 255;

    private static final int PALETTE_ENTRIES = MAX_SLOTS * SurfaceMapSettings.BiomeLayer.MAX_ATTRIBUTES;

    public static final int UBO_SIZE = paletteSize();

    private static int paletteSize() {
        Std140SizeCalculator size = new Std140SizeCalculator().putIVec4().putIVec4();
        for (int i = 0; i < PALETTE_ENTRIES; i++) size.putVec4();
        return size.get();
    }

    // identity: biome instances are the registry's own, and this is only ever touched on the render thread
    private final Map<Biome, Integer> slots = new IdentityHashMap<>();
    // INDEXED BY SLOT, with holes: a slot freed by retainOnly leaves a null behind rather than shifting
    // everything after it, because the numbers are already written into the texture's texels.
    private final List<Holder<Biome>> bySlot = new ArrayList<>(Collections.nCopies(MAX_SLOTS, null));
    private int highestSlot = 0;
    private boolean warnedOverflow = false;
    private GpuBuffer buffer = null;

    /** True when one more distinct biome would overflow, so the caller can free what it can first. */
    public boolean isFull() {
        return slots.size() + 1 >= MAX_SLOTS;
    }

    /** The slot for this biome, allocating one if the window has just reached it. */
    public int slotFor(Holder<Biome> biome) {
        Integer existing = slots.get(biome.value());
        if (existing != null) return existing;
        for (int slot = 1; slot < MAX_SLOTS; slot++) {   // slot 0 stays "not filled yet"
            if (bySlot.get(slot) != null) continue;
            bySlot.set(slot, biome);
            slots.put(biome.value(), slot);
            highestSlot = Math.max(highestSlot, slot);
            return slot;
        }
        if (!warnedOverflow) {
            warnedOverflow = true;
            Polytone.LOGGER.warn("Surface map: more than {} biomes are in the window at once, so cells beyond "
                    + "that read as overflow and consumers fall back to the camera. Reduce the biome layer's "
                    + "coverage if this dimension really has this many.", MAX_SLOTS - 1);
        }
        return OVERFLOW_SLOT;
    }

    /**
     * Hands back every slot no live texel still refers to.
     *
     * <p>Slots are handed out as cells enter the window and, without this, never handed back: walk far
     * enough in one session and the 63 fill up with biomes that left the window long ago, after which
     * every new cell - including the one under the camera - reads {@link #OVERFLOW_SLOT} and the whole
     * feature dies silently. It presents as "it worked, then stopped".</p>
     *
     * <p>The texture IS the reference count, so a slot absent from it is genuinely unused. Freed slots
     * are not compacted: the numbers still in the texels have to keep meaning what they meant.</p>
     */
    public void retainOnly(IntSet used) {
        int highest = 0;
        for (int slot = 1; slot < MAX_SLOTS; slot++) {
            Holder<Biome> biome = bySlot.get(slot);
            if (biome == null) continue;
            if (used.contains(slot)) {
                highest = slot;
                continue;
            }
            bySlot.set(slot, null);
            slots.remove(biome.value());
        }
        highestSlot = highest;
    }

    public void clear() {
        slots.clear();
        Collections.fill(bySlot, null);
        highestSlot = 0;
        warnedOverflow = false;
    }

    /**
     * Re-evaluates every live slot. Each biome is run through the whole layer stack with its own
     * contribution pinned to weight 1, which is what makes a pack's existing biome_modifiers - MVEL
     * expressions included - show up in the palette with nothing new to author.
     */
    public void update(ClientLevel level, Vec3 camPos, List<EnvironmentAttribute<?>> attributes,
                       int windowMinX, int windowMinZ, int texelSize, int texels) {
        int attrCount = attributes.size();
        int maxAttr = SurfaceMapSettings.BiomeLayer.MAX_ATTRIBUTES;
        boolean climate = attrCount < maxAttr;   // the last entry is free: carry the climate there
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Std140Builder builder = Std140Builder.onStack(stack, UBO_SIZE)
                    .putIVec4(windowMinX, windowMinZ, texelSize, texels)
                    // y is the HIGHEST live slot, not a count: retainOnly leaves holes, so a shader's
                    // "slot <= slots in use" check has to be against the top of the range.
                    // z = 1: the climate entry is present (see the class comment).
                    .putIVec4(attrCount, highestSlot, climate ? 1 : 0, 0);
            // Slot 0 is "not filled yet" and is written as a zero block like any other unused slot, so
            // the shader's index is a plain slot * MAX_ATTRIBUTES and the array is written to its full
            // declared length rather than one slot short of it.
            for (int slot = 0; slot < MAX_SLOTS; slot++) {
                Holder<Biome> biome = bySlot.get(slot);
                for (int i = 0; i < maxAttr; i++) {
                    if (biome == null) {
                        builder.putVec4(0, 0, 0, 0);
                    } else if (i < attrCount) {
                        putValue(builder, evaluate(level, camPos, attributes.get(i), biome));
                    } else if (climate && i == maxAttr - 1) {
                        putClimate(builder, biome.value());
                    } else {
                        builder.putVec4(0, 0, 0, 0);
                    }
                }
            }
            ByteBuffer bb = builder.get();
            if (buffer == null) {
                buffer = RenderSystem.getDevice().createBuffer(() -> "Polytone surface biome palette",
                        GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM, UBO_SIZE);
            }
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), bb);
        }
    }

    private static <Value> Object evaluate(ClientLevel level, Vec3 camPos,
                                           EnvironmentAttribute<Value> attribute, Holder<Biome> biome) {
        SpatialAttributeInterpolator interpolator = new SpatialAttributeInterpolator();
        interpolator.accumulate(1.0, biome.value().getAttributes());
        // same post layer the camera probe feeds, so biome_modifiers apply here too
        SpatialAttributeInterpolator post = ((IExtendedAttrInterpolator) interpolator)
                .polytone$getOrCreatePostInterpolator();
        if (post != null) post.accumulate(1.0, Polytone.BIOME_MODIFIERS.getPostAttributes(biome.value()));
        return level.environmentAttributes().getValue(attribute, camPos, interpolator);
    }

    // The biome's own climate, not an attribute: base temperature (the value vanilla height-adjusts
    // for the snow line), downfall, and whether it precipitates at all.
    private static void putClimate(Std140Builder builder, Biome biome) {
        Biome.ClimateSettings c = ColorUtils.getClimateSettings(biome);
        builder.putVec4(c.temperature(), c.downfall(), c.hasPrecipitation() ? 1 : 0, 1);
    }

    private static void putValue(Std140Builder builder, Object value) {
        switch (value) {
            case Float f -> builder.putVec4(f, 0, 0, 0);
            case Integer color -> builder.putVec4(
                    ((color >> 16) & 0xFF) / 255f,
                    ((color >> 8) & 0xFF) / 255f,
                    (color & 0xFF) / 255f,
                    ((color >>> 24) & 0xFF) / 255f);
            case Boolean b -> builder.putVec4(b ? 1 : 0, 0, 0, 0);
            case null, default -> builder.putVec4(0, 0, 0, 0);
        }
    }

    /** Null until the first update; bind sites fall back to an empty block. */
    public GpuBufferSlice slice() {
        return buffer == null ? null : buffer.slice();
    }

    public static boolean isSupported(EnvironmentAttribute<?> attribute) {
        var type = attribute.type();
        return type == AttributeTypes.FLOAT || type == AttributeTypes.ANGLE_DEGREES
                || type == AttributeTypes.RGB_COLOR || type == AttributeTypes.ARGB_COLOR
                || type == AttributeTypes.BOOLEAN;
    }

    @Override
    public void close() {
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
        clear();
    }
}

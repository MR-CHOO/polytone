package net.mehvahdjukaar.polytone.common.codec_ui.example;

import com.mojang.serialization.Codec;
import com.mojang.serialization.RecordBuilder;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.mehvahdjukaar.polytone.common.codec_ui.SchemaCodec;
import net.mehvahdjukaar.polytone.content.block.BlockPropertyModifier;
import net.mehvahdjukaar.polytone.content.colormap.Colormap;
import net.mehvahdjukaar.polytone.content.dimension.DimensionEffectsModifier;
import net.mehvahdjukaar.polytone.content.fluid.FluidPropertyModifier;
import net.mehvahdjukaar.polytone.content.shaders.ExpressionUniformBuffers;
import net.mehvahdjukaar.polytone.content.tabs.ItemPredicate;
import net.mehvahdjukaar.polytone.content.tabs.CreativeTabModifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockStateMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Central registry of editor entries — every {@link SchemaCodec} that should appear in the
 * Swing UI is listed here together with a human-readable label and a group name.
 * Consumed by {@code ExamplesLauncher}.
 */
public final class CodecRegistry {

    private CodecRegistry() {}

    public record Entry(String label, String group, SchemaCodec<?> codec) {}

    private static final List<Entry> ENTRIES = build();

    public static List<Entry> all() {
        return ENTRIES;
    }

    private static List<Entry> build() {
        PolytoneSchemas.bootstrap();
        List<Entry> list = new ArrayList<>();

        // ----- Demo / migration examples -----
        list.add(new Entry("Migrated GuiDepthTarget (3 fields)",   "Demo types", MigratedGuiDepthTargetExample.SCHEMA_CODEC));
        list.add(new Entry("Migrated Lightmap (7 fields)",         "Demo types", MigratedLightmapExample.SCHEMA_CODEC));
        list.add(new Entry("Migrated Colormap (9 fields, group9)", "Demo types", MigratedColormapExample.SCHEMA_CODEC));

        // ----- Auto-introspected (raw) -----
        // These pass raw codecs straight through SchemaCodec.wrap(...) to test what the
        // SchemaResolver derives. No hand-crafted Schema, no wrapper object. Expected:
        //   tier 1 (identity) and tier 2 (structural) → real widgets;
        //   tier 3/4 (xmap, RecordCodecBuilder, dispatch) → Opaque JSON editor.
        String g = "Auto-introspected (raw)";
        // Tier 3/4 — opaque fallback (xmap / RecordCodecBuilder / dispatch)
        list.add(new Entry("raw BlockPos.CODEC",                   g, SchemaCodec.wrap(BlockPos.CODEC)));
        list.add(new Entry("raw Vec3.CODEC",                       g, SchemaCodec.wrap(Vec3.CODEC)));
        list.add(new Entry("raw Direction.CODEC",                  g, SchemaCodec.wrap(Direction.CODEC)));
        list.add(new Entry("raw MobEffectInstance.CODEC",          g, SchemaCodec.wrap(MobEffectInstance.CODEC)));
        list.add(new Entry("raw ItemStack.CODEC",                  g, SchemaCodec.wrap(ItemStack.CODEC)));
        list.add(new Entry("raw RuleTest.CODEC",                   g, SchemaCodec.wrap(RuleTest.CODEC)));
        list.add(new Entry("raw dimensitonType.CODEC",                   g, SchemaCodec.wrap(DimensionType.DIRECT_CODEC)));
        list.add(new Entry("raw colormap.CODEC",                   g, SchemaCodec.wrap(Colormap.CODEC)));
        list.add(new Entry("raw dimensionmod.CODEC",                   g, SchemaCodec.wrap(DimensionEffectsModifier.CODEC)));
        list.add(new Entry("raw sound event.CODEC",                   g, SchemaCodec.wrap(SoundEvent.CODEC)));
        list.add(new Entry("raw itempreciate.CODEC",                   g, SchemaCodec.wrap(ItemPredicate.CODEC)));
        list.add(new Entry("raw Expressiontype.CODEC",                   g, SchemaCodec.wrap(ExpressionUniformBuffers.CODEC)));

        // ----- Auto-introspected: one entry per inference path we claim to cover -----
        String g2 = "Auto (coverage checks)";
        // either(INT, dispatch-record).xmap → flat AnyOf(number, typed object)
        list.add(new Entry("IntProvider (constant or object)",     g2, SchemaCodec.wrap(IntProvider.CODEC)));
        // registry dispatch, ~100 entries → real variant bodies via the decoder
        list.add(new Entry("ParticleOptions (registry dispatch)",  g2, SchemaCodec.wrap(ParticleTypes.CODEC)));
        // plain RCB record: id + double + StringRepresentable enum
        list.add(new Entry("AttributeModifier (record + enum)",    g2, SchemaCodec.wrap(AttributeModifier.CODEC)));
        // HolderSetCodec → AnyOf(#tag or id, single, list)
        list.add(new Entry("Ingredient (holder set)",              g2, SchemaCodec.wrap(Ingredient.CODEC)));
        // hex-string colors (curated Color) + optionals + enum dropdown
        list.add(new Entry("BiomeSpecialEffects (colors)",         g2, SchemaCodec.wrap(BiomeSpecialEffects.CODEC)));
        // real polytone target codec
        list.add(new Entry("CreativeTabModifier",                  g2, SchemaCodec.wrap(CreativeTabModifier.CODEC)));

        // ----- Stress tests: partial coverage expected -----
        String g3 = "Stress (partial expected)";
        // deeply recursive sum type — self-references degrade to raw JSON sub-editors
        list.add(new Entry("Text Component (recursive)",           g3, SchemaCodec.wrap(ComponentSerialization.CODEC)));
        // registry dispatch over ~1000 blocks — name dropdown only, opaque bodies (>128 gate)
        list.add(new Entry("BlockState (huge dispatch)",           g3, SchemaCodec.wrap(BlockState.CODEC)));

        // ----- Curated entries (internal/CuratedSchemas) — verify each shows its widget -----
        String g4 = "Curated (CuratedSchemas)";
        list.add(new Entry("RGB color (int)",                      g4, SchemaCodec.wrap(ExtraCodecs.RGB_COLOR_CODEC)));
        list.add(new Entry("ARGB color (hex string)",              g4, SchemaCodec.wrap(ExtraCodecs.STRING_ARGB_COLOR)));
        list.add(new Entry("BlockPos (int x3)",                    g4, SchemaCodec.wrap(BlockPos.CODEC)));
        list.add(new Entry("Vector3f (float x3)",                  g4, SchemaCodec.wrap(ExtraCodecs.VECTOR3F)));
        list.add(new Entry("UUID (string form)",                   g4, SchemaCodec.wrap(UUIDUtil.STRING_CODEC)));
        list.add(new Entry("Identifier (id widget)",               g4, SchemaCodec.wrap(net.minecraft.resources.Identifier.CODEC)));

        // ----- Ported polytone codecs (PolytoneSchemas) — the "convert our own" showcase -----
        String g5 = "Ported polytone codecs";
        list.add(new Entry("ColorUtils.COLOR (hex color)",         g5, SchemaCodec.wrap(net.mehvahdjukaar.polytone.common.ColorUtils.COLOR)));
        list.add(new Entry("ISimpleExp (constant|expression)",     g5, SchemaCodec.wrap(net.mehvahdjukaar.polytone.common.expressions.impl.ISimpleExp.CODEC)));
        list.add(new Entry("ColormapExpression (widget)",          g5, SchemaCodec.wrap(net.mehvahdjukaar.polytone.content.colormap.ColormapExpressionProvider.CODEC)));
        return list;
    };
}

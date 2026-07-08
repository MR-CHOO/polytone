package net.mehvahdjukaar.polytone.editor;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Decoder;
import net.mehvahdjukaar.polytone.Polytone;
import net.mehvahdjukaar.codecui.SchemaCodec;
import net.mehvahdjukaar.polytone.common.codec_ui.SchemaEditor.Side;
import net.mehvahdjukaar.polytone.common.codec_ui.swing.SwingWorkbench;
import net.mehvahdjukaar.polytone.common.codec_ui.workbench.CodecEntry;
import net.mehvahdjukaar.polytone.common.codec_ui.workbench.Workbench;
import net.mehvahdjukaar.polytone.content.block.BlockPropertyModifier;
import net.mehvahdjukaar.polytone.content.colormap.Colormap;
import net.mehvahdjukaar.polytone.content.dimension.DimensionEffectsModifier;
import net.mehvahdjukaar.polytone.content.fluid.FluidPropertyModifier;
import net.mehvahdjukaar.polytone.content.item.ItemModifier;
import net.mehvahdjukaar.polytone.content.lightmap.Lightmap;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * PRODUCTION entry point of the Polytone pack editor: boots the single-window workbench
 * with the real content library ({@link #contentEntries()}) and the running-game hooks.
 * The resolver test/demo pages ({@code codec_ui/example}) are appended in dev only.
 */
public final class PolytoneEditor {

    /** Open (or focus) the editor window. Callable from any thread. */
    public static void open() {
        forceNonHeadless();
        SwingWorkbench.open(new Workbench(buildEntries(Polytone.isDevEnv)));
    }

    /**
     * Full entry list with all registrations bootstrapped. Also used by
     * {@link UiPreviewLauncher} for bare-JVM UI iteration.
     */
    static List<CodecEntry> buildEntries(boolean includeDevExamples) {
        // Widget registrations must exist before any schema resolves (vanilla codec
        // curation lives in codecui's internal/CuratedSchemas, bootstrapped by the resolver itself).
        PolytoneSchemas.bootstrap();
        GameReloadHooks.install();

        return new ArrayList<>(contentEntries());
    }

    /**
     * The real, creatable polytone content: file-level codecs (what each manager actually
     * parses) plus the container dir its reloader scans — which drives file→codec
     * association and the guided New Content placement.
     */
    private static List<CodecEntry> contentEntries() {
        String g = "Polytone content";
        return List.of(
                entry("Colormap",           g, Colormap.DIRECT_CODEC,                "polytone/colormaps"),
                entry("Lightmap",           g, SchemaCodec.wrap(Lightmap.CODEC),     "polytone/lightmaps"),
                entry("Block modifier",     g, SchemaCodec.wrap(decoderAsCodec(BlockPropertyModifier.CODEC)), "polytone/block_modifiers"),
                entry("Fluid modifier",     g, SchemaCodec.wrap(decoderAsCodec(FluidPropertyModifier.CODEC)), "polytone/fluid_modifiers"),
                entry("Item modifier",      g, SchemaCodec.wrap(ItemModifier.CODEC), "polytone/item_modifiers"),
                entry("Dimension modifier", g, SchemaCodec.wrap(DimensionEffectsModifier.CODEC), "polytone/dimension_modifiers"));
    }

    private static CodecEntry entry(String label, String group, SchemaCodec<?> codec, String containerDir) {
        return new CodecEntry(label, group, codec, Side.CLIENT_RESOURCES, containerDir);
    }

    /**
     * Some content codecs are declared {@code Decoder}-only (encode unsupported) but are
     * RecordCodecBuilder-built full Codecs at runtime. The editor only decodes — widgets
     * emit JSON directly and validation parses — so the downcast is safe here.
     */
    private static <A> Codec<A> decoderAsCodec(Decoder<A> decoder) {
        return (Codec<A>) decoder;
    }

    // NeoForge launches with java.awt.headless=true and GraphicsEnvironment caches the flag.
    // setAccessible on a java.desktop private field is blocked since JDK 16, so go through
    // sun.misc.Unsafe (jdk.unsupported is opened for reflection) to overwrite the cache directly.
    private static void forceNonHeadless() {
        System.setProperty("java.awt.headless", "false");
        if (!GraphicsEnvironment.isHeadless()) return;
        try {
            Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object unsafe = unsafeField.get(null);
            Class<?> unsafeClass = unsafe.getClass();
            Field headlessField = GraphicsEnvironment.class.getDeclaredField("headless");
            Object base = unsafeClass.getMethod("staticFieldBase", Field.class).invoke(unsafe, headlessField);
            long offset = (long) unsafeClass.getMethod("staticFieldOffset", Field.class).invoke(unsafe, headlessField);
            unsafeClass.getMethod("putObject", Object.class, long.class, Object.class)
                    .invoke(unsafe, base, offset, Boolean.FALSE);
        } catch (Throwable t) {
            try {
                Polytone.LOGGER.warn("Could not disable AWT headless mode. Add JVM arg "
                        + "-Djava.awt.headless=false to your run config if the editor fails to open.", t);
            } catch (Throwable ignored) {
                // Bare JVM without Polytone bootstrap — the preview main logs to stderr anyway.
            }
        }
    }
}

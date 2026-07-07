package net.mehvahdjukaar.polytone.editor;
import net.mehvahdjukaar.polytone.common.codec_ui.SchemaCodecs;

import com.mojang.serialization.Codec;
import net.mehvahdjukaar.codecui.SchemaCodec;
import net.mehvahdjukaar.polytone.common.codec_ui.SchemaEditor.Side;
import net.mehvahdjukaar.polytone.common.codec_ui.swing.SwingWorkbench;
import net.mehvahdjukaar.polytone.common.codec_ui.workbench.CodecEntry;
import net.mehvahdjukaar.polytone.common.codec_ui.workbench.Workbench;

import java.util.List;

/**
 * Bare-JVM entry point for iterating on the workbench UI WITHOUT launching Minecraft —
 * just run this {@code main} from the IDE. Expect graceful degradation, not full function:
 * <ul>
 *   <li>no registry ops → registry-touching codecs validate with errors;</li>
 *   <li>reload buttons disabled (no game to hook);</li>
 *   <li>no mixins → construction tags absent, so RecordCodecBuilder records resolve to the
 *       raw-JSON editor and the full codec library may fail to class-load at all (it then
 *       falls back to the pure-DFU demo entries below).</li>
 * </ul>
 * Window layout, pack tree, tab management, codec library, text/image tabs and the form
 * widgets themselves are all fully exercisable — which is the point.
 */
public final class UiPreviewLauncher {

    private UiPreviewLauncher() {}

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "false");

        List<CodecEntry> entries;
        try {
            entries = PolytoneEditor.buildEntries(true);
        } catch (Throwable t) {
            System.err.println("[codec_ui] Codec library needs game classes (" + t
                    + ") — falling back to pure-DFU demo entries.");
            entries = demoEntries();
        }
        SwingWorkbench.open(new Workbench(entries));
    }

    /** Entries built from DFU alone — no Minecraft classes, no mixins, no bootstrap. */
    private static List<CodecEntry> demoEntries() {
        String g = "UI preview (pure DFU)";
        return List.of(
                entry("Int", g, Codec.INT),
                entry("Bool", g, Codec.BOOL),
                entry("String", g, Codec.STRING),
                entry("Int range 0-100", g, Codec.intRange(0, 100)),
                entry("String list", g, Codec.STRING.listOf()),
                entry("Bool or String", g, Codec.either(Codec.BOOL, Codec.STRING)),
                entry("String→Double map", g, Codec.unboundedMap(Codec.STRING, Codec.DOUBLE)),
                entry("Nested list of int lists", g, Codec.INT.listOf().listOf()),
                entry("Pair (int, string)", g, Codec.pair(
                        Codec.INT.fieldOf("left").codec(), Codec.STRING.fieldOf("right").codec())));
    }

    private static CodecEntry entry(String label, String group, Codec<?> codec) {
        return new CodecEntry(label, group, SchemaCodecs.wrap(codec), Side.CLIENT_RESOURCES);
    }
}

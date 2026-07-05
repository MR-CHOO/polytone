package net.mehvahdjukaar.polytone.common.codec_ui.workbench;

import net.mehvahdjukaar.polytone.common.codec_ui.SchemaCodec;
import net.mehvahdjukaar.polytone.common.codec_ui.SchemaEditor;
import org.jetbrains.annotations.Nullable;

/**
 * One editable content type in the workbench: what the codec library lists and what pack
 * files bind to. {@code containerDir} (optional) is the in-pack directory this content lives
 * in, e.g. {@code "polytone/colormaps"} — it is BOTH documentation ("where these files go")
 * and the file-association rule used by {@link Workbench#entryFor}.
 */
public record CodecEntry(String label, String group, SchemaCodec<?> codec,
                         SchemaEditor.Side side, @Nullable String containerDir) {

    public CodecEntry(String label, String group, SchemaCodec<?> codec, SchemaEditor.Side side) {
        this(label, group, codec, side, null);
    }

    /**
     * Whether a file whose {@link PackWorkspace.Location#containerDir} is {@code container}
     * should open with this entry's codec. Deliberately loose about extra path levels on
     * either side: {@code containerDir = "polytone/colormaps"} matches
     * {@code "polytone/colormaps"}, {@code "polytone/colormaps/sub"}, and (for lenient roots
     * where the user opened a folder above or below the usual pack root)
     * {@code "mypack/assets-less/polytone/colormaps"}.
     */
    public boolean matchesContainer(@Nullable String container) {
        if (containerDir == null || container == null || container.isEmpty()) return false;
        return container.equals(containerDir)
                || container.startsWith(containerDir + "/")
                || container.endsWith("/" + containerDir)
                || container.contains("/" + containerDir + "/");
    }
}

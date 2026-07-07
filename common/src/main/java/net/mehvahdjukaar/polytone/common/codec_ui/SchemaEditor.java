package net.mehvahdjukaar.polytone.common.codec_ui;
import net.mehvahdjukaar.codecui.*;

import org.jetbrains.annotations.Nullable;
import java.util.function.Consumer;

public interface SchemaEditor {

    /**
     * Which registry view a codec's file logically belongs to. Datapack (dynamic) registries
     * like {@code worldgen/biome} only resolve through a registry-aware ops, and there are
     * two views of them: the CLIENT-synced one (what resource-pack-side content — all of
     * polytone's own files — sees) and the SERVER one (what datapack files see). The side
     * determines which registry access the editor binds when encoding/validating.
     */
    enum Side {
        /** Resource-pack side: client-synced registries (connection/level), all polytone content. */
        CLIENT_RESOURCES,
        /** Datapack side: the (integrated) server's registries. */
        SERVER_DATA
    }

    /**
     * Opens an editor for the given codec. On save, the editor:
     *  (a) writes the JSON encoding to a file (implementation chooses path UX),
     *  (b) calls {@code onSave} with the parsed value.
     * The editor must not block the calling thread.
     */
    <A> void open(SchemaCodec<A> codec, @Nullable A initial, Consumer<A> onSave);
}

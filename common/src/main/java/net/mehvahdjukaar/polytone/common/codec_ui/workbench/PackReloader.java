package net.mehvahdjukaar.polytone.common.codec_ui.workbench;

import net.mehvahdjukaar.polytone.common.codec_ui.SchemaEditor;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Hook into the running game to reload packs, so content saved by the editor becomes
 * referenceable (registry pickers, dispatch enumerations, validation) without restarting.
 * CLIENT_RESOURCES → resource reload (F3+T equivalent); SERVER_DATA → {@code /reload}.
 *
 * <p>The workbench only ever talks to {@link #get()}. Whoever embeds the tool installs a real
 * implementation (see {@code example/GameReloadHooks}); until then the {@link #NONE} default
 * keeps everything working from a bare main, with reload buttons disabled.</p>
 */
public interface PackReloader {

    boolean available(SchemaEditor.Side side);

    /**
     * Trigger a reload. May complete asynchronously on any thread; {@code onDone} receives
     * null on success or a human-readable error. Implementations must always call it exactly
     * once.
     */
    void reload(SchemaEditor.Side side, Consumer<@Nullable String> onDone);

    PackReloader NONE = new PackReloader() {
        @Override
        public boolean available(SchemaEditor.Side side) {
            return false;
        }

        @Override
        public void reload(SchemaEditor.Side side, Consumer<@Nullable String> onDone) {
            onDone.accept("No game reload hook installed");
        }
    };

    static PackReloader get() {
        return Holder.value;
    }

    static void install(PackReloader reloader) {
        Holder.value = reloader;
    }

    /** Mutable-holder workaround: interfaces can't declare non-final static fields. */
    final class Holder {
        private static volatile PackReloader value = NONE;

        private Holder() {}
    }
}

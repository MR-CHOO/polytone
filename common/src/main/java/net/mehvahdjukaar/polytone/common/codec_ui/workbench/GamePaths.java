package net.mehvahdjukaar.polytone.common.codec_ui.workbench;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Well-known folders of the running game, exposed as installable providers so this package
 * never touches Minecraft classes (same pattern as {@link PackReloader}). Providers are
 * evaluated lazily and defensively: any failure or missing game just yields null and the UI
 * falls back to its own defaults.
 */
public final class GamePaths {

    private static volatile Supplier<@Nullable Path> resourcePackDirProvider = () -> null;

    private GamePaths() {}

    /** Installed by the game-side bootstrap (see {@code example/GameReloadHooks}). */
    public static void installResourcePackDirProvider(Supplier<@Nullable Path> provider) {
        resourcePackDirProvider = provider;
    }

    /** The game's local resourcepacks folder, or null when unavailable. */
    public static @Nullable Path resourcePackDir() {
        try {
            Path path = resourcePackDirProvider.get();
            return path != null && Files.isDirectory(path) ? path : null;
        } catch (Throwable t) {
            return null;
        }
    }
}

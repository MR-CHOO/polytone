package net.mehvahdjukaar.polytone.editor;

import net.mehvahdjukaar.polytone.common.codec_ui.SchemaEditor.Side;
import net.mehvahdjukaar.polytone.common.codec_ui.workbench.GamePaths;
import net.mehvahdjukaar.polytone.common.codec_ui.workbench.PackReloader;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

import java.util.function.Consumer;

/**
 * Installs the game-backed workbench hooks: the {@link PackReloader} (CLIENT_RESOURCES →
 * {@code Minecraft.reloadResourcePacks()} (F3+T), SERVER_DATA → integrated server
 * {@code reloadResources(...)} ({@code /reload})) and the {@link GamePaths} resourcepacks
 * folder provider. Every check is defensive so the workbench keeps working (buttons
 * disabled, default folders) when launched without a game.
 */
public final class GameReloadHooks {

    private static boolean installed;

    private GameReloadHooks() {}

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        PackReloader.install(new GameReloader());
        GamePaths.installResourcePackDirProvider(() -> {
            Minecraft mc = Minecraft.getInstance();
            return mc != null ? mc.getResourcePackDirectory() : null;
        });
    }

    private static final class GameReloader implements PackReloader {

        @Override
        public boolean available(Side side) {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc == null) return false;
                return side == Side.CLIENT_RESOURCES || mc.getSingleplayerServer() != null;
            } catch (Throwable t) {
                return false; // MC not bootstrapped (bare Swing main)
            }
        }

        @Override
        public void reload(Side side, Consumer<String> onDone) {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc == null) {
                    onDone.accept("No game running");
                    return;
                }
                if (side == Side.CLIENT_RESOURCES) {
                    mc.execute(() -> mc.reloadResourcePacks()
                            .whenComplete((v, t) -> onDone.accept(t == null ? null : String.valueOf(t))));
                } else {
                    MinecraftServer server = mc.getSingleplayerServer();
                    if (server == null) {
                        onDone.accept("No integrated server running");
                        return;
                    }
                    server.execute(() -> server.reloadResources(server.getPackRepository().getSelectedIds())
                            .whenComplete((v, t) -> onDone.accept(t == null ? null : String.valueOf(t))));
                }
            } catch (Throwable t) {
                onDone.accept(String.valueOf(t));
            }
        }
    }
}

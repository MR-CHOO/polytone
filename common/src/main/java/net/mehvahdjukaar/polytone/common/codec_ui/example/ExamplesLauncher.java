package net.mehvahdjukaar.polytone.common.codec_ui.example;

import net.mehvahdjukaar.polytone.Polytone;
import net.mehvahdjukaar.polytone.common.codec_ui.swing.SwingWorkbench;
import net.mehvahdjukaar.polytone.common.codec_ui.workbench.Workbench;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;

/**
 * Tool entry point: boots the single-window {@link SwingWorkbench} with the codec library
 * from {@link CodecRegistry} and the running-game reload hooks. The name is historical —
 * this used to be a one-button-per-example launcher window.
 */
public final class ExamplesLauncher {

    private ExamplesLauncher() {}

    public static void open() {
        forceNonHeadless();
        // Force-load VanillaCodecs to guarantee companion registration runs before any
        // schema is resolved.
        VanillaCodecs.bootstrap();
        GameReloadHooks.install();
        SwingWorkbench.open(new Workbench(CodecRegistry.all()));
    }

    // Duplicated from SwingSchemaEditor (which keeps its copy package-private). NeoForge
    // launches with java.awt.headless=true and GraphicsEnvironment caches the flag; setAccessible
    // on a java.desktop private field is blocked since JDK 16, so we go through sun.misc.Unsafe
    // (jdk.unsupported is opened for reflection) to overwrite the cache directly.
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
            Polytone.LOGGER.warn("Could not disable AWT headless mode. Add JVM arg -Djava.awt.headless=false "
                    + "to your run config if the editor fails to open.", t);
        }
    }
}

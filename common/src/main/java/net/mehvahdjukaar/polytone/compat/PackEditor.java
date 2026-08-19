package net.mehvahdjukaar.polytone.compat;

/**
 * FORK-LOCAL STUB - NOT UPSTREAM. Replaces the real pack-editor bridge.
 *
 * <p>The upstream class compiles against {@code net.mehvahdjukaar:nautilus_studio-common}, which is
 * published only to the author's mavenLocal and has no public source repository, so it cannot be
 * built outside that machine. This stub keeps every caller ({@code Polytone.init},
 * {@code EditorButton}) compiling unchanged; the in-game editor button simply does nothing. The
 * {@code compat/nautilus} package is deleted for the same reason - note that excluding it via
 * {@code sourceSets { java.exclude(...) }} does NOT work, the plugin configures compilation
 * elsewhere and every error comes back.</p>
 *
 * <p>To restore: {@code git checkout upstream/1.21.11 -- common/src/main/java/net/mehvahdjukaar/polytone/compat/}
 * and revert the two commented-out dependency lines in the fabric/neoforge build scripts.</p>
 */
public final class PackEditor {

    public static void init() {
    }

    public static void open() {
    }

    public static boolean isOpen() {
        return false;
    }

    public static void close() {
    }
}

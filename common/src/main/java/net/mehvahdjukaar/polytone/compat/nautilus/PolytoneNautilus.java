package net.mehvahdjukaar.polytone.compat.nautilus;

// Local build stub: nautilus_studio has no public artifact, so the editor hooks are no-ops.
// The real version calls PolytoneCompat.init(), registers NautilusEnvironment and forwards to NautilusStudioApi.
public final class PolytoneNautilus {

    public static void init() {
    }

    public static void open() {
    }

    public static boolean isOpen() {
        return false;
    }
}

package net.mehvahdjukaar.polytone.common.codec_ui.workbench;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One workbench session: the codec library (immutable for the session) plus the currently
 * opened {@link PackWorkspace} (nullable, swappable). UI backends observe workspace changes
 * via {@link #addListener}; listeners run synchronously on whatever thread mutated the model,
 * so a Swing backend must trampoline to the EDT itself.
 */
public final class Workbench {

    private final List<CodecEntry> entries;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private @Nullable PackWorkspace workspace;

    public Workbench(List<CodecEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<CodecEntry> entries() {
        return entries;
    }

    public @Nullable PackWorkspace workspace() {
        return workspace;
    }

    public void openWorkspace(Path folder) throws IOException {
        this.workspace = PackWorkspace.open(folder);
        fire();
    }

    public void closeWorkspace() {
        this.workspace = null;
        fire();
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    private void fire() {
        for (Runnable listener : listeners) listener.run();
    }

    /**
     * The codec entry a pack file should open with, or null for "no association" (the UI then
     * falls back to a plain text editor). Prefers an entry whose side matches the file's
     * {@code assets}/{@code data} tree; an entry with a matching container but mismatched
     * side is kept as fallback for lenient/odd pack layouts.
     */
    public @Nullable CodecEntry entryFor(Path file) {
        PackWorkspace ws = workspace;
        if (ws == null) return null;
        PackWorkspace.Location location = ws.locate(file);
        if (location == null) return null;

        CodecEntry fallback = null;
        for (CodecEntry entry : entries) {
            if (!entry.matchesContainer(location.containerDir())) continue;
            if (location.side() == null || entry.side() == location.side()) return entry;
            if (fallback == null) fallback = entry;
        }
        return fallback;
    }

    /**
     * The codec entry a directory is the EXACT container root of (the {@code colormaps} folder,
     * the {@code block_modifiers} folder, ...), or null. Unlike {@link #entryFor}, this identifies
     * the folder itself — used to badge codec-root folders in the file tree — and matches only the
     * root, not files or sub-folders within it.
     */
    public @Nullable CodecEntry entryForContainer(Path dir) {
        PackWorkspace ws = workspace;
        if (ws == null) return null;
        // Treat the folder as a container by locating a hypothetical file inside it.
        PackWorkspace.Location location = ws.locate(dir.resolve("_probe"));
        if (location == null) return null;
        String container = location.containerDir();
        if (container.isEmpty()) return null;
        for (CodecEntry entry : entries) {
            String c = entry.containerDir();
            if (c == null || c.isEmpty()) continue;
            // Match the exact container root, tolerating either the pack being opened ABOVE assets/
            // (container carries an extra prefix) or INSIDE the polytone/ dir (container is a suffix
            // of the entry's dir).
            if (container.equals(c) || container.endsWith("/" + c) || c.endsWith("/" + container)) {
                return entry;
            }
        }
        return null;
    }
}

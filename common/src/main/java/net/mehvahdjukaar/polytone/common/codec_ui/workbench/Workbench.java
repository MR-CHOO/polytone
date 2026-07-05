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
}

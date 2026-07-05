package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.nio.file.Path;

/**
 * Contract between {@link SwingWorkbench} and the panels it hosts as editor tabs
 * (codec form editors, plain text editors, image previews...). Keeps the shell agnostic
 * of what each tab actually edits.
 */
interface WorkbenchTab {

    JComponent component();

    /** Current tab title — implementations prepend a dirty marker themselves. */
    String title();

    /** File this tab is bound to, if any (used for tooltips and dedup). */
    @Nullable Path file();

    boolean isDirty();

    /**
     * Persist this tab's content. Returns false if saving failed or was cancelled — the
     * shell then aborts whatever prompted it (e.g. a close). Read-only tabs return true.
     */
    boolean save();

    /** Notifies whenever title/dirty state may have changed. May fire on any thread. */
    void setStateListener(Runnable listener);

    /** Release resources (timers...). Called exactly once when the tab is closed. */
    void dispose();
}

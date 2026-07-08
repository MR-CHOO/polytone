package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import javax.swing.JLabel;
import java.awt.Font;
import java.util.function.Consumer;

/**
 * Factory for theme-reactive {@link JLabel}s. Replaces the recurring
 * {@code new JLabel(text) { updateUI() { super.updateUI(); setForeground(...); setFont(...); } }}
 * anonymous-subclass idiom scattered across the swing package: the styling must live in
 * {@code updateUI()} so a live light/dark theme switch (or zoom) re-derives colors and fonts,
 * but hand-rolling that subclass per label is pure boilerplate.
 *
 * <p>The style {@link Consumer} runs on every {@code updateUI()} — including the one fired from
 * the {@code JLabel} constructor before the field is set, which is why {@code updateUI()}
 * null-guards it and the constructor applies it once explicitly afterwards.</p>
 */
final class StyledLabels {

    private StyledLabels() {}

    /** Muted secondary-text foreground, default font. */
    static JLabel muted(String text) {
        return of(text, l -> l.setForeground(EditorOps.mutedColor()));
    }

    /** Muted foreground at the small (−1) label size. */
    static JLabel mutedSmall(String text) {
        return of(text, l -> {
            l.setFont(UiScale.labelFont(Font.PLAIN, -1f));
            l.setForeground(EditorOps.mutedColor());
        });
    }

    /** Accent foreground at the small (−1) label size. */
    static JLabel accentSmall(String text) {
        return of(text, l -> {
            l.setFont(UiScale.labelFont(Font.PLAIN, -1f));
            l.setForeground(EditorOps.accentColor());
        });
    }

    /** Default foreground at the small (−1) label size. */
    static JLabel small(String text) {
        return of(text, l -> l.setFont(UiScale.labelFont(Font.PLAIN, -1f)));
    }

    /** A label that re-applies {@code style} on every theme/zoom change. */
    static JLabel of(String text, Consumer<JLabel> style) {
        return new StyledLabel(text, style);
    }

    private static final class StyledLabel extends JLabel {
        private final Consumer<JLabel> style;

        StyledLabel(String text, Consumer<JLabel> style) {
            super(text);
            this.style = style;
            style.accept(this); // super()'s early updateUI() was a no-op (field still null)
        }

        @Override public void updateUI() {
            super.updateUI();
            if (style != null) style.accept(this); // null during super() constructor
        }
    }
}

package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.function.Supplier;

/**
 * Loads icons from the bundled <a href="https://lucide.dev">Lucide</a> pack (ISC, see
 * {@code resources/polytone/codec_ui/icons/LICENSE.txt}) as {@link FlatSVGIcon}s — vector, so
 * they stay crisp at any UI scale, with zero raster assets.
 *
 * <p>Lucide glyphs stroke in {@code currentColor}; we recolor each icon at paint time —
 * to the host component's foreground by default, or to a live color-supplier tint for the
 * few semantically colored actions (accent add, green game-sync). Tinted icons still dim
 * to the disabled color when their button is disabled, and every color resolves per paint
 * so a light/dark theme switch just works.</p>
 */
final class WorkbenchIcons {

    private WorkbenchIcons() {}

    private static final String DIR = "polytone/codec_ui/icons/";
    private static final int SIZE = 18; // logical px; FlatSVGIcon scales for HiDPI

    static Icon folder()    { return themed("folder"); }
    static Icon file()      { return themed("file"); }
    static Icon filePlus()  { return themed("file-plus"); }
    static Icon refresh()   { return themed("refresh-cw"); }
    static Icon database()  { return themed("database"); }
    static Icon layers()    { return themed("layers"); }
    static Icon search()    { return themed("search"); }
    static Icon save()      { return themed("save"); }
    static Icon trash()     { return themed("trash-2"); }
    static Icon sun()       { return themed("sun"); }
    static Icon moon()      { return themed("moon"); }
    static Icon copy()      { return themed("copy"); }
    static Icon zoomIn()    { return themed("zoom-in"); }
    static Icon zoomOut()   { return themed("zoom-out"); }

    /** Accent glyphs marking embedded-language fields: ƒ(x) = expression, &lt;/&gt; = raw JSON. */
    static Icon fx()        { return new ThemedSvgIcon("square-function", SIZE, EditorOps::accentColor); }
    static Icon codeGlyph() { return new ThemedSvgIcon("code", SIZE, EditorOps::accentColor); }

    /** Status-bar state glyphs, tinted to their semantic color. */
    static Icon checkTinted() { return new ThemedSvgIcon("check", SIZE, EditorOps::successColor); }
    static Icon xTinted()     { return new ThemedSvgIcon("x", SIZE, EditorOps::errorColor); }

    /** Accent-colored plus, matching the list rail. */
    static Icon plusAccent() { return new ThemedSvgIcon("plus", SIZE, EditorOps::accentColor); }

    /** Green "sync with the game" tints — the reload pair stands out from neutral tools. */
    static Icon refreshTinted()  { return new ThemedSvgIcon("refresh-cw", SIZE, EditorOps::successColor); }
    static Icon databaseTinted() { return new ThemedSvgIcon("database", SIZE, EditorOps::successColor); }

    /** Any bundled glyph at a custom logical size (empty-state art …); follows foreground. */
    static Icon sized(String name, int size) { return new ThemedSvgIcon(name, size, null); }

    /** Amber dot marking tabs with unsaved changes. */
    static Icon dirtyDot() { return DIRTY_DOT; }

    private static final Icon DIRTY_DOT = new Icon() {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(EditorOps.warningColor()); // resolved per paint — theme-switch safe
            int d = UiScale.px(8);
            g2.fillOval(x + (getIconWidth() - d) / 2, y + (getIconHeight() - d) / 2, d, d);
            g2.dispose();
        }

        @Override public int getIconWidth() { return UiScale.px(10); }
        @Override public int getIconHeight() { return UiScale.px(10); }
    };

    private static Icon themed(String name) {
        return new ThemedSvgIcon(name, SIZE, null);
    }

    /**
     * Wraps a {@link FlatSVGIcon} and recolors it on paint: disabled host → disabled text
     * color; {@code tint} supplier when given; else the host's live foreground. The
     * underlying icon caches its render and only re-rasterizes when the color changes.
     */
    private static final class ThemedSvgIcon implements Icon {
        private final FlatSVGIcon base;
        private final @Nullable Supplier<Color> tint;
        private Color applied;

        ThemedSvgIcon(String name, int size, @Nullable Supplier<Color> tint) {
            this.base = new FlatSVGIcon(DIR + name + ".svg").derive(size, size);
            this.tint = tint;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Color color;
            if (c != null && !c.isEnabled()) {
                color = UIManager.getColor("Button.disabledText");
                if (color == null) color = EditorOps.mutedColor();
            } else if (tint != null) {
                color = tint.get();
            } else {
                color = c != null && c.getForeground() != null ? c.getForeground()
                        : UIManager.getColor("Label.foreground");
            }
            if (color != null && !color.equals(applied)) {
                Color target = color;
                base.setColorFilter(new FlatSVGIcon.ColorFilter(ignored -> target));
                applied = color;
            }
            base.paintIcon(c, g, x, y);
        }

        @Override public int getIconWidth() { return base.getIconWidth(); }
        @Override public int getIconHeight() { return base.getIconHeight(); }
    }
}

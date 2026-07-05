package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import com.formdev.flatlaf.extras.FlatSVGIcon;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;

/**
 * Loads icons from the bundled <a href="https://lucide.dev">Lucide</a> pack (ISC, see
 * {@code resources/polytone/codec_ui/icons/LICENSE.txt}) as {@link FlatSVGIcon}s — vector, so
 * they stay crisp at any UI scale, with zero raster assets.
 *
 * <p>Lucide glyphs stroke in {@code currentColor}; we recolor each icon to the host
 * component's foreground at paint time so it dims when a button is disabled and flips
 * automatically on a light/dark theme switch.</p>
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

    /** Accent-colored plus, matching the list rail. */
    static Icon plusAccent() { return new ThemedSvgIcon("plus", EditorOps.accentColor()); }

    private static Icon themed(String name) {
        return new ThemedSvgIcon(name, null);
    }

    /**
     * Wraps a {@link FlatSVGIcon} and recolors it on paint: to {@code fixed} when set, else to
     * the host component's live foreground. The underlying icon caches its render and only
     * re-rasterizes when the resolved color actually changes.
     */
    private static final class ThemedSvgIcon implements Icon {
        private final FlatSVGIcon base;
        private final Color fixed;
        private Color applied;

        ThemedSvgIcon(String name, Color fixed) {
            this.base = new FlatSVGIcon(DIR + name + ".svg").derive(SIZE, SIZE);
            this.fixed = fixed;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Color color = fixed != null ? fixed
                    : (c != null && c.getForeground() != null ? c.getForeground()
                    : UIManager.getColor("Label.foreground"));
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

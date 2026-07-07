package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import com.formdev.flatlaf.FlatLaf;
import com.google.gson.JsonElement;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.mehvahdjukaar.polytone.common.codec_ui.SchemaEditor.Side;
import org.jetbrains.annotations.Nullable;

import javax.swing.UIManager;
import java.awt.Color;

/**
 * Shared editor plumbing: registry-aware ops per {@link Side} and small theme helpers.
 * Package-private — this is glue between the Swing widgets and the game, not API.
 */
final class EditorOps {

    private EditorOps() {}

    /**
     * Registry-aware JSON ops for the given side. Codecs over datapack registries
     * ({@code worldgen/biome}, dimension types, ...) fail with plain JsonOps
     * ("Can't access registry ..."); we bind the best available registry view:
     * <ol>
     *   <li>SERVER_DATA with an integrated server running → the server's registries;</li>
     *   <li>CLIENT_RESOURCES (or no server) in a world → the client-synced registries —
     *       this is what polytone's own resource-pack-side files see;</li>
     *   <li>no world at all (bare launcher) → {@code VanillaRegistries.createLookup()},
     *       the full vanilla worldgen content (built once, cached — it's expensive);</li>
     *   <li>if even that fails → plain JsonOps (registry codecs will show their error).</li>
     * </ol>
     */
    static DynamicOps<JsonElement> buildOps(Side side) {
        net.minecraft.core.HolderLookup.Provider provider = null;
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null) {
                if (side == Side.SERVER_DATA && mc.getSingleplayerServer() != null) {
                    provider = mc.getSingleplayerServer().registryAccess();
                } else if (mc.level != null) {
                    provider = mc.level.registryAccess();
                } else if (mc.getConnection() != null) {
                    provider = mc.getConnection().registryAccess();
                }
            }
        } catch (Throwable ignored) {
            // Not in a client environment (bare launcher main) — fall through.
        }
        if (provider == null) provider = vanillaLookup();
        if (provider != null) {
            try {
                return provider.createSerializationContext(JsonOps.INSTANCE);
            } catch (Throwable t) {
                UiLog.get().warn("[codec_ui] could not create registry ops, falling back to plain JsonOps", t);
            }
        }
        return JsonOps.INSTANCE;
    }

    private static net.minecraft.core.HolderLookup.@Nullable Provider vanillaLookup;
    private static boolean vanillaLookupFailed;

    private static net.minecraft.core.HolderLookup.@Nullable Provider vanillaLookup() {
        if (vanillaLookup == null && !vanillaLookupFailed) {
            try {
                vanillaLookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
            } catch (Throwable t) {
                vanillaLookupFailed = true;
                UiLog.get().warn("[codec_ui] VanillaRegistries lookup unavailable", t);
            }
        }
        return vanillaLookup;
    }

    /**
     * The ONE error red, per theme. Deliberately NOT {@code UIManager "Actions.Red"} — some
     * FlatLaf themes define that as a muted/desaturated red that reads "faded" and drifts between
     * components. A single fixed vivid red keeps every error indicator (banners, status lines,
     * required {@code *}, validity pills, borders) identical and legible on both themes.
     */
    static Color errorColor() {
        return FlatLaf.isLafDark() ? new Color(0xFF5C5C) : new Color(0xC0392B);
    }

    /** Muted foreground for secondary text — adapts to dark/light L&F. */
    static Color mutedColor() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : new Color(0x999999);
    }

    /** Positive / "synced with the game" green (reload actions). */
    static Color successColor() {
        Color c = UIManager.getColor("Actions.Green");
        return c != null ? c : new Color(0x59A869);
    }

    /** Pending-changes amber — the unsaved-tab dot. */
    static Color warningColor() {
        Color c = UIManager.getColor("Actions.Yellow");
        return c != null ? c : new Color(0xE8AF3B);
    }

    // -------------------- Theme accent + surface layering --------------------

    /**
     * Theme accent, split by use because no single purple has enough contrast both ways:
     * <ul>
     *   <li>{@link #accentFillHex()} — button FILLS carrying white text (New Content, the
     *       default Save button via {@code @accentColor}): a deep violet, ~5:1+ vs white.</li>
     *   <li>{@link #accentColor()} — accent TEXT/GLYPHS sitting on the theme surface (brand,
     *       kind chip, ƒ(x) glyphs): lighter on dark so it reads, deep on light.</li>
     * </ul>
     * The old single {@code #8B5CF6} washed out as text on light backgrounds (~3:1) and as a
     * white-text fill (~4:1). Everything reads these live, so a theme switch re-resolves.
     */
    static String accentFillHex() {
        return FlatLaf.isLafDark() ? ACCENT_FILL_DARK_HEX : ACCENT_FILL_LIGHT_HEX;
    }

    // Toned down from the brighter #7C3AED (read as too neon on the filled New Content button)
    // to a deeper violet that still stands out cleanly against the dark chrome.
    static final String ACCENT_FILL_DARK_HEX = "#6D28D9";
    static final String ACCENT_FILL_LIGHT_HEX = "#6D28D9";

    // ---- Structured two-tone surface palette -------------------------------------------------
    // ONE source of truth, defined for BOTH themes so light and dark stay coherent and every
    // surface can be driven off the SAME UIManager keys (see SwingSchemaEditor.applyUiDefaults).
    // Two tones per theme:
    //   panel  — panels / chrome / lists / trees / tabs ("options" surface)
    //   editor — code / JSON / input wells (the deepest surface)
    // Dark is the requested cool violet-grey; light is a soft neutral pair aligned with FlatLight.
    static final int PANEL_BG_DARK   = 0x363841;
    static final int EDITOR_BG_DARK  = 0x292b32;
    static final int PANEL_BG_LIGHT  = 0xF3F3F6;
    static final int EDITOR_BG_LIGHT = 0xFFFFFF;
    static final int RAIL_BG_DARK    = 0x2F313A; // activity rail — between panel and editor
    static final int RAIL_BG_LIGHT   = 0xEAEAEF;

    /** Hex string form (for FlatLaf {@code @background} seeding). */
    static String panelBgHex() {
        return FlatLaf.isLafDark() ? "#363841" : "#F3F3F6";
    }

    /** App / panel / chrome surface for the active theme. */
    static Color panelBg() {
        return new Color(FlatLaf.isLafDark() ? PANEL_BG_DARK : PANEL_BG_LIGHT);
    }

    /** Code/JSON/input-well surface for the active theme (the deepest tone). */
    static Color editorSurface() {
        return new Color(FlatLaf.isLafDark() ? EDITOR_BG_DARK : EDITOR_BG_LIGHT);
    }

    /** Activity-rail surface for the active theme. */
    static Color railBg() {
        return new Color(FlatLaf.isLafDark() ? RAIL_BG_DARK : RAIL_BG_LIGHT);
    }

    /** The accent for hand-drawn text/glyph touches (brand text, chips, gutters). */
    static Color accentColor() {
        return FlatLaf.isLafDark() ? new Color(0xA78BFA) : new Color(0x6D28D9);
    }

    /**
     * Panel background nudged by {@code delta} in HSB brightness (−1..1): positive
     * lifts a surface (card/header/selected tab), negative sinks it. Gives the flat
     * dark theme a sense of depth without hard-coding hex values per theme.
     */
    static Color surface(float delta) {
        return shiftBrightness(UIManager.getColor("Panel.background"), delta);
    }

    /** Subtle divider/border color for the current theme. */
    static Color dividerColor() {
        Color c = UIManager.getColor("Component.borderColor");
        return c != null ? c : surface(0.12f);
    }

    /** Linear blend a→b by t (0..1) — tinted pills/banners without alpha compositing. */
    static Color mix(Color a, Color b, float t) {
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    private static Color shiftBrightness(@Nullable Color base, float delta) {
        if (base == null) base = new Color(0x3C3F41);
        float[] hsb = Color.RGBtoHSB(base.getRed(), base.getGreen(), base.getBlue(), null);
        float b = Math.max(0f, Math.min(1f, hsb[2] + delta));
        return new Color(Color.HSBtoRGB(hsb[0], hsb[1], b));
    }
}

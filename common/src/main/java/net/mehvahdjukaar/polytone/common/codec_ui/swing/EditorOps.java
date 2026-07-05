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

    /** Error/warning color that reads on both light and dark themes. */
    static Color errorColor() {
        Color c = UIManager.getColor("Actions.Red");
        if (c != null) return c;
        // #FF6B6B reads on dark; #C0392B on light. Pick by L&F dark flag.
        return FlatLaf.isLafDark() ? new Color(0xFF6B6B) : new Color(0xC0392B);
    }

    /** Muted foreground for secondary text — adapts to dark/light L&F. */
    static Color mutedColor() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : new Color(0x999999);
    }

    // -------------------- Theme accent + surface layering --------------------

    /**
     * Single accent color — seeded into FlatLaf via {@code @accentColor} so buttons,
     * focus rings, selection and the tab underline all share it. Kept here as the one
     * source of truth; the bootstrap reads {@link #ACCENT_HEX}, widgets read
     * {@link #accentColor()}.
     */
    static final String ACCENT_HEX = "#4C8DF6";

    /** The accent as a {@link Color} for hand-drawn touches (brand text, gutters). */
    static Color accentColor() {
        return Color.decode(ACCENT_HEX);
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

    private static Color shiftBrightness(@Nullable Color base, float delta) {
        if (base == null) base = new Color(0x3C3F41);
        float[] hsb = Color.RGBtoHSB(base.getRed(), base.getGreen(), base.getBlue(), null);
        float b = Math.max(0f, Math.min(1f, hsb[2] + delta));
        return new Color(Color.HSBtoRGB(hsb[0], hsb[1], b));
    }
}

package net.mehvahdjukaar.polytone.common;

import net.mehvahdjukaar.polytone.Polytone;

import java.util.Arrays;

// TEMPORARY stutter probe - not for upstream, never commit. Times the client loop, Sodium's terrain steps and
// Polytone's per-frame work, logs every frame over SLOW_MS with its breakdown, and an average every 10s.
public final class FrameProbe {
    // top level: these don't nest in each other, so frame - their sum = time nothing here measured
    public static final int TICK = 0, PACKETS = 1, TASKS = 2, TEXTURES = 3, SOUND = 4, ACQUIRE = 5, LEVEL_UPDATE = 6,
            EXEC_PENDING = 7, GAME_RENDER = 8, BLIT = 9, GPU_WAIT = 10, PRESENT = 11, END_FRAME = 12, LIMITER = 13;
    // nested inside the above
    public static final int POLY_TICK = 14, RENDER_LEVEL = 15, UNIFORMS = 16, SHADOWS = 17, VIEWPOINTS = 18,
            SURFACE_MAP = 19, CHAIN_GRAPH = 20, CHAINS_AFTER_HAND = 21,
            SODIUM_TERRAIN = 22, SODIUM_UPLOAD = 23, SODIUM_UPDATE = 24, SODIUM_FLIP = 25;
    private static final int TOP_LEVEL = 14;
    private static final String[] NAMES = {"client tick", "packets", "main-thread tasks", "texture tick", "sound",
            "acquire swapchain", "level update", "render pending tasks", "GameRenderer.render", "blit to surface",
            "gpu wait", "present", "endFrame", "fps limiter",
            "  (polytone tick)", "  (renderLevel)", "  (poly uniforms)", "  (poly shadow map)", "  (poly viewpoints)",
            "  (poly surface map)", "  (poly chain graph)", "  (poly chains after hand)",
            "  (sodium setupTerrain)", "  (sodium mesh upload)", "  (sodium updateChunks)", "  (sodium cleanupAndFlip)"};
    private static final double SLOW_MS = 15;

    private static final long[] NANOS = new long[NAMES.length];
    private static final long[] TOTAL = new long[NAMES.length + 1];
    private static final StringBuilder NOTES = new StringBuilder();
    private static long frameStart, windowStart, windowFrames, windowSlow;
    private static double windowMax;

    public static long now() {
        return System.nanoTime();
    }

    public static long lap(int section, long since) {
        long n = System.nanoTime();
        NANOS[section] += n - since;
        return n;
    }

    public static void note(String s) {
        NOTES.append(" | ").append(s);
    }

    // Minecraft.renderFrame HEAD: one full loop (ticks + render + gpu wait) since the last call
    public static void frame() {
        long n = System.nanoTime();
        if (frameStart != 0) {
            long frame = n - frameStart;
            double ms = frame / 1e6;
            long measured = 0;
            for (int i = 0; i < TOP_LEVEL; i++) measured += NANOS[i];
            long other = frame - measured;
            windowFrames++;
            windowMax = Math.max(windowMax, ms);
            if (ms >= SLOW_MS) {
                windowSlow++;
                StringBuilder sb = new StringBuilder(String.format("[FrameProbe] SLOW %.1f ms:", ms));
                for (int i = 0; i < NAMES.length; i++) {
                    if (NANOS[i] >= 300_000) sb.append(String.format(" %s %.1f,", NAMES[i].trim(), NANOS[i] / 1e6));
                }
                sb.append(String.format(" UNMEASURED %.1f", other / 1e6));
                sb.append(NOTES);
                Polytone.LOGGER.info(sb.toString());
            }
            for (int i = 0; i < NAMES.length; i++) TOTAL[i] += NANOS[i];
            TOTAL[NAMES.length] += other;
            if (n - windowStart >= 10_000_000_000L) {
                StringBuilder sb = new StringBuilder(String.format("[FrameProbe] 10s: %d frames, %d slow, max %.1f ms, avg/frame:",
                        windowFrames, windowSlow, windowMax));
                for (int i = 0; i < NAMES.length; i++) {
                    sb.append(String.format(" %s %.2f,", NAMES[i].trim(), TOTAL[i] / 1e6 / Math.max(1, windowFrames)));
                }
                sb.append(String.format(" unmeasured %.2f", TOTAL[NAMES.length] / 1e6 / Math.max(1, windowFrames)));
                Polytone.LOGGER.info(sb.toString());
                Arrays.fill(TOTAL, 0);
                windowStart = n;
                windowFrames = windowSlow = 0;
                windowMax = 0;
            }
        } else {
            windowStart = n;
        }
        Arrays.fill(NANOS, 0);
        NOTES.setLength(0);
        frameStart = n;
    }
}

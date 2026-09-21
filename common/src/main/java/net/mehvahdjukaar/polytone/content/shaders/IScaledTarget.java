package net.mehvahdjukaar.polytone.content.shaders;

import org.jspecify.annotations.Nullable;

/**
 * A post_effect internal target whose size is multiplied by a {@code scale}:
 * <pre>
 * "targets": {
 *     "half_res": { "scale": 0.5 },
 *     "small_lut": { "width": 128, "height": 128, "scale": 0.5 }
 * }
 * </pre>
 * The scale multiplies whatever size the target would otherwise have - its absolute {@code width}/{@code height}
 * where given, else the screen - so the first is half the screen and the second 64x64. Mixed onto vanilla's
 * {@code PostChainConfig.InternalTarget}. Without Polytone, vanilla ignores the field and the target keeps its
 * unscaled size.
 */
public interface IScaledTarget {

    /**
     * Pixels for a scaled size. Never below 1, since vanilla's own sizes are positive ints and a zero-sized target
     * is an error rather than an empty one. Never clamped above: vanilla allows a target larger than the screen
     * when given an absolute size, so a scale of 2 is exactly that.
     */
    static int resolve(float scale, int size) {
        return Math.max(1, Math.round(size * scale));
    }

    @Nullable
    Float polytone$getScale();

    void polytone$setScale(@Nullable Float scale);
}

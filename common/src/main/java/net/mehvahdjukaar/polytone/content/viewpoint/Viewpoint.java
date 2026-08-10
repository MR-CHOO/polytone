package net.mehvahdjukaar.polytone.content.viewpoint;

import com.mojang.serialization.Codec;
import net.mehvahdjukaar.codecui.SchemaCodec;
import net.mehvahdjukaar.codecui.SchemaRecord;
import net.mehvahdjukaar.polytone.common.expressions.impl.ISimpleExp;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

import java.util.List;
import java.util.Locale;

/**
 * One {@code polytone/viewpoints/<name>.json}: render some subset of the world, from a scripted
 * camera, into a texture that shaders read by name.
 *
 * <p><b>Placement is ABSOLUTE WORLD SPACE.</b> {@code x}/{@code y}/{@code z} are world coordinates,
 * not offsets from the player. Following the camera is then just {@code "x": "c.x()"}, while a fixed
 * point stays a literal constant — which is the direction that matters: a viewpoint pinned to a
 * light or a landmark must have a position that does NOT change every frame, or nothing about it can
 * ever be cached. (Rendering happens in camera-relative space because section origins are uploaded
 * that way, but that conversion belongs to the renderer and is invisible here. Getting that boundary
 * wrong is what made the first height-map prototype slide when the player moved vertically.)</p>
 *
 * <p>Rotations are degrees about each world axis, particle-style: {@code x_rot} is pitch,
 * {@code y_rot} yaw, {@code z_rot} roll. {@code -90} pitch looks straight down. NOTE the deliberate
 * divergence from custom particles, whose {@code x_rot}/{@code y_rot}/{@code z_rot} are RADIANS —
 * degrees wins here because every other camera-shaped value a pack will feed these ({@code c.pitch()},
 * {@code c.yaw()}, {@code sun_angle}) is already degrees.</p>
 *
 * <p>This is the barebones slice: enough for a height map. No colour capture, no shader variants, no
 * entity filters, no {@code reuse} modes, no uniform block — consumers hardcode the projection
 * constants for now. The viewpoint owns its texture internally rather than writing into a named
 * {@code post_target}, because {@code post_targets} has no {@code format} field yet and so cannot
 * express depth32.</p>
 */
public record Viewpoint(ISimpleExp x, ISimpleExp y, ISimpleExp z,
                        ISimpleExp xRot, ISimpleExp yRot, ISimpleExp zRot,
                        ISimpleExp orthographic, ISimpleExp fov,
                        ISimpleExp near, ISimpleExp far,
                        List<ChunkSectionLayer> terrainLayers,
                        int resolution,
                        String depthSampler,
                        ISimpleExp updateInterval,
                        ISimpleExp activationCondition) {

    /** Accepts the {@link ChunkSectionLayer} names verbatim — the SAME vocabulary
     * {@code block_modifiers.render_type} takes, so a block moved between layers is selected here
     * with the same word. Unknown names are rejected by the codec rather than silently ignored. */
    private static final Codec<ChunkSectionLayer> LAYER_CODEC = Codec.STRING.xmap(
            s -> ChunkSectionLayer.valueOf(s.toUpperCase(Locale.ROOT)), ChunkSectionLayer::label);

    private static final List<ChunkSectionLayer> DEFAULT_LAYERS =
            List.of(ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT);

    // ISimpleExp is a bare functional interface (() -> double), so constants are lambdas.
    private static final ISimpleExp DEFAULT_FOV = () -> 70.0;
    private static final ISimpleExp DEFAULT_NEAR = () -> 0.05;
    private static final ISimpleExp DEFAULT_FAR = () -> 256.0;

    public static final SchemaCodec<Viewpoint> CODEC = SchemaRecord.create(Viewpoint.class,
            i -> i.group(
                    i.optional("x", ISimpleExp.CODEC, ISimpleExp.ZERO, Viewpoint::x),
                    i.optional("y", ISimpleExp.CODEC, ISimpleExp.ZERO, Viewpoint::y),
                    i.optional("z", ISimpleExp.CODEC, ISimpleExp.ZERO, Viewpoint::z),
                    i.optional("x_rot", ISimpleExp.CODEC, ISimpleExp.ZERO, Viewpoint::xRot),
                    i.optional("y_rot", ISimpleExp.CODEC, ISimpleExp.ZERO, Viewpoint::yRot),
                    i.optional("z_rot", ISimpleExp.CODEC, ISimpleExp.ZERO, Viewpoint::zRot),
                    // `orthographic` IS the projection discriminator: > 0 = orthographic, that many
                    // blocks across; 0 (the default) = perspective using `fov`. A sentinel rather
                    // than an absent-optional because a zero-width ortho view is meaningless anyway.
                    i.optional("orthographic", ISimpleExp.CODEC, ISimpleExp.ZERO, Viewpoint::orthographic),
                    i.optional("fov", ISimpleExp.CODEC, DEFAULT_FOV, Viewpoint::fov),
                    i.optional("near", ISimpleExp.CODEC, DEFAULT_NEAR, Viewpoint::near),
                    i.optional("far", ISimpleExp.CODEC, DEFAULT_FAR, Viewpoint::far),
                    i.optional("terrain", LAYER_CODEC.listOf(), DEFAULT_LAYERS, Viewpoint::terrainLayers),
                    i.optional("resolution", Codec.INT, 1024, Viewpoint::resolution),
                    i.optional("depth_sampler", Codec.STRING, "", Viewpoint::depthSampler),
                    // Expression on purpose: a pack can scale the rate by config, weather or dimension.
                    // Anything rate-DEPENDENT downstream must be re-evaluated per frame, never cached
                    // at parse time, because this can change at runtime.
                    i.optional("update_interval", ISimpleExp.CODEC, ISimpleExp.ZERO, Viewpoint::updateInterval),
                    i.optional("activation_condition", ISimpleExp.CODEC, ISimpleExp.ONE, Viewpoint::activationCondition)
            ).apply(i, Viewpoint::new));

    public boolean isActive() {
        return activationCondition.evaluate() > 0;
    }

    /** &gt; 0 selects an orthographic projection of that many blocks across; 0 selects perspective. */
    public double orthographicSize() {
        return orthographic.evaluate();
    }

    /** A viewpoint nothing can sample is a no-op; the manager skips it and warns. */
    public boolean isSamplable() {
        return !depthSampler.isEmpty();
    }
}

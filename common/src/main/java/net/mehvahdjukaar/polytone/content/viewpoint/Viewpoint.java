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
 * {@code y_rot} yaw, {@code z_rot} roll. {@code 90} pitch looks straight down (Minecraft's own pitch
 * convention, so {@code "x_rot": "c.pitch()"} aims where the player looks). NOTE the deliberate
 * divergence from custom particles, whose {@code x_rot}/{@code y_rot}/{@code z_rot} are RADIANS —
 * degrees wins here because every other camera-shaped value a pack will feed these ({@code c.pitch()},
 * {@code c.yaw()}, {@code sun_angle}) is already degrees.</p>
 *
 * <p>Captures depth and colour: terrain by layer, plus entities and block entities when enabled. Colour
 * is lit but never fogged - a viewpoint's eye is nowhere near the player, so vanilla fog measured from
 * it would be meaningless; consumers fog it themselves. No shader variants yet. The viewpoint still owns its
 * texture internally rather than writing into a named {@code post_target}; {@code post_targets} gained
 * {@code format} and {@code scale}, so that is now possible but not yet done.</p>
 *
 * <p>{@code uniform_block} names a std140 block ({@link ViewpointUniforms}) publishing the matrix the
 * map was ACTUALLY rendered with, reprojected to the live camera every frame — so consumers never
 * re-derive the projection from the json's constants. There is deliberately no {@code reuse} knob:
 * reprojection is unconditional, because holding the matrix would make the addressing slide with the
 * player, which is the bug reprojection exists to fix.</p>
 */
public record Viewpoint(ISimpleExp x, ISimpleExp y, ISimpleExp z,
                        ISimpleExp xRot, ISimpleExp yRot, ISimpleExp zRot,
                        ISimpleExp orthographic, ISimpleExp fov,
                        ISimpleExp near, ISimpleExp far,
                        List<ChunkSectionLayer> terrainLayers,
                        boolean renderEntities, boolean renderBlockEntities, boolean renderCameraEntity,
                        int resolution,
                        String depthSampler, String colorSampler,
                        String uniformBlock,
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
                    // Same names as shadow_map.json. Default FALSE, unlike the shadow map: entity model
                    // state is rebuilt on the CPU per viewpoint per render - the shadow map's single
                    // biggest expense - and it scales with the number of viewpoints, so opt-in.
                    i.optional("render_entities", Codec.BOOL, false, Viewpoint::renderEntities),
                    i.optional("render_block_entities", Codec.BOOL, false, Viewpoint::renderBlockEntities),
                    // The entity the camera views from - you, or whoever you spectate. Drawn even in first
                    // person, which is what puts you in your own shadow; a near-pass capture of nearby
                    // entities wants it, a height field may not want a column where you stand.
                    i.optional("render_camera_entity", Codec.BOOL, true, Viewpoint::renderCameraEntity),
                    i.optional("resolution", Codec.INT, 1024, Viewpoint::resolution),
                    i.optional("depth_sampler", Codec.STRING, "", Viewpoint::depthSampler),
                    // What the capture looked like: lit, unfogged, alpha 0 where nothing was drawn.
                    i.optional("color_sampler", Codec.STRING, "", Viewpoint::colorSampler),
                    // GLSL uniform BLOCK name, not an instance name: the shader picks its own instance
                    // name (`} vp;`), which is what lets two viewpoints share member names.
                    i.optional("uniform_block", Codec.STRING, "", Viewpoint::uniformBlock),
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
        return !depthSampler.isEmpty() || !colorSampler.isEmpty();
    }

    public boolean hasUniformBlock() {
        return !uniformBlock.isEmpty();
    }
}

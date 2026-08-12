package net.mehvahdjukaar.polytone.content.viewpoint;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.mehvahdjukaar.polytone.Polytone;
import net.mehvahdjukaar.polytone.compat.CompatHandler;
import net.mehvahdjukaar.polytone.content.shaders.ShadowCasterVolume;
import net.mehvahdjukaar.polytone.content.shaders.sodium.SodiumShadowRenderer;
import net.mehvahdjukaar.polytone.mixins.accessor.LevelRendererShadowAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionBuffers;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * The live GPU state and render logic for ONE {@link Viewpoint}. The parsed viewpoint is immutable
 * data; everything mutable (textures, timing) lives here, keyed by viewpoint id in
 * {@link ViewpointsManager}.
 *
 * <p>Generalised from the throwaway height-map spike, which proved the two things this rests on:
 * a second geometry pass coexists with the shadow pass in one frame (the singletons it looked like
 * it would collide with are all set and cleared within each call), and the cost of the extra Sodium
 * re-cull is not measurable at two passes.</p>
 */
public class ViewpointInstance {

    private GpuTexture depthTexture = null;
    private GpuTextureView depthTextureView = null;
    private GpuTexture colorTexture = null;   // never sampled yet; a render pass requires one
    private GpuTextureView colorTextureView = null;
    private GpuBuffer projectionBuffer = null;
    private int allocatedResolution = -1;

    private long lastUpdateMs = 0L;
    private boolean hasRendered = false;
    private boolean insidePass = false;

    private final List<SectionRenderDispatcher.RenderSection> sections = new ArrayList<>();
    private final List<BlockEntity> capturedBlockEntities = new ArrayList<>();

    public GpuTextureView getDepthTexture() {
        return depthTextureView;
    }

    /**
     * Renders if due. {@code update_interval} is an EXPRESSION, so it is re-read every frame and may
     * change at runtime — never cache a decision derived from it.
     */
    public void renderIfNeeded(Viewpoint vp, GpuBufferSlice shaderFog, Camera cam) {
        if (insidePass) return; // a nested level render must not re-enter and clear our section list

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !cam.isInitialized()) return;

        double interval = vp.updateInterval().evaluate();
        long now = Util.getMillis();
        boolean due = !hasRendered || interval <= 0 || (now - lastUpdateMs) >= interval * 50.0;
        if (!due) return;

        insidePass = true;
        boolean ok = true;
        try {
            render(vp, mc, cam, cam.position(), shaderFog);
        } catch (Exception e) {
            ok = false;
            Polytone.LOGGER.error("Polytone viewpoint render failed", e);
        } finally {
            insidePass = false;
        }
        // Only a completed pass counts as rendered; a half-drawn map must not be held for a whole
        // interval.
        hasRendered = ok;
        if (ok) lastUpdateMs = now;
    }

    private void render(Viewpoint vp, Minecraft mc, Camera cam, Vec3 camPos, GpuBufferSlice shaderFog) {
        ensureTarget(vp.resolution());

        // ---- placement: ABSOLUTE world space in, camera-relative out ----------------------------
        // This subtraction is the ONLY place the two spaces meet. Packs author world coordinates;
        // the geometry on the GPU is camera-relative because section origins are uploaded that way.
        // Keeping the conversion here (rather than expecting packs to write "320 - c.y()") is what
        // makes a fixed viewpoint a literal constant, which is what makes caching it sound.
        Vector3f eye = new Vector3f(
                (float) (vp.x().evaluate() - camPos.x),
                (float) (vp.y().evaluate() - camPos.y),
                (float) (vp.z().evaluate() - camPos.z));

        // MINECRAFT's pitch convention: +90 looks DOWN, -90 up. Chosen so that pointing a viewpoint
        // where the player looks is `"x_rot": "c.pitch()"` with no negation - matching the proxy is
        // what fixes the sign. (The draft wiki page says -90 looks down; it is WRONG.)
        float pitch = (float) Math.toRadians(vp.xRot().evaluate());
        float yaw = (float) Math.toRadians(vp.yRot().evaluate());
        float roll = (float) Math.toRadians(vp.zRot().evaluate());

        float cosPitch = Mth.cos(pitch);
        Vector3f dir = new Vector3f(-Mth.sin(yaw) * cosPitch, -Mth.sin(pitch), Mth.cos(yaw) * cosPitch);
        // Straight up/down leaves the world-up degenerate as a reference; same guard the shadow map
        // uses. Roll is authored, so there is no gimbal ambiguity to resolve beyond this.
        Vector3f up = Math.abs(dir.y) > 0.99f ? new Vector3f(0, 0, 1) : new Vector3f(0, 1, 0);

        Matrix4f view = new Matrix4f().rotateZ(roll)
                .lookAlong(dir.x, dir.y, dir.z, up.x, up.y, up.z)
                .translate(-eye.x, -eye.y, -eye.z);

        // ---- projection -------------------------------------------------------------------------
        float near = (float) vp.near().evaluate();
        float far = (float) vp.far().evaluate();
        double orthoSize = vp.orthographicSize();

        Matrix4f proj;
        float lateralHalf;
        if (orthoSize > 0) {
            lateralHalf = (float) (orthoSize * 0.5);
            proj = new Matrix4f().ortho(-lateralHalf, lateralHalf, -lateralHalf, lateralHalf, near, far);
        } else {
            proj = new Matrix4f().perspective((float) Math.toRadians(vp.fov().evaluate()), 1.0f, near, far);
            // A cone doesn't fit a box; use the far-plane half-width so the cull can only ever be
            // too generous, never too tight.
            lateralHalf = (float) (far * Math.tan(Math.toRadians(vp.fov().evaluate()) * 0.5));
        }

        // The caster box is axis-symmetric around a point, so it must reach far enough to contain an
        // asymmetric near..far span (e.g. a world-pinned band seen from below it).
        float depthHalf = Math.max(Math.abs(near), Math.abs(far));
        ShadowCasterVolume volume = new ShadowCasterVolume(view, lateralHalf, depthHalf);

        collectSections(mc, volume, camPos, eye, vp.terrainLayers());

        GpuDevice device = RenderSystem.getDevice();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer bb = Std140Builder.onStack(stack, RenderSystem.PROJECTION_MATRIX_UBO_SIZE)
                    .putMat4f(proj).get();
            device.createCommandEncoder().writeToBuffer(projectionBuffer.slice(), bb);
        }
        device.createCommandEncoder().clearColorAndDepthTextures(colorTexture, 0, depthTexture, 1.0);

        RenderSystem.setShaderFog(shaderFog);

        if (CompatHandler.SODIUM) {
            capturedBlockEntities.clear();
            // Sodium owns the block entities of the re-culled set (the vanilla section meshes are
            // empty under it), so this is where they come from on that path.
            SodiumShadowRenderer.replayTerrain(mc, cam, camPos, view, proj,
                    volume, colorTexture, depthTexture, capturedBlockEntities);
            if (!vp.blockEntities()) capturedBlockEntities.clear();
        } else {
            drawTerrain(mc, view, vp.terrainLayers());
        }

        if (vp.entities() || vp.blockEntities()) {
            drawEntitiesAndBlockEntities(vp, mc, camPos, eye, view, volume, orthoSize > 0);
        } else {
            capturedBlockEntities.clear();
        }
    }

    /**
     * Entities and block entities are not part of the section meshes, so they are extracted and
     * submitted like the main pass does, with this viewpoint's matrices and output target swapped
     * onto the RenderSystem globals. Structure follows {@code ShadowMapRenderer} closely, including
     * the parts that are easy to get wrong.
     *
     * <p>WHY THIS MATTERS FOR A HEIGHT MAP: without it, a boat, a mob or a chest beside the water
     * contributes nothing to the height field, so a reflection ray passes straight through it to the
     * sky. Note the height-field caveat though — one height per column means an entity occludes
     * EVERYTHING below its top surface, which is right for anything standing on the ground and wrong
     * for anything flying.</p>
     */
    private void drawEntitiesAndBlockEntities(Viewpoint vp, Minecraft mc, Vec3 camPos, Vector3f eye,
                                              Matrix4f view, ShadowCasterVolume volume, boolean ortho) {
        ClientLevel level = mc.level;
        if (level == null) return;

        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        BlockEntityRenderDispatcher beDispatcher = mc.getBlockEntityRenderDispatcher();
        FeatureRenderDispatcher featureDispatcher = mc.gameRenderer.getFeatureRenderDispatcher();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        CameraRenderState camState = mc.levelRenderer.levelRenderState.cameraRenderState;

        // Swap this viewpoint's matrices + output target onto the globals the feature draws read.
        // EVERYTHING here is restored in the finally: an unbalanced push leaks the viewpoint's basis
        // into later frames and renders the world from it.
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(projectionBuffer.slice(),
                ortho ? ProjectionType.ORTHOGRAPHIC : ProjectionType.PERSPECTIVE);
        Matrix4fStack mvStack = RenderSystem.getModelViewStack();
        mvStack.pushMatrix();
        mvStack.set(view);
        RenderSystem.outputColorTextureOverride = colorTextureView;
        RenderSystem.outputDepthTextureOverride = depthTextureView;
        // Only depth matters for an occlusion capture, but entity shaders still evaluate diffuse
        // light; use the level setup rather than leaving whatever was last bound.
        mc.gameRenderer.getLighting().setupFor(Lighting.Entry.LEVEL);
        try {
            PoseStack poseStack = new PoseStack();

            if (vp.entities()) {
                for (Entity entity : level.entitiesForRendering()) {
                    if (entity.isSpectator()) continue;
                    AABB bb = entity.getBoundingBox();
                    float radius = (float) Math.max(bb.getXsize(), Math.max(bb.getYsize(), bb.getZsize()));
                    Vec3 c = bb.getCenter();
                    if (!volume.intersects((float) (c.x - camPos.x) - eye.x,
                            (float) (c.y - camPos.y) - eye.y,
                            (float) (c.z - camPos.z) - eye.z, radius, radius, radius)) continue;

                    float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(
                            !level.tickRateManager().isEntityFrozen(entity));
                    try {
                        EntityRenderState state = dispatcher.extractEntity(entity, partial);
                        dispatcher.submit(state, camState, state.x - camPos.x, state.y - camPos.y,
                                state.z - camPos.z, poseStack, featureDispatcher.getSubmitNodeStorage());
                    } catch (Exception e) {
                        // One broken entity renderer, called outside its usual pass, must not kill the frame.
                    }
                }
            }

            if (vp.blockEntities()) {
                float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                for (BlockEntity be : capturedBlockEntities) {
                    BlockPos pos = be.getBlockPos();
                    poseStack.pushPose();
                    poseStack.translate(pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z);
                    try {
                        var state = beDispatcher.tryExtractRenderState(be, partial, null);
                        if (state != null) {
                            beDispatcher.submit(state, poseStack, featureDispatcher.getSubmitNodeStorage(), camState);
                        }
                    } catch (Exception e) {
                        // As above.
                    }
                    poseStack.popPose();
                }
            }

            featureRenderSafely(mc, featureDispatcher, bufferSource);
        } finally {
            capturedBlockEntities.clear();
            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
            mvStack.popMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }

    /**
     * Flush what this pass submitted, and make sure NOTHING it produced survives into the main pass.
     * Both halves matter: {@code renderAllFeatures} clears the submit-node storage on its last line,
     * so a throw partway through would leave our nodes queued for the main pass to draw a second time
     * with the camera's matrices; and the feature renderers also write into the outline and crumbling
     * buffer sources, which the main {@code endBatch()} never touches. Drain all three.
     */
    private static void featureRenderSafely(Minecraft mc, FeatureRenderDispatcher featureDispatcher,
                                            MultiBufferSource.BufferSource bufferSource) {
        try {
            featureDispatcher.renderAllFeatures();
        } catch (Exception e) {
            Polytone.LOGGER.error("Error rendering polytone viewpoint features", e);
            try {
                featureDispatcher.getSubmitNodeStorage().clear();
            } catch (Exception ignored) {
            }
        }
        RenderBuffers buffers = mc.renderBuffers();
        drainSafely(bufferSource::endBatch);
        drainSafely(buffers.outlineBufferSource()::endOutlineBatch);
        drainSafely(buffers.crumblingBufferSource()::endBatch);
    }

    private static void drainSafely(Runnable endBatch) {
        try {
            endBatch.run();
        } catch (Exception e) {
            Polytone.LOGGER.error("Error flushing polytone viewpoint batch", e);
        }
    }

    private void drawTerrain(Minecraft mc, Matrix4f view, List<ChunkSectionLayer> layers) {
        if (sections.isEmpty()) return;

        GpuTextureView atlasView = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        int atlasW = atlasView.getWidth(0);
        int atlasH = atlasView.getHeight(0);

        EnumMap<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> drawsPerLayer = new EnumMap<>(ChunkSectionLayer.class);
        for (ChunkSectionLayer layer : layers) drawsPerLayer.put(layer, new ArrayList<>());
        List<DynamicUniforms.ChunkSectionInfo> infos = new ArrayList<>();
        int maxIndices = 0;
        long now = Util.getMillis();

        for (SectionRenderDispatcher.RenderSection section : sections) {
            SectionMesh mesh = section.getSectionMesh();
            BlockPos origin = section.getRenderOrigin();
            int infoIndex = -1;
            for (ChunkSectionLayer layer : layers) {
                SectionBuffers buffers = mesh.getBuffers(layer);
                if (buffers == null) continue;
                if (infoIndex == -1) {
                    infoIndex = infos.size();
                    infos.add(new DynamicUniforms.ChunkSectionInfo(new Matrix4f(view),
                            origin.getX(), origin.getY(), origin.getZ(),
                            section.getVisibility(now), atlasW, atlasH));
                }
                GpuBuffer indexBuffer;
                VertexFormat.IndexType indexType;
                if (buffers.getIndexBuffer() == null) {
                    maxIndices = Math.max(maxIndices, buffers.getIndexCount());
                    indexBuffer = null;
                    indexType = null;
                } else {
                    indexBuffer = buffers.getIndexBuffer();
                    indexType = buffers.getIndexType();
                }
                int idx = infoIndex;
                drawsPerLayer.get(layer).add(new RenderPass.Draw<>(0, buffers.getVertexBuffer(), indexBuffer,
                        indexType, 0, buffers.getIndexCount(),
                        (slices, uploader) -> uploader.upload("ChunkSection", slices[idx])));
            }
        }
        if (infos.isEmpty()) return;

        GpuBufferSlice[] slices = RenderSystem.getDynamicUniforms()
                .writeChunkSections(infos.toArray(new DynamicUniforms.ChunkSectionInfo[0]));

        RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GpuBuffer sharedIndexBuffer = maxIndices == 0 ? null : sequential.getBuffer(maxIndices);
        VertexFormat.IndexType sharedIndexType = maxIndices == 0 ? null : sequential.type();

        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, true);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Polytone viewpoint terrain", colorTextureView, OptionalInt.empty(),
                depthTextureView, OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("Projection", projectionBuffer.slice()); // after the defaults: last bind wins
            pass.bindTexture("Sampler2", mc.gameRenderer.lightTexture().getTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            for (ChunkSectionLayer layer : layers) {
                List<RenderPass.Draw<GpuBufferSlice[]>> draws = drawsPerLayer.get(layer);
                if (draws == null || draws.isEmpty()) continue;
                pass.setPipeline(layer.pipeline());
                pass.bindTexture("Sampler0", atlasView, sampler);
                pass.drawMultipleIndexed(draws, sharedIndexBuffer, sharedIndexType, List.of("ChunkSection"), slices);
            }
        }
    }

    /**
     * Sections that can reach the viewpoint's volume, regardless of camera visibility. The volume is
     * centred on the VIEWPOINT, not the camera, so section centres are offset by the eye before the
     * test — the one place the generalisation actually differs from the shadow map, whose volume is
     * always camera-centred.
     *
     * <p>NOTE: only ALREADY-COMPILED meshes can be drawn. Sodium builds sections on demand by camera
     * need, so terrain the player has never looked at is simply absent until it gets built. That is a
     * build-queue limit, not a culling one, and it is inherent to drawing existing buffers.</p>
     */
    private void collectSections(Minecraft mc, ShadowCasterVolume volume, Vec3 camPos, Vector3f eye,
                                 List<ChunkSectionLayer> layers) {
        sections.clear();
        ViewArea viewArea = ((LevelRendererShadowAccessor) mc.levelRenderer).polytone$getViewArea();
        if (viewArea == null) return;

        for (SectionRenderDispatcher.RenderSection section : viewArea.sections) {
            SectionMesh mesh = section.getSectionMesh();
            if (!mesh.hasRenderableLayers()) continue;
            boolean any = false;
            for (ChunkSectionLayer layer : layers) {
                if (mesh.getBuffers(layer) != null) { any = true; break; }
            }
            if (!any) continue;

            BlockPos origin = section.getRenderOrigin();
            if (volume.intersects((float) (origin.getX() + 8 - camPos.x) - eye.x,
                    (float) (origin.getY() + 8 - camPos.y) - eye.y,
                    (float) (origin.getZ() + 8 - camPos.z) - eye.z, 8f, 8f, 8f)) {
                sections.add(section);
            }
        }
    }

    private void ensureTarget(int resolution) {
        GpuDevice device = RenderSystem.getDevice();
        if (projectionBuffer == null) {
            projectionBuffer = device.createBuffer(() -> "Polytone viewpoint projection UBO",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM, RenderSystem.PROJECTION_MATRIX_UBO_SIZE);
        }
        if (depthTexture == null || allocatedResolution != resolution) {
            closeTextures();
            allocatedResolution = resolution;
            depthTexture = device.createTexture(() -> "Polytone viewpoint depth",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    TextureFormat.DEPTH32, resolution, resolution, 1, 1);
            depthTextureView = device.createTextureView(depthTexture);
            colorTexture = device.createTexture(() -> "Polytone viewpoint color",
                    GpuTexture.USAGE_RENDER_ATTACHMENT,
                    TextureFormat.RGBA8, resolution, resolution, 1, 1);
            colorTextureView = device.createTextureView(colorTexture);
        }
    }

    private void closeTextures() {
        if (depthTexture != null) {
            depthTextureView.close();
            depthTexture.close();
            colorTextureView.close();
            colorTexture.close();
            depthTexture = null;
            depthTextureView = null;
            colorTexture = null;
            colorTextureView = null;
        }
    }

    public void close() {
        closeTextures();
        if (projectionBuffer != null) {
            projectionBuffer.close();
            projectionBuffer = null;
        }
        allocatedResolution = -1;
        hasRendered = false;
        sections.clear();
        capturedBlockEntities.clear();
    }
}

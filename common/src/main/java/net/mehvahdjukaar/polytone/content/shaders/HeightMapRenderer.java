package net.mehvahdjukaar.polytone.content.shaders;

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
import com.mojang.blaze3d.vertex.VertexFormat;
import net.mehvahdjukaar.polytone.Polytone;
import net.mehvahdjukaar.polytone.compat.CompatHandler;
import net.mehvahdjukaar.polytone.content.shaders.sodium.SodiumShadowRenderer;
import net.mehvahdjukaar.polytone.mixins.accessor.LevelRendererShadowAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionBuffers;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * SPIKE — THROWAWAY, NOT A FEATURE. Delete once milestone 0 lands.
 *
 * <p>A top-down orthographic depth pass ("height map"), hardcoded end to end. It exists to answer
 * ONE question that no amount of reading answers: <b>what breaks when a second geometry pass runs
 * in the same frame as the shadow map?</b> Everything on that path is currently a singleton — the
 * per-renderer {@code insidePass} boolean, {@link SodiumShadowRenderer}'s {@code active} /
 * {@code shadowColor} / {@code shadowDepth} / {@code terrainSampler} statics, one
 * {@code rsm.update()} + {@code markGraphDirty()} per pass, and the {@code useBlockFaceCulling}
 * save/restore. Run this WITH the shadow map on; whatever misbehaves is the specification for the
 * planner/executor split.
 *
 * <p>Deliberately hardcoded (no JSON, no codecs, no manager): the risk lives in the renderer, not
 * in the schema, and everything here is thrown away rather than generalised in place.
 *
 * <p>Geometry: an ortho box that follows the camera in XZ but is <b>pinned in absolute world Y</b>
 * (see the anchoring note in {@code render}). Depth is LINEAR (orthographic), so a consumer recovers
 * absolute height with {@code worldY = WORLD_TOP - depth * (WORLD_TOP - WORLD_BOTTOM)} — no camera
 * term, and the stored values don't change when the player moves vertically.
 */
public class HeightMapRenderer {

    // Same two opaque layers the shadow map uses. NOTE (spike finding to confirm): the vanilla path
    // draws exactly these, while the Sodium path draws ChunkSectionLayerGroup.OPAQUE, which also
    // includes CUTOUT_MIPPED - the two paths do not agree today.
    private static final ChunkSectionLayer[] LAYERS = {ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT};

    private static final float HALF_SIZE = 128f;   // ortho half-width in blocks (256 across)
    private static final int RESOLUTION = 1024;

    // The captured world band, ABSOLUTE (see the class note on anchoring). 320 -> -192 is 512 blocks
    // and contains the whole 1.21 build range (-64..320).
    private static final float WORLD_TOP = 320f;
    private static final float WORLD_BOTTOM = -192f;

    private boolean insidePass = false;

    private GpuTexture depthTexture = null;
    private GpuTextureView depthTextureView = null;
    private GpuTexture colorTexture = null;   // never sampled; a render pass needs a colour attachment
    private GpuTextureView colorTextureView = null;
    private GpuBuffer projectionBuffer = null;

    private final List<SectionRenderDispatcher.RenderSection> sections = new ArrayList<>();
    // Sodium hands back the block entities of the re-culled set; we don't draw them, but the call
    // signature wants somewhere to put them.
    private final List<BlockEntity> ignoredBlockEntities = new ArrayList<>();

    public GpuTextureView getHeightTexture() {
        return depthTextureView;
    }

    /**
     * Called from {@code LevelRendererMixin.poly$preRender} right after the shadow pass, so both
     * passes run under identical conditions (no render pass open, last frame's meshes current).
     */
    public void renderIfNeeded(GpuBufferSlice shaderFog, Camera cam) {
        if (insidePass) return;
        if (!Polytone.POST_CHAINS.anyActiveEffectUsesHeightMap()) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !cam.isInitialized()) return;

        insidePass = true;
        try {
            render(mc, cam, cam.position(), shaderFog);
        } catch (Exception e) {
            Polytone.LOGGER.error("Polytone height-map render failed", e);
        } finally {
            insidePass = false;
        }
    }

    private void render(Minecraft mc, Camera cam, Vec3 camPos, GpuBufferSlice shaderFog) {
        ensureTarget();

        // Straight down. Any up vector not parallel to the view direction works; +Z keeps world +X
        // pointing right in the image, which makes the debug view readable.
        Matrix4f view = new Matrix4f().lookAlong(0f, -1f, 0f, 0f, 0f, 1f);

        // ANCHORED IN ABSOLUTE WORLD Y. Rendering stays camera-relative (section origins are uploaded
        // that way and can't be moved), but near/far are measured from the camera DOWN the view axis,
        // so making them functions of camera Y pins the captured band to fixed world heights:
        //   near = camY - WORLD_TOP     (distance down to the top of the band; negative when the
        //                                camera is below it, which ortho allows - it just means the
        //                                band starts behind the eye)
        //   far  = camY - WORLD_BOTTOM
        // Depth 0 is therefore ALWAYS world Y = WORLD_TOP and depth 1 ALWAYS WORLD_BOTTOM, whatever
        // the player's altitude. The first version centred the box on the camera (copied from the
        // shadow cascade) and the whole map shifted when you moved vertically - which both forces
        // consumers to un-bias by camera Y and makes the map unreusable across frames, since it
        // changes when no geometry did. In viewpoint-schema terms this is the difference between
        // "y": 320 (a constant) and "y": "c.y() + 320".
        float camY = (float) camPos.y;
        float near = camY - WORLD_TOP;
        float far = camY - WORLD_BOTTOM;
        Matrix4f proj = new Matrix4f().ortho(-HALF_SIZE, HALF_SIZE, -HALF_SIZE, HALF_SIZE, near, far);

        // The caster box is symmetric around the camera, so it has to be big enough to contain the
        // (now asymmetric) band: the further the camera is from the band, the deeper it must reach.
        // Plain box, NOT narrowed with buildCasterPlanes - a height map wants the whole column
        // regardless of what the camera can see.
        float halfDepth = Math.max(Math.abs(near), Math.abs(far)) + 8f;
        ShadowCasterVolume volume = new ShadowCasterVolume(view, HALF_SIZE, halfDepth);

        collectSections(mc, volume, camPos);

        GpuDevice device = RenderSystem.getDevice();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer bb = Std140Builder.onStack(stack, RenderSystem.PROJECTION_MATRIX_UBO_SIZE)
                    .putMat4f(proj).get();
            device.createCommandEncoder().writeToBuffer(projectionBuffer.slice(), bb);
        }
        device.createCommandEncoder().clearColorAndDepthTextures(colorTexture, 0, depthTexture, 1.0);

        RenderSystem.setShaderFog(shaderFog);

        if (CompatHandler.SODIUM) {
            ignoredBlockEntities.clear();
            SodiumShadowRenderer.replayTerrain(mc, cam, camPos, view, proj,
                    volume, colorTexture, depthTexture, ignoredBlockEntities);
            ignoredBlockEntities.clear();
        } else {
            drawTerrain(mc, view);
        }
        // No entities, no block entities: terrain height is the whole point.
    }

    // Verbatim in shape from ShadowMapRenderer.drawTerrain - if that one changes, this must too
    // (which is itself an argument for the executor split this spike is scouting).
    private void drawTerrain(Minecraft mc, Matrix4f view) {
        if (sections.isEmpty()) return;

        GpuTextureView atlasView = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        int atlasW = atlasView.getWidth(0);
        int atlasH = atlasView.getHeight(0);

        EnumMap<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> drawsPerLayer = new EnumMap<>(ChunkSectionLayer.class);
        for (ChunkSectionLayer layer : LAYERS) {
            drawsPerLayer.put(layer, new ArrayList<>());
        }
        List<DynamicUniforms.ChunkSectionInfo> infos = new ArrayList<>();
        int maxIndices = 0;
        long now = Util.getMillis();

        for (SectionRenderDispatcher.RenderSection section : sections) {
            SectionMesh mesh = section.getSectionMesh();
            BlockPos origin = section.getRenderOrigin();
            int infoIndex = -1;
            for (ChunkSectionLayer layer : LAYERS) {
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
                () -> "Polytone height map terrain", colorTextureView, OptionalInt.empty(),
                depthTextureView, OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("Projection", projectionBuffer.slice()); // after the defaults: last bind wins
            pass.bindTexture("Sampler2", mc.gameRenderer.lightTexture().getTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            for (ChunkSectionLayer layer : LAYERS) {
                List<RenderPass.Draw<GpuBufferSlice[]>> draws = drawsPerLayer.get(layer);
                if (draws.isEmpty()) continue;
                pass.setPipeline(layer.pipeline());
                pass.bindTexture("Sampler0", atlasView, sampler);
                pass.drawMultipleIndexed(draws, sharedIndexBuffer, sharedIndexType, List.of("ChunkSection"), slices);
            }
        }
    }

    private void collectSections(Minecraft mc, ShadowCasterVolume volume, Vec3 camPos) {
        sections.clear();
        ViewArea viewArea = ((LevelRendererShadowAccessor) mc.levelRenderer).polytone$getViewArea();
        if (viewArea == null) return;

        for (SectionRenderDispatcher.RenderSection section : viewArea.sections) {
            if (!section.getSectionMesh().hasRenderableLayers()) continue;
            BlockPos origin = section.getRenderOrigin();
            if (volume.intersects((float) (origin.getX() + 8 - camPos.x),
                    (float) (origin.getY() + 8 - camPos.y),
                    (float) (origin.getZ() + 8 - camPos.z), 8f, 8f, 8f)) {
                sections.add(section);
            }
        }
    }

    private void ensureTarget() {
        GpuDevice device = RenderSystem.getDevice();
        if (projectionBuffer == null) {
            projectionBuffer = device.createBuffer(() -> "Polytone height map projection UBO",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM, RenderSystem.PROJECTION_MATRIX_UBO_SIZE);
        }
        if (depthTexture == null) {
            depthTexture = device.createTexture(() -> "Polytone height map depth",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    TextureFormat.DEPTH32, RESOLUTION, RESOLUTION, 1, 1);
            depthTextureView = device.createTextureView(depthTexture);
            colorTexture = device.createTexture(() -> "Polytone height map color",
                    GpuTexture.USAGE_RENDER_ATTACHMENT,
                    TextureFormat.RGBA8, RESOLUTION, RESOLUTION, 1, 1);
            colorTextureView = device.createTextureView(colorTexture);
        }
    }

    public void close() {
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
        if (projectionBuffer != null) {
            projectionBuffer.close();
            projectionBuffer = null;
        }
        sections.clear();
        ignoredBlockEntities.clear();
    }
}

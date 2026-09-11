package net.mehvahdjukaar.polytone.content.viewpoint;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

/**
 * One viewpoint's {@code uniform_block}: the matrices a shader needs to look a world position up in
 * the viewpoint's texture. Bound by the pack-declared block name to every pass whose program declares
 * it (see {@code PostChainsManager.setupExtraUniforms}), written once per frame by
 * {@link ViewpointInstance} while no render pass is open.
 *
 * <pre>
 * layout(std140) uniform PolyHeightView {   // block name = "uniform_block"
 *     mat4 ViewProj;       // camera-relative (to NOW) world -> viewpoint clip, reprojected every frame
 *     vec4 ViewDir;        // xyz = direction the viewpoint faces
 *     vec4 CamFract;       // xyz = fract(cameraPos), the world-grid anchor
 *     vec4 NearFarTexel;   // near, far, 1/width, 1/height
 *     vec4 Update;         // x = rendered this frame (0/1), y = age s, z = interval s, w = phase 0..1
 * } vp;                    // give it an INSTANCE name so two viewpoints in one shader can't collide
 * </pre>
 *
 * <p>Same std140 rule as {@code PolyGlobals}: shaders may declare only a LEADING prefix of the
 * members, so never reorder or insert — append only.</p>
 *
 * <p>{@code InvViewProj} is deliberately absent: {@code inverse()} of a uniform is loop-invariant, the
 * compiler hoists it, so a {@code #define} in the consumer is free and keeps 64 bytes per frame off
 * the upload.</p>
 *
 * <p>⚠ RE-IMPLEMENTED 2026-09-11 from the design notes ({@code viewpoints_plan.md} §7b items 1 + 4)
 * because the original (2026-08-18) was never committed — it sits in a stash on the other PC. Same
 * layout and semantics as documented there; reconcile with the original when it is pushed.</p>
 */
public class ViewpointUniforms implements AutoCloseable {

    public static final int UBO_SIZE = new Std140SizeCalculator()
            .putMat4f()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec4()
            .get();

    private final GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Polytone viewpoint UBO",
            GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM, UBO_SIZE);

    public void update(Matrix4f viewProj, Vector3f viewDir, Vector3f camFract,
                       float near, float far, int width, int height,
                       boolean rendered, float ageSeconds, float intervalSeconds, float phase) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer bb = Std140Builder.onStack(stack, UBO_SIZE)
                    .putMat4f(viewProj)
                    .putVec4(viewDir.x, viewDir.y, viewDir.z, 0f)
                    .putVec4(camFract.x, camFract.y, camFract.z, 0f)
                    .putVec4(near, far, 1f / Math.max(width, 1), 1f / Math.max(height, 1))
                    .putVec4(rendered ? 1f : 0f, ageSeconds, intervalSeconds, phase)
                    .get();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), bb);
        }
    }

    public GpuBufferSlice getSlice() {
        return buffer.slice();
    }

    @Override
    public void close() {
        buffer.close();
    }
}

package net.mehvahdjukaar.polytone.content.shaders;

import com.google.gson.JsonElement;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.mehvahdjukaar.polytone.Polytone;
import net.mehvahdjukaar.polytone.PolytoneRenderTypes;
import net.mehvahdjukaar.polytone.common.ClientFrameTicker;
import net.mehvahdjukaar.polytone.common.reloader.ContentManager;
import net.mehvahdjukaar.polytone.common.struc.AssetsFiles;
import net.mehvahdjukaar.polytone.mixins.accessor.PostChainAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.stream.Collectors;

public class PostChainsManager extends ContentManager<PostChainActivator> {

    public static final String GLOBALS_NAME = "PolyGlobals";
    public static final String SHADOW_UBO_NAME = "PolyShadow";
    public static final String SHADOW_SAMPLER_NAME = "InShadow";

    // Samplers bound at runtime rather than declared by a pipeline: the shadow map plus every viewpoint's
    // depth_sampler. Programs can compile before a level exists, so the viewpoint names come from the raw
    // jsons read at prepare time.
    public static List<String> dynamicSamplers() {
        List<String> viewpoints = Polytone.VIEWPOINTS.declaredSamplerNames();
        List<String> surfaceMap = Polytone.SURFACE_MAP.samplerNames();
        if (viewpoints.isEmpty() && surfaceMap.isEmpty()) return List.of(SHADOW_SAMPLER_NAME);
        List<String> all = new ArrayList<>(viewpoints.size() + surfaceMap.size() + 1);
        all.add(SHADOW_SAMPLER_NAME);
        all.addAll(viewpoints);
        all.addAll(surfaceMap);
        return all;
    }

    private static volatile boolean globalsDeclared = false;
    private static volatile boolean shadowUboDeclared = false;
    private static volatile boolean shadowSamplerDeclared = false;

    private PolytoneGlobalUniforms globalUniforms = null;
    private GpuBuffer emptyShadowUbo = null;

    private final List<PostChainActivator> activators = new ArrayList<>();
    private final Map<Identifier, List<Map<String, Identifier>>> samplersByPassShader = new HashMap<>();
    // Chains already reported as unsatisfiable, so the error is logged once instead of every frame. Identity-
    // based: PostChain has no id and does not override equals, and the instance is what we are gating on.
    private final Set<PostChain> warnedChains = Collections.newSetFromMap(new IdentityHashMap<>());

    private TextureTarget worldDepthSnapshot;
    private boolean worldDepthCaptured = false;

    // The chains deferred to the after-hand stage for THIS frame, in priority order. Filled by
    // addChainsToFrameGraph, consumed by runChainsAfterHand. Render thread only.
    private final List<ActiveChain> deferredAfterHand = new ArrayList<>();
    private List<Identifier> lastStagingIds = List.of();
    private int lastStagingSplit = -1;

    // See captureRenderedProjection: the bobbed projection this frame is rendered with.
    private final Matrix4f renderedProjection = new Matrix4f();
    private boolean renderedProjectionValid = false;

    public PostChainsManager() {
        super(Spec.of("Post chain", () -> PostChainActivator.CODEC)
                .wikiPage("Shaders")
                .folders("post_chains", "post_shaders"));
    }

    @Override
    protected AssetsFiles prepare(PreparableReloadListener.SharedState sharedState) {
        AssetsFiles resources = super.prepare(sharedState);
        ShaderUniformsManager.registerExpressionUniformNames(resources.jsons());
        return resources;
    }

    @Override
    protected void parseWithLevel(AssetsFiles resources, RegistryOps<JsonElement> ops, HolderLookup.Provider access) {
        synchronized (activators) {
            for (var j : parseEnabledJsons(resources.jsons(), ops)) {
                if (j != null) {
                    activators.add(j.getValue());
                }
            }
        }
    }

    @Override
    protected void resetWithLevel(boolean logOff) {
        synchronized (activators) {
            for (var a : activators) a.close();
            activators.clear();
        }
        samplersByPassShader.clear();
        // Chains are rebuilt on reload, so a pack that fixed its targets must be able to report again
        warnedChains.clear();
        // Holds PostChains a reload closes, and the staging is worth re-logging for the new set
        deferredAfterHand.clear();
        lastStagingIds = List.of();
        lastStagingSplit = -1;
    }

    private GpuBufferSlice emptyShadowUbo() {
        if (emptyShadowUbo == null) {
            emptyShadowUbo = RenderSystem.getDevice().createBuffer(() -> "Polytone empty shadow UBO",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM, PolyShadowUniforms.UBO_SIZE);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                RenderSystem.getDevice().createCommandEncoder()
                        .writeToBuffer(emptyShadowUbo.slice(), stack.calloc(PolyShadowUniforms.UBO_SIZE));
            }
        }
        return emptyShadowUbo.slice();
    }

    private PolytoneGlobalUniforms globalUniforms() {
        if (globalUniforms == null) {
            globalUniforms = new PolytoneGlobalUniforms();
        }
        return globalUniforms;
    }

    public static void onProgramLinked(Set<String> declaredUniforms) {
        if (declaredUniforms.contains(GLOBALS_NAME)) globalsDeclared = true;
        if (declaredUniforms.contains(SHADOW_UBO_NAME)) shadowUboDeclared = true;
    }

    public static void onDynamicSamplerDeclared(String name) {
        if (SHADOW_SAMPLER_NAME.equals(name)) shadowSamplerDeclared = true;
        Polytone.SURFACE_MAP.onSamplerDeclared(name);
    }

    public boolean hasAnyPassBindings() {
        return globalsDeclared || shadowUboDeclared || shadowSamplerDeclared || !samplersByPassShader.isEmpty()
                || !Polytone.VIEWPOINTS.isEmpty() || Polytone.SURFACE_MAP.hasDeclaredSamplers();
    }

    public void bindUniformBlocks(RenderPass pass, Set<String> declaredUniforms) {
        if (declaredUniforms.contains(GLOBALS_NAME)) {
            globalsDeclared = true;
            pass.setUniform(GLOBALS_NAME, globalUniforms().getSlice());
        }
        if (declaredUniforms.contains(SHADOW_UBO_NAME)) {
            GpuBufferSlice shadowSlice = Polytone.SHADOWS.renderer().getUniformsSlice();
            pass.setUniform(SHADOW_UBO_NAME, shadowSlice != null ? shadowSlice : emptyShadowUbo());
        }
        Polytone.VIEWPOINTS.bindUniformBlocks(pass, declaredUniforms);
        Polytone.SURFACE_MAP.bindUniformBlocks(pass, declaredUniforms);
    }

    public boolean anyActiveChainWantsShadowMap() {
        synchronized (activators) {
            for (var a : activators) {
                if (a.wantsShadowMap()) return true;
            }
        }
        return false;
    }

    // union of the viewpoints every currently active chain asked for
    public Set<Identifier> wantedViewpoints() {
        Set<Identifier> wanted = new HashSet<>();
        synchronized (activators) {
            for (var a : activators) wanted.addAll(a.wantedViewpoints());
        }
        return wanted;
    }

    public void registerSamplers(Identifier passShaderId, Map<String, Identifier> samplers) {
        if (samplers.isEmpty()) return;
        samplersByPassShader.computeIfAbsent(passShaderId, k -> new ArrayList<>()).add(samplers);
    }

    public void unregisterSamplers(Identifier passShaderId, Map<String, Identifier> samplers) {
        List<Map<String, Identifier>> list = samplersByPassShader.get(passShaderId);
        if (list != null) {
            list.remove(samplers);
            if (list.isEmpty()) samplersByPassShader.remove(passShaderId);
        }
    }

    public void bindSamplers(RenderPass pass, RenderPipeline pipeline, Set<String> declaredUniforms) {
        if (declaredUniforms.contains(SHADOW_SAMPLER_NAME)) {
            GpuTextureView shadowMap = Polytone.SHADOWS.renderer().getShadowTexture();
            if (shadowMap == null) {
                shadowMap = Minecraft.getInstance().getTextureManager()
                        .getTexture(MissingTextureAtlasSprite.getLocation()).getTextureView();
            }
            pass.bindTexture(SHADOW_SAMPLER_NAME, shadowMap,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        }
        Polytone.VIEWPOINTS.bindSamplers(pass, declaredUniforms);
        Polytone.SURFACE_MAP.bindSamplers(pass, declaredUniforms);
        if (samplersByPassShader.isEmpty()) return;
        List<Map<String, Identifier>> list = samplersByPassShader.get(pipeline.getFragmentShader());
        if (list == null) return;
        var textureManager = Minecraft.getInstance().getTextureManager();
        GpuSampler sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
        for (Map<String, Identifier> samplers : list) {
            for (var e : samplers.entrySet()) {
                if (!declaredUniforms.contains(e.getKey())) continue;
                GpuTextureView view = textureManager.getTexture(e.getValue()).getTextureView();
                pass.bindTexture(e.getKey(), view, sampler);
            }
        }
    }

    public void onClose() {
        synchronized (activators) {
            for (var a : activators) a.close();
        }
        deferredAfterHand.clear();
        lastStagingIds = List.of();
        lastStagingSplit = -1;
        if (globalUniforms != null) {
            globalUniforms.close();
            globalUniforms = null;
        }
        if (emptyShadowUbo != null) {
            emptyShadowUbo.close();
            emptyShadowUbo = null;
        }
        DeclaredUniforms.clearCache();
        if (worldDepthSnapshot != null) {
            worldDepthSnapshot.destroyBuffers();
            worldDepthSnapshot = null;
        }
        Polytone.POST_TARGETS.close();
    }

    // The projection vanilla actually rasterises the world with. GameRenderer.renderLevel takes
    // cameraState.projectionMatrix and multiplies the view-bob pose (and the nausea skew) into a COPY,
    // then renders with that; the CameraRenderState field keeps the un-bobbed matrix. PolyProjMat has to
    // be the bobbed one, or PolyInvViewProjMat doesn't invert the matrix the depth buffer was written
    // with and every depth reconstruction (fog, VL, shadows, AO, water) swims while walking.
    // Captured by GameRendererMixin, which runs before LevelRenderer.render in the same frame.
    public void captureRenderedProjection(Matrix4fc projection) {
        renderedProjection.set(projection);
        renderedProjectionValid = true;
    }

    // Consumes the capture: a frame that somehow didn't capture falls back to the un-bobbed matrix rather
    // than silently reusing the previous frame's.
    private Matrix4fc renderedProjectionOr(Matrix4fc fallback) {
        if (!renderedProjectionValid) return fallback;
        renderedProjectionValid = false;
        return renderedProjection;
    }

    public void updateGlobalUniforms(Matrix4fc projectionMatrix, Matrix4fc viewMatrix, float deltaTime) {
        projectionMatrix = renderedProjectionOr(projectionMatrix);
        if (!globalsDeclared && !Polytone.isDevEnv) return;
        Minecraft mc = Minecraft.getInstance();
        float sunAngle = mc.levelRenderer.levelRenderState.skyRenderState.sunAngle;
        float dayTime = (float) ClientFrameTicker.getDayTime();
        globalUniforms().update(projectionMatrix, viewMatrix, sunAngle, dayTime, deltaTime);
    }

    public void tick() {
        for (var a : activators) {
            a.refreshActive();
        }
    }

    /** An active chain paired with the id of the json that activated it - PostChain itself has no id. */
    private record ActiveChain(Identifier id, PostChain chain) {
    }

    // In ascending `priority` order: `activators` is filled from Parsed.SortedMap#entrySet, which sorts by
    // the json's optional "priority" int (ties falling back to insertion order). A pack's chain list is ONE
    // ordered program - every chain reads and rewrites minecraft:main - so this order is data flow, and
    // firstDeferrable below relies on it.
    private List<ActiveChain> activeChains() {
        ShaderManager shaderManager = Minecraft.getInstance().getShaderManager();
        List<ActiveChain> active = new ArrayList<>();
        synchronized (activators) {
            for (var a : activators) {
                PostChain chain = a.getPostChain(shaderManager);
                if (chain != null) active.add(new ActiveChain(a.postChainId(), chain));
            }
        }
        return active;
    }

    // The after-hand stage runs strictly later than the WHOLE level frame graph, so it may only take a
    // SUFFIX of the priority order: a chain is deferrable only if every chain after it is deferrable too.
    // Deferring by capability alone silently reorders the pack's program - shadow/AO composites landing on
    // top of fog, a chain reading minecraft:main after a later chain rewrote its alpha, target writers
    // running after their readers - which broke Recrafted's fog, VL, clouds and shadow face occlusion.
    // See AI Memory/polytone/after_hand_stage_order.md. The general rule: a stage boundary is only safe
    // where no chain after it in priority order needs the earlier stage.
    private static int firstDeferrable(List<ActiveChain> ordered) {
        int i = ordered.size();
        while (i > 0 && canRunAfterHand(ordered.get(i - 1).chain())) i--;
        return i;
    }

    // There is otherwise no way to see the staging, and a silent reorder costs a day of shader debugging.
    // Naming the chain that stopped the walk tells a pack author exactly which one to change to get more of
    // its stack after the hand.
    private void logStaging(List<ActiveChain> ordered, int split) {
        // Runs every frame, so the unchanged case must not allocate: compare against the last staging
        // in place and only build the message when the split or the active set actually moved.
        if (split == lastStagingSplit && sameIds(ordered, lastStagingIds)) return;
        lastStagingSplit = split;
        List<Identifier> ids = new ArrayList<>(ordered.size());
        for (ActiveChain c : ordered) ids.add(c.id());
        lastStagingIds = ids;

        String level = ordered.subList(0, split).stream().map(c -> c.id().toString())
                .collect(Collectors.joining(", "));
        String afterHand = ordered.subList(split, ordered.size()).stream().map(c -> c.id().toString())
                .collect(Collectors.joining(", "));
        Polytone.LOGGER.info("Post chains: level=[{}] after_hand=[{}]{}", level, afterHand,
                split > 0 ? " (held back by " + ordered.get(split - 1).id() + ")" : "");
    }

    private static boolean sameIds(List<ActiveChain> ordered, List<Identifier> ids) {
        if (ordered.size() != ids.size()) return false;
        for (int i = 0; i < ids.size(); i++) {
            if (!ordered.get(i).id().equals(ids.get(i))) return false;
        }
        return true;
    }

    // Level-FrameGraph placement. Runs before the first-person hand is drawn, so depth-reading chains here
    // don't see held items. Always invoked: when post_chains_after_hand is off it hosts every chain, and when
    // it is on it hosts everything up to the deferrable suffix - the vanilla sorting targets live in this
    // graph and nowhere else, so a chain reading minecraft:translucent has to run here.
    //
    // This is also where the frame's split is DECIDED, once, and stashed for runChainsAfterHand, so the two
    // stages can never disagree about who hosts what.
    public void addChainsToFrameGraph(int width, int height, LevelTargetBundle targets, FrameGraphBuilder frameGraphBuilder,
                                      GpuBufferSlice fog, CameraRenderState cameraRenderState) {
        Polytone.POST_TARGETS.ensureAllocated(width, height);
        PostChain.TargetBundle bundle = Polytone.POST_TARGETS.wrap(targets, frameGraphBuilder);

        List<ActiveChain> ordered = activeChains();
        int split = Polytone.CONFIGS.postChainsAfterHand.get() ? firstDeferrable(ordered) : ordered.size();
        logStaging(ordered, split);

        deferredAfterHand.clear();
        deferredAfterHand.addAll(ordered.subList(split, ordered.size()));

        for (ActiveChain active : ordered.subList(0, split)) {
            // An undeferrable chain that is also unsatisfiable here is skipped, but it still acted as a
            // barrier above: the split must not depend on bundleSatisfies, since a target missing at runtime
            // (Improved Transparency off) would otherwise let the suffix swallow the rest of the stack.
            if (!bundleSatisfies(bundle, active.chain(), "the level frame graph")) continue;
            active.chain().addToFrame(frameGraphBuilder, width, height, bundle);
        }
    }

    // Whether every external target a chain reads can be supplied after the level frame graph has finished.
    // Only two kinds survive that long: the main target, and our own post_targets, which are persistent
    // RenderTargets this mod owns and can re-import into any graph. The vanilla sorting targets
    // (minecraft:translucent and friends) are transient level-graph resources whose handles are dead by then,
    // so a chain touching one must stay on the level path.
    private static boolean canRunAfterHand(PostChain chain) {
        for (Identifier id : ((PostChainAccessor) chain).polytone$getExternalTargets()) {
            if (id.equals(PostChain.MAIN_TARGET_ID)) continue;
            if (Polytone.POST_TARGETS.customTargetIds().contains(id)) continue;
            return false;
        }
        return true;
    }

    // Guard against PostChain.TargetBundle#getOrThrow, which throws MID-FRAME and takes the game down. A
    // target can be declared and still be absent at runtime (the sorting targets only exist while
    // options.improvedTransparency is on), so declaration-time validation is not enough. Skip the chain and
    // say which target and which stage, once per chain, rather than crashing.
    private boolean bundleSatisfies(PostChain.TargetBundle bundle, PostChain chain, String stage) {
        for (Identifier id : ((PostChainAccessor) chain).polytone$getExternalTargets()) {
            if (bundle.get(id) == null) {
                if (warnedChains.add(chain)) {
                    Polytone.LOGGER.error(
                            "Skipping a Polytone post chain: it reads target {}, which is not available in {}. " +
                            "Check that the target exists and, for vanilla sorting targets, that Improved " +
                            "Transparency is enabled.", id, stage);
                }
                return false;
            }
        }
        return true;
    }

    public void snapshotWorldDepth(RenderTarget main) {
        worldDepthCaptured = false;
        // Nothing deferred means no after-hand stage at all, so skip the full-screen depth copy and the
        // combine that would follow it.
        if (deferredAfterHand.isEmpty()) return;
        ensureSnapshotSized(main.width, main.height);
        worldDepthSnapshot.copyDepthFrom(main);
        worldDepthCaptured = true;
    }

    // Fold the saved world depth back into the main depth, then run every chain this stage can host.
    public void runChainsAfterHand(RenderTarget main, GraphicsResourceAllocator resourceAllocator) {
        if (!worldDepthCaptured) return;
        worldDepthCaptured = false;
        // Exactly the suffix addChainsToFrameGraph deferred this frame - never re-derived, so the two stages
        // cannot disagree and no chain can be hosted twice or dropped.
        if (deferredAfterHand.isEmpty()) return;

        combineWorldDepthIntoMain(main);

        // Build the graph ourselves instead of calling PostChain#process per chain. process() hands the chain
        // a bundle holding ONLY minecraft:main, so any chain writing to one of our post_targets - which is
        // most of them, since a pack declares those as the pass output rather than as an internal target -
        // died on getOrThrow. Wrapping the bundle the same way addChainsToFrameGraph does splices them back in,
        // and sharing one graph across all the chains also lets them read each other's outputs, exactly as
        // they can on the level path.
        Polytone.POST_TARGETS.ensureAllocated(main.width, main.height);
        FrameGraphBuilder builder = new FrameGraphBuilder();
        var mainHandle = builder.importExternal(PostChain.MAIN_TARGET_ID.toString(), main);
        PostChain.TargetBundle bundle = Polytone.POST_TARGETS.wrap(
                PostChain.TargetBundle.of(PostChain.MAIN_TARGET_ID, mainHandle), builder);

        boolean any = false;
        for (ActiveChain active : deferredAfterHand) {
            if (!bundleSatisfies(bundle, active.chain(), "the after-hand stage")) continue;
            active.chain().addToFrame(builder, main.width, main.height, bundle);
            any = true;
        }
        if (any) builder.execute(resourceAllocator);
    }

    private void ensureSnapshotSized(int width, int height) {
        if (worldDepthSnapshot == null) {
            worldDepthSnapshot = new TextureTarget("Polytone World Depth Snapshot", width, height, true,
                    GpuFormat.RGBA8_UNORM);
        } else if (worldDepthSnapshot.width != width || worldDepthSnapshot.height != height) {
            worldDepthSnapshot.resize(width, height);
        }
    }

    private void combineWorldDepthIntoMain(RenderTarget main) {
        GpuTextureView worldDepth = worldDepthSnapshot.getDepthTextureView();
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Polytone depth combine",
                main.getColorTextureView(), Optional.empty(),
                main.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(PolytoneRenderTypes.DEPTH_COMBINE_PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("InSampler", worldDepth, sampler);
            pass.draw(3, 1, 0, 0);
        }
    }
}

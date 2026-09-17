package net.mehvahdjukaar.polytone.content.shaders;

import com.google.gson.JsonElement;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.mehvahdjukaar.codecui.SchemaCodec;
import net.mehvahdjukaar.codecui.SchemaRecord;
import net.mehvahdjukaar.polytone.Polytone;
import net.mehvahdjukaar.polytone.common.reloader.ContentManager;
import net.mehvahdjukaar.polytone.common.struc.AssetsFiles;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class PostTargetsManager extends ContentManager<PostTargetsManager.TargetSpec> {

    // Colour formats only: the depth attachment is use_depth's business, not this field's.
    private static final Codec<GpuFormat> FORMAT_CODEC = Codec.STRING.comapFlatMap(s -> {
        GpuFormat format;
        try {
            format = GpuFormat.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DataResult.error(() -> "Unknown texture format '" + s + "'");
        }
        if (!format.hasColorAspect()) {
            return DataResult.error(() -> "Texture format '" + s + "' has no colour aspect, so it cannot back a post target");
        }
        return DataResult.success(format);
    }, f -> f.name().toLowerCase(Locale.ROOT));

    /**
     * @param scale  fraction of the frame, when no absolute size is given. Aspect follows the frame,
     *               since both axes take the same factor.
     * @param format colour format of the target's texture; the default matches the main target.
     */
    public record TargetSpec(Optional<Integer> width, Optional<Integer> height, Optional<Float> scale,
                             GpuFormat format, boolean useDepth) {
        static final SchemaCodec<TargetSpec> CODEC = SchemaRecord.create(TargetSpec.class, i -> i.group(
                i.optional("width", Codec.INT, TargetSpec::width),
                i.optional("height", Codec.INT, TargetSpec::height),
                i.optional("scale", Codec.floatRange(0.01f, 1.0f), TargetSpec::scale),
                i.optional("format", FORMAT_CODEC, GpuFormat.RGBA8_UNORM, TargetSpec::format),
                i.optional("use_depth", Codec.BOOL, false, TargetSpec::useDepth)
        ).apply(i, TargetSpec::new));

        public int resolveWidth(int frameWidth) {
            return resolve(width, frameWidth);
        }

        public int resolveHeight(int frameHeight) {
            return resolve(height, frameHeight);
        }

        // Precedence: an absolute size wins, then scale x frame, then the full frame. Per axis, so
        // "width": 512 with a scale still pins the width and scales only the height.
        private int resolve(Optional<Integer> absolute, int frameSize) {
            if (absolute.isPresent()) return Math.max(1, absolute.get());
            if (scale.isPresent()) return Math.max(1, Math.round(frameSize * scale.get()));
            return frameSize;
        }
    }

    private volatile Map<Identifier, TargetSpec> specs = Map.of();
    private volatile boolean dirty = false;
    private final Map<Identifier, RenderTarget> targets = new HashMap<>();

    public PostTargetsManager() {
        super(Spec.of("Post target", () -> TargetSpec.CODEC)
                .wikiPage("Shaders")
                .folders("post_targets"));
    }

    @Override
    protected void parseWithLevel(AssetsFiles resources, RegistryOps<JsonElement> ops, HolderLookup.Provider access) {
        Map<Identifier, TargetSpec> parsed = new HashMap<>();
        for (var entry : resources.jsons().entrySet()) {
            TargetSpec.CODEC.parse(ops, entry.getValue())
                    .resultOrPartial(err -> Polytone.LOGGER.error("Failed to parse post target {}: {}", entry.getKey(), err))
                    .ifPresent(spec -> parsed.put(entry.getKey(), spec));
        }
        this.specs = Map.copyOf(parsed);
        this.dirty = true;
    }

    @Override
    protected void resetWithLevel(boolean logOff) {
        this.specs = Map.of();
        this.dirty = true;
    }

    public Set<Identifier> allowedTargets() {
        Map<Identifier, TargetSpec> specs = this.specs;
        if (specs.isEmpty()) return LevelTargetBundle.SORTING_TARGETS;
        Set<Identifier> set = new HashSet<>(LevelTargetBundle.SORTING_TARGETS);
        set.addAll(specs.keySet());
        return set;
    }

    // Targets without an explicit size follow the frame, scaled by "scale" if they declare one. Runs every
    // frame, so a scaled target follows a window resize for free - and a shader reading one MUST use
    // textureSize(), never ScreenSize, which is the window size and not this target's.
    public void ensureAllocated(int frameWidth, int frameHeight) {
        Map<Identifier, TargetSpec> specs = this.specs;
        if (dirty) {
            destroyAll();
            for (var e : specs.entrySet()) {
                TargetSpec spec = e.getValue();
                targets.put(e.getKey(), new TextureTarget(e.getKey().toString(),
                        spec.resolveWidth(frameWidth), spec.resolveHeight(frameHeight), spec.useDepth(),
                        spec.format()));
            }
            dirty = false;
        } else {
            for (var e : specs.entrySet()) {
                TargetSpec spec = e.getValue();
                int width = spec.resolveWidth(frameWidth);
                int height = spec.resolveHeight(frameHeight);
                RenderTarget target = targets.get(e.getKey());
                if (target != null && (target.width != width || target.height != height)) target.resize(width, height);
            }
        }
    }

    // Ids of the custom targets currently declared. These live in persistent RenderTargets this manager
    // owns, so unlike the vanilla level targets they can be imported into ANY frame graph at any point in
    // the frame - which is what lets post chains run after the hand and still reach them.
    public Set<Identifier> customTargetIds() {
        return this.specs.keySet();
    }

    // Wraps a base bundle so custom ids resolve to the persistent targets. Takes the interface rather than
    // LevelTargetBundle so this also works from a main-only base, which is all that exists once the level
    // frame graph has finished (see PostChainsManager.runChainsAfterHand).
    public PostChain.TargetBundle wrap(PostChain.TargetBundle vanilla, FrameGraphBuilder builder) {
        if (targets.isEmpty()) return vanilla;
        Map<Identifier, ResourceHandle<RenderTarget>> handles = new HashMap<>();
        for (var e : targets.entrySet()) {
            handles.put(e.getKey(), builder.importExternal(e.getKey().toString(), e.getValue()));
        }
        return new CustomTargetBundle(vanilla, handles);
    }

    public void close() {
        destroyAll();
    }

    private void destroyAll() {
        for (RenderTarget t : targets.values()) t.destroyBuffers();
        targets.clear();
    }

    private record CustomTargetBundle(PostChain.TargetBundle delegate,
                                      Map<Identifier, ResourceHandle<RenderTarget>> custom)
            implements PostChain.TargetBundle {
        @Override
        public ResourceHandle<RenderTarget> get(Identifier id) {
            ResourceHandle<RenderTarget> handle = custom.get(id);
            return handle != null ? handle : delegate.get(id);
        }

        @Override
        public void replace(Identifier id, ResourceHandle<RenderTarget> handle) {
            if (custom.containsKey(id)) custom.put(id, handle);
            else delegate.replace(id, handle);
        }
    }
}

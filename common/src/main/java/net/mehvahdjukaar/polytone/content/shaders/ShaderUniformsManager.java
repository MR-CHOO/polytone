package net.mehvahdjukaar.polytone.content.shaders;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import net.mehvahdjukaar.codecui.SchemaCodec;
import net.mehvahdjukaar.polytone.Polytone;
import net.mehvahdjukaar.polytone.common.reloader.ContentManager;
import net.mehvahdjukaar.polytone.common.struc.AssetsFiles;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Expression-driven float UBOs bound to any pipeline whose vertex or fragment shader id matches. The json path
// under polytone/shader_modifiers is the target shader id.
public class ShaderUniformsManager extends ContentManager<ExpressionUniformBuffers> {

    private static final String[] SHADER_EXTENSIONS = {".vsh", ".fsh"};

    private final List<ExpressionUniformBuffers> owned = new ArrayList<>();
    private final Map<Identifier, List<ExpressionUniformBuffers>> byShader = new HashMap<>();

    // Written off-thread during prepare, read on the render thread at link time
    private volatile Set<String> modifierBlockNames = Set.of();
    private final Map<PipelineKey, Set<String>> pendingChecks = new LinkedHashMap<>();
    private final Set<String> warnedPairs = new HashSet<>();

    public ShaderUniformsManager() {
        super("Shader uniforms", () -> SchemaCodec.wrap(ExpressionUniformBuffers.CODEC), "shader_modifiers");
    }

    @Override
    protected AssetsFiles prepare(PreparableReloadListener.SharedState sharedState) {
        AssetsFiles resources = super.prepare(sharedState);
        registerUniformNames(resources.jsons());
        warnAboutOrphanedTargets(sharedState.resourceManager(), resources.jsons());
        return resources;
    }

    // These files are keyed BY SHADER ID, and a modifier for an id nothing provides simply never binds:
    // every shader still compiles, links and renders, and nothing is logged. A vanilla shader rename
    // therefore kills a pack's modifiers silently - 26.1/26.2's core shader renames orphaned 12 of
    // Recrafted's, taking dynamic lights off all text and all items with no symptom in the log.
    // A shader id resolves to shaders/<path>.vsh / .fsh, so an orphan is detectable right here.
    private static void warnAboutOrphanedTargets(ResourceManager resourceManager,
                                                 Map<Identifier, JsonElement> jsons) {
        for (Identifier shaderId : jsons.keySet()) {
            if (shaderExists(resourceManager, shaderId)) continue;
            Polytone.LOGGER.warn(
                    "Polytone shader modifier '{}' targets a shader that no loaded pack provides " +
                    "(looked for shaders/{}.vsh and .fsh). It will never bind, silently. Was the shader " +
                    "renamed in this Minecraft version, or does it belong to a mod that isn't installed?",
                    shaderId, shaderId.getPath());
        }
    }

    private static boolean shaderExists(ResourceManager resourceManager, Identifier shaderId) {
        for (String extension : SHADER_EXTENSIONS) {
            Identifier file = shaderId.withPath("shaders/" + shaderId.getPath() + extension);
            if (resourceManager.getResource(file).isPresent()) return true;
        }
        return false;
    }

    // post chain files: block names are the expression_uniforms keys
    static void registerExpressionUniformNames(Map<Identifier, JsonElement> jsons) {
        for (var e : jsons.values()) {
            if (e == null || !e.isJsonObject()) continue;
            JsonElement uniforms = e.getAsJsonObject().get("expression_uniforms");
            if (uniforms instanceof JsonObject obj) {
                for (String name : obj.keySet()) {
                    PolytoneBuiltInUniformsSet.register(name);
                }
            }
        }
    }

    // shader_modifiers files: block names are the top-level keys
    private void registerUniformNames(Map<Identifier, JsonElement> jsons) {
        Set<String> names = new HashSet<>();
        for (var e : jsons.values()) {
            if (e instanceof JsonObject obj) {
                for (String name : obj.keySet()) {
                    PolytoneBuiltInUniformsSet.register(name);
                    names.add(name);
                }
            }
        }
        // Every key in any loaded modifier file - names the PACK ITSELF declared as Polytone expression
        // blocks. Using that as the ownership set is what makes the checks below false-positive free: no
        // prefix convention, no guessing. Vanilla's blocks (Fog, DynamicTransforms, Projection, Globals,
        // LightmapInfo) and Sodium's never appear as modifier keys, so they can never be flagged.
        modifierBlockNames = Set.copyOf(names);
    }

    // Called once per pipeline when its program links, on both backends. Deliberately NOT per pass: a
    // pipeline that simply hasn't drawn yet would otherwise look broken. The check itself is deferred to
    // the next frame because pipelines can link during the same reload that parses the modifier files,
    // and running it before byShader is filled would flag everything.
    public void onPipelineLinked(Identifier vertexShader, Identifier fragmentShader, Set<String> declaredBlocks) {
        if (declaredBlocks.isEmpty() || modifierBlockNames.isEmpty()) return;
        synchronized (pendingChecks) {
            pendingChecks.put(new PipelineKey(vertexShader, fragmentShader), Set.copyOf(declaredBlocks));
        }
    }

    private void runPendingChecks() {
        List<Map.Entry<PipelineKey, Set<String>>> pending;
        synchronized (pendingChecks) {
            if (pendingChecks.isEmpty()) return;
            pending = new ArrayList<>(pendingChecks.entrySet());
            pendingChecks.clear();
        }
        for (var entry : pending) {
            PipelineKey key = entry.getKey();
            Set<String> declared = entry.getValue();
            Set<String> supplied = new HashSet<>();
            for (Identifier shaderId : key.shaderIds()) {
                List<ExpressionUniformBuffers> list = byShader.get(shaderId);
                if (list == null) continue;
                for (ExpressionUniformBuffers b : list) supplied.addAll(b.expressions().keySet());
            }

            // Declared by the shader, owned by Polytone, supplied by nobody: the block exists in the
            // program and is never written, silently. This is the shape of a shader rename or a modifier
            // file that was never written for this shader id.
            for (String name : declared) {
                if (!modifierBlockNames.contains(name) || supplied.contains(name)) continue;
                if (!warnedPairs.add(key + "|" + name)) continue;
                Polytone.LOGGER.warn(
                        "Shader {} declares Polytone expression block '{}', but no shader_modifiers file " +
                        "supplies it for that shader, so it is never written. A modifier applies only to " +
                        "the shader id its file is named after. '{}' is currently supplied for: {}",
                        key, name, name, suppliersOf(name));
            }

            // NOT checked: the inverse ("supplies a block the program doesn't have"). It looks like it
            // would catch typos and stale keys, but `declared` here is what vanilla RESOLVED as ACTIVE
            // uniforms of this compiled program, not what the source declares. A block behind an #ifdef -
            // Sodium compiles a variant per terrain pass - is legitimately inactive in some variants, and
            // an unused block is optimised out entirely, so that direction warns on healthy packs.
        }
    }

    // Where a block IS supplied, so the warning above points straight at the mismatch
    private List<Identifier> suppliersOf(String blockName) {
        List<Identifier> ids = new ArrayList<>();
        for (var e : byShader.entrySet()) {
            for (ExpressionUniformBuffers b : e.getValue()) {
                if (b.expressions().containsKey(blockName)) {
                    ids.add(e.getKey());
                    break;
                }
            }
        }
        return ids;
    }

    private record PipelineKey(Identifier vertexShader, Identifier fragmentShader) {
        List<Identifier> shaderIds() {
            return vertexShader.equals(fragmentShader) ? List.of(vertexShader)
                    : List.of(vertexShader, fragmentShader);
        }

        @Override
        public String toString() {
            return vertexShader.equals(fragmentShader) ? vertexShader.toString()
                    : vertexShader + " / " + fragmentShader;
        }
    }

    @Override
    protected void parseWithLevel(AssetsFiles resources, RegistryOps<JsonElement> ops, HolderLookup.Provider access) {
        synchronized (owned) {
            for (var j : parseEnabledJsons(resources.jsons(), ops)) {
                if (j == null) continue;
                Identifier targetShader = j.getKey();
                ExpressionUniformBuffers buffers = j.getValue();
                buffers.ensureInitialized("Polytone shader expr uniform");
                owned.add(buffers);
                registerExternal(targetShader, buffers);
            }
        }
    }

    @Override
    protected void resetWithLevel(boolean logOff) {
        synchronized (owned) {
            for (var b : owned) b.close();
            owned.clear();
            byShader.clear();
        }
        // Pipelines relink on a reload, so a pack that fixed a block name must be able to report again
        synchronized (pendingChecks) {
            pendingChecks.clear();
        }
        warnedPairs.clear();
    }

    public void onClose() {
        synchronized (owned) {
            for (var b : owned) b.close();
        }
    }

    public void registerExternal(Identifier shaderId, ExpressionUniformBuffers buffers) {
        byShader.computeIfAbsent(shaderId, k -> new ArrayList<>()).add(buffers);
    }

    public void unregisterExternal(Identifier shaderId, ExpressionUniformBuffers buffers) {
        List<ExpressionUniformBuffers> list = byShader.get(shaderId);
        if (list != null) {
            list.remove(buffers);
            if (list.isEmpty()) byShader.remove(shaderId);
        }
    }

    // once per frame while no render pass is open; tryApply only binds what this uploaded
    public void updateAll() {
        runPendingChecks();
        if (byShader.isEmpty()) return;
        // one buffer set can be registered under several shader ids
        Set<ExpressionUniformBuffers> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (List<ExpressionUniformBuffers> list : byShader.values()) {
            for (ExpressionUniformBuffers b : list) {
                if (seen.add(b)) b.update();
            }
        }
    }

    // raw GL path for renderers that bypass RenderPass (Sodium chunk shaders); safe for any bound program
    public void bindToCurrentGlProgram() {
        if (byShader.isEmpty()) return;
        int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        if (program == 0) return;
        int point = 1;
        Set<ExpressionUniformBuffers> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (List<ExpressionUniformBuffers> list : byShader.values()) {
            for (ExpressionUniformBuffers b : list) {
                if (seen.add(b)) point = b.bindBlocksToProgram(program, point);
            }
        }
    }

    public boolean hasAnyRegistered() {
        return !byShader.isEmpty();
    }

    public void tryApply(RenderPass pass, RenderPipeline pipeline, Set<String> declaredUniforms) {
        if (byShader.isEmpty()) return;
        List<ExpressionUniformBuffers> list = byShader.get(pipeline.getFragmentShader());
        if (list == null) list = byShader.get(pipeline.getVertexShader());
        if (list == null) return;
        for (ExpressionUniformBuffers b : list) {
            b.bind(pass, declaredUniforms);
        }
    }
}

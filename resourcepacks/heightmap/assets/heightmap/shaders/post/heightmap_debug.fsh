#version 330

// SPIKE debug view for HeightMapRenderer (throwaway - goes away with the spike).
//
// Draws the top-down depth map in the bottom-left corner over the scene (yellow border), and the same
// capture's colour beside it (cyan border). The pass is a second
// geometry pass rendered in the SAME frame as the shadow map; if this window shows plausible
// terrain while shadows still look correct, two passes coexist.
//
// The map is ORTHOGRAPHIC, so its depth is LINEAR - no reconstruction needed, which is exactly why
// a heightmap is the right first viewpoint to build. The captured band is pinned in ABSOLUTE world
// Y, so depth 1 is always y=320 and depth 0 always y=-192 (reversed-Z on 26.x: near = 1, far = 0)
// no matter where the player is standing, and this decode has no camera term at all. These constants MUST match HeightMapRenderer's
// WORLD_TOP / WORLD_BOTTOM. Nothing is exposed as a uniform on purpose: the spike adds no UBO.

uniform sampler2D InSampler;   // scene colour (pass input "In")
uniform sampler2D InHeight;    // top-down depth map (Polytone binds it by name)
uniform sampler2D InHeightColor; // the same capture's colour: lit, unfogged, alpha 0 where empty
uniform sampler2D InSurfaceGround; // CPU surface map: R+G = height - minY, alpha 0 = not filled yet
uniform sampler2D InSurfaceBiome;  // CPU surface map: R = palette slot, 0 = not filled, 255 = overflow

layout(std140) uniform PolySurfaceBiome {
    ivec4 SurfaceBiomeWindow;   // xy = window min block, z = blocks per texel, w = texels per side
    ivec4 SurfaceBiomeInfo;     // x = attributes per slot, y = slots in use
    vec4  SurfaceBiomePalette[512];
} sb;

in vec2 texCoord;
out vec4 fragColor;

const float VIEW_SIZE = 0.30;     // fraction of the screen height the debug window occupies
const float WORLD_TOP = 320.0;    // MUST match HeightMapRenderer.WORLD_TOP
const float WORLD_BOTTOM = -192.0; // MUST match HeightMapRenderer.WORLD_BOTTOM

void main() {
    vec4 scene = texture(InSampler, texCoord);

    // Bottom-left square. texCoord is (0,0) at the top-left in MC's post pipeline, so the window
    // occupies the LAST VIEW_SIZE of y.
    vec2 lo = vec2(0.0, 1.0 - VIEW_SIZE);
    vec2 hi = vec2(VIEW_SIZE, 1.0);

    // Colour capture in a second window right of the depth one, same uv mapping so the two line up.
    vec2 colorLo = vec2(VIEW_SIZE, 1.0 - VIEW_SIZE);
    vec2 colorHi = vec2(2.0 * VIEW_SIZE, 1.0);
    if (all(greaterThanEqual(texCoord, colorLo)) && all(lessThanEqual(texCoord, colorHi))) {
        vec2 uv = (texCoord - colorLo) / (colorHi - colorLo);
        vec4 captured = texture(InHeightColor, uv);
        // alpha 0 = nothing drawn in this column, same purple as the depth window
        fragColor = captured.a < 0.004 ? vec4(0.15, 0.0, 0.2, 1.0) : vec4(captured.rgb, 1.0);
        vec2 edge = min(uv, 1.0 - uv);
        if (min(edge.x, edge.y) < 0.004) fragColor = vec4(0.1, 0.85, 1.0, 1.0);
        return;
    }

    // Fourth window: the biome layer. Left half tints by slot so borders are visible; right half shows
    // that slot's first attribute (fog end distance) as brightness, straight from the palette.
    vec2 bioLo = vec2(3.0 * VIEW_SIZE, 1.0 - VIEW_SIZE);
    vec2 bioHi = vec2(4.0 * VIEW_SIZE, 1.0);
    if (all(greaterThanEqual(texCoord, bioLo)) && all(lessThanEqual(texCoord, bioHi))) {
        vec2 uv = (texCoord - bioLo) / (bioHi - bioLo);
        float slotF = texture(InSurfaceBiome, uv).r * 255.0;
        int slot = int(slotF + 0.5);
        if (slot == 0) {
            fragColor = vec4(0.0, 0.0, 0.0, 1.0);                 // not filled yet
        } else if (slot == 255) {
            fragColor = vec4(1.0, 0.0, 1.0, 1.0);                 // ran out of palette slots
        } else if (uv.x < 0.5) {
            float h = fract(float(slot) * 0.6180339887);          // a colour per slot
            fragColor = vec4(abs(h * 6.0 - 3.0) - 1.0, 2.0 - abs(h * 6.0 - 2.0), 2.0 - abs(h * 6.0 - 4.0), 1.0);
            fragColor.rgb = clamp(fragColor.rgb, 0.0, 1.0);
        } else {
            float fogEnd = sb.SurfaceBiomePalette[slot * sb.SurfaceBiomeInfo.x].x;
            fragColor = vec4(vec3(clamp(fogEnd / 512.0, 0.0, 1.0)), 1.0);
        }
        vec2 edge = min(uv, 1.0 - uv);
        if (min(edge.x, edge.y) < 0.004) fragColor = vec4(1.0, 0.4, 0.1, 1.0);
        return;
    }

    // Third window: the CPU surface map, straight out of the texture (it is world-locked and wraps, so
    // this scrolls as you walk). Green = filled, black = still to fill, brightness = height.
    vec2 cpuLo = vec2(2.0 * VIEW_SIZE, 1.0 - VIEW_SIZE);
    vec2 cpuHi = vec2(3.0 * VIEW_SIZE, 1.0);
    if (all(greaterThanEqual(texCoord, cpuLo)) && all(lessThanEqual(texCoord, cpuHi))) {
        vec2 uv = (texCoord - cpuLo) / (cpuHi - cpuLo);
        vec4 s = texture(InSurfaceGround, uv);
        if (s.a < 0.5) {
            fragColor = vec4(0.0, 0.0, 0.0, 1.0);          // not filled yet
        } else {
            float h = (s.r * 255.0 + s.g * 255.0 * 256.0); // height above minY, in blocks
            fragColor = vec4(0.0, clamp(h / 384.0, 0.0, 1.0), 0.0, 1.0);
        }
        vec2 edge = min(uv, 1.0 - uv);
        if (min(edge.x, edge.y) < 0.004) fragColor = vec4(0.2, 1.0, 0.2, 1.0);
        return;
    }

    if (any(lessThan(texCoord, lo)) || any(greaterThan(texCoord, hi))) {
        fragColor = scene;
        return;
    }

    vec2 uv = (texCoord - lo) / (hi - lo);
    float depth = texture(InHeight, uv).r;

    // Far plane (nothing drawn) reads 0.0 under reversed-Z - paint it flat so empty columns are
    // obvious rather than looking like "the ground is very low".
    if (depth <= 0.001) {
        fragColor = vec4(0.15, 0.0, 0.2, 1.0); // purple = no geometry in this column
    } else {
        float worldY = WORLD_BOTTOM + depth * (WORLD_TOP - WORLD_BOTTOM);

        // SMOOTH grayscale over the useful build range - this is the actual depth readout. Dark at
        // bedrock, bright at the build limit, sea level around mid grey.
        float shade = clamp((worldY + 64.0) / 384.0, 0.0, 1.0);

        // Contour lines every 8 blocks, purely as a scale reference.
        //
        // ⚠ TWO THINGS HERE ARE DELIBERATE - the first version got both wrong and flickered.
        // (1) PHASE OFFSET: Minecraft terrain sits at multiples of 8 (y=64, 72, 80...), so an
        //     unoffset period-8 test lands EXACTLY on flat ground, where one ULP of float noise
        //     flips the comparison and the whole plateau strobes as the camera moves vertically.
        //     +4.0 puts the lines mid-band, as far from real surfaces as possible.
        // (2) SMOOTH, not step(): fwidth-scaled so the line antialiases and degrades to nothing
        //     instead of aliasing when the gradient is steep. A discontinuous test amplifies any
        //     sub-ULP wobble into a full-brightness jump; a smooth one cannot.
        float h = (worldY + 4.0) / 8.0;
        float line = abs(fract(h) - 0.5) / max(fwidth(h), 1e-4);
        float contour = 1.0 - clamp(line, 0.0, 1.0) * 0.18;

        fragColor = vec4(vec3(shade * contour), 1.0);
    }

    // 1px border so the window edge is unambiguous against dark terrain.
    vec2 edge = min(uv, 1.0 - uv);
    if (min(edge.x, edge.y) < 0.004) fragColor = vec4(1.0, 0.85, 0.1, 1.0);
}

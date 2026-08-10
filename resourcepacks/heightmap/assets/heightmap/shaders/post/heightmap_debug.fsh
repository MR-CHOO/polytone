#version 330

// SPIKE debug view for HeightMapRenderer (throwaway - goes away with the spike).
//
// Draws the top-down depth map in the bottom-left corner over the scene. The pass is a second
// geometry pass rendered in the SAME frame as the shadow map; if this window shows plausible
// terrain while shadows still look correct, two passes coexist.
//
// The map is ORTHOGRAPHIC, so its depth is LINEAR - no reconstruction needed, which is exactly why
// a heightmap is the right first viewpoint to build. The captured band is pinned in ABSOLUTE world
// Y, so depth 0 is always y=320 and depth 1 always y=-192 no matter where the player is standing,
// and this decode has no camera term at all. These constants MUST match HeightMapRenderer's
// WORLD_TOP / WORLD_BOTTOM. Nothing is exposed as a uniform on purpose: the spike adds no UBO.

uniform sampler2D InSampler;   // scene colour (pass input "In")
uniform sampler2D InHeight;    // top-down depth map (Polytone binds it by name)

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
    if (any(lessThan(texCoord, lo)) || any(greaterThan(texCoord, hi))) {
        fragColor = scene;
        return;
    }

    vec2 uv = (texCoord - lo) / (hi - lo);
    float depth = texture(InHeight, uv).r;

    // Far plane (nothing drawn) reads 1.0 - paint it flat so empty columns are obvious rather than
    // looking like "the ground is very low".
    if (depth >= 0.999) {
        fragColor = vec4(0.15, 0.0, 0.2, 1.0); // purple = no geometry in this column
    } else {
        float worldY = WORLD_TOP - depth * (WORLD_TOP - WORLD_BOTTOM);

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

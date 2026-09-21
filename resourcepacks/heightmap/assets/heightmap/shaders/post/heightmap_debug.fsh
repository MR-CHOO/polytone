#version 330

// SPIKE debug view for the viewpoint capture and the CPU surface map (throwaway - goes away with
// the spike).
//
// FOUR SQUARE WINDOWS along the bottom of the screen, left to right:
//   1 yellow  the viewpoint's top-down DEPTH map          (GPU, a second geometry pass)
//   2 cyan    the same capture's COLOUR
//   3 green   the CPU surface map's height layer, GREYSCALE on window 1's ramp
//   4 orange  the CPU surface map's biome layer, painted with the palette's own fog colour
// and a NUMERIC READOUT in the top-left corner, point-sampling both CPU layers at the player's own
// column. The windows answer "is it filling"; the readout answers "is it filling with the RIGHT
// values", which a picture of a wrapping texture cannot.
//
// Windows 3 and 4 are UN-WRAPPED and north-up with a red cross on the player - see rc_groundOrigin.
// The chain carries a very high "priority" so it is the last thing composited into main and nothing
// draws over it.
//
// The capture is ORTHOGRAPHIC, so its depth is LINEAR - no reconstruction needed, which is exactly
// why a heightmap is the right first viewpoint to build. The captured band is pinned in ABSOLUTE
// world Y, so depth 1 is always y=320 and depth 0 always y=-192 (reversed-Z on 26.x: near = 1,
// far = 0) no matter where the player is standing, and this decode has no camera term at all. These
// constants MUST match the viewpoint json's y / near / far.

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

layout(std140) uniform PolyGlobals {
    mat4 PolyProjMat;
    mat4 PolyModelViewMat;
    float PolySunAngle;
    float PolyDayTime;
    float PolyDeltaTime;
    ivec3 PolyPlayerBlockPos;
    vec3 PolyPlayerOffset;
};

in vec2 texCoord;
out vec4 fragColor;

// Side of one window as a fraction of screen HEIGHT, used for BOTH axes so the windows are square
// whatever the monitor is. Four of them is 4 * 0.25 = one screen height across, which fits inside
// any aspect ratio at or above 1:1 - measure the side in screen widths and the last one walks off
// the edge of a 16:9 display.
const float VIEW_SIZE = 0.25;
const int WINDOWS = 4;

const float WORLD_TOP = 320.0;     // MUST match the viewpoint json's y
const float WORLD_BOTTOM = -192.0; // MUST match y - far

// ── NUMERIC READOUT ───────────────────────────────────────────────────────────────────────────
// A 3x5 bitmap font: rows top to bottom, 3 bits per row, MSB = leftmost column. Enough to print the
// two numbers this pass exists to check against F3, and nothing more.
const int RC_FONT[13] = int[13](
    31599, 11415, 29671, 29647, 23497, 31183, 31215, 29257, 31727, 31695, // 0-9
    448, 23533, 27566                                                     // '-', 'H', 'B'
);
const float RC_PX = 3.0;                        // screen pixels per font pixel
const vec2 RC_CELL = vec2(3.0, 5.0) * RC_PX;    // one glyph
const float RC_ADV = 4.0 * RC_PX;               // glyph plus one column of spacing

// The height layers publish no window of their own yet, so these two come from this pack's
// surface_map.json and from the overworld. The toroid needs only the side length: addressing is
// world-locked, so a block's texel is its coordinate wrapped, with no origin term at all.
const int RC_GROUND_TEXELS = 256;  // coverage 128 -> roundUp(128 * 2, 16)
const int RC_MIN_Y = -64;

float rc_glyph(int g, vec2 local) {
    if (any(lessThan(local, vec2(0.0))) || any(greaterThanEqual(local, RC_CELL))) return 0.0;
    ivec2 f = ivec2(local / RC_PX);
    return float((g >> ((4 - f.y) * 3 + (2 - f.x))) & 1);
}

// Right-aligned digits grown leftwards from the value, so the number never jumps column as it gains
// one - a readout you have to re-find every frame is not a readout.
float rc_int(vec2 px, vec2 origin, int value) {
    int glyphs[6];
    int n = 0;
    int v = abs(value);
    if (v == 0) {
        glyphs[0] = 0;
        n = 1;
    }
    while (v > 0 && n < 5) {
        glyphs[n] = v % 10;
        v /= 10;
        n++;
    }
    if (value < 0) {
        glyphs[n] = 10;  // '-' is leftmost, so last in a list built right to left
        n++;
    }
    float ink = 0.0;
    for (int i = 0; i < n; i++) {
        ink = max(ink, rc_glyph(RC_FONT[glyphs[i]], px - (origin + vec2(float(n - 1 - i) * RC_ADV, 0.0))));
    }
    return ink;
}

// A world block coordinate to its texel in a layer of `texels` a side. GLSL's % truncates toward
// zero, hence the second fold.
ivec2 rc_wrap(ivec2 blockXZ, int texels) {
    return ((blockXZ % texels) + texels) % texels;
}

// ⚠ THE CPU LAYERS ARE TOROIDAL. Drawing one straight out of the texture shows the world cut at the
// wrap seam and rearranged into up to four pieces - terrain laced through terrain, biome blobs
// sliced into stripes. So the windows below walk the layer's WINDOW IN WORLD SPACE and wrap each
// lookup instead, which comes out continuous, north-up, and centred on you.
//
// The biome layer publishes its origin in PolySurfaceBiome. The height layers publish nothing, so
// this reproduces the CPU's own formula, `(camera - size / 2) & ~15`, from the player position -
// in third person the two can straddle a chunk boundary, which shifts this by at most 16 blocks.
ivec2 rc_groundOrigin() {
    return (PolyPlayerBlockPos.xz - RC_GROUND_TEXELS / 2) & ivec2(~15);
}

// Where the player sits inside a window spanning `span` blocks from `origin`, as window uv (y up).
vec2 rc_playerUv(ivec2 origin, int span) {
    vec2 rel = vec2(PolyPlayerBlockPos.xz - origin) / float(span);
    return vec2(rel.x, 1.0 - rel.y);
}

// A small cross, so "the height under me" has somewhere to be read off.
bool rc_mark(vec2 uv, vec2 at) {
    vec2 d = abs(uv - at);
    return (d.x < 0.005 && d.y < 0.035) || (d.y < 0.005 && d.x < 0.035);
}

// ── THE WINDOWS ───────────────────────────────────────────────────────────────────────────────

vec3 rc_winDepth(vec2 uv) {
    float depth = texture(InHeight, uv).r;
    // Far plane (nothing drawn) reads 0.0 under reversed-Z - paint it flat so empty columns are
    // obvious rather than looking like "the ground is very low".
    if (depth <= 0.001) return vec3(0.15, 0.0, 0.2);

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
    return vec3(shade * (1.0 - clamp(line, 0.0, 1.0) * 0.18));
}

vec3 rc_winColor(vec2 uv) {
    vec4 captured = texture(InHeightColor, uv);
    // alpha 0 = nothing drawn in this column, same purple as the depth window
    return captured.a < 0.004 ? vec3(0.15, 0.0, 0.2) : captured.rgb;
}

// GREYSCALE, on the SAME ramp window 1 uses, so the CPU map and the GPU capture can be read against
// each other directly: dark at bedrock, bright at the build limit, sea level around mid grey.
vec3 rc_winGround(vec2 uv) {
    int n = RC_GROUND_TEXELS;
    ivec2 block = rc_groundOrigin() + ivec2(int(uv.x * float(n)), int((1.0 - uv.y) * float(n)));
    vec4 s = texelFetch(InSurfaceGround, rc_wrap(block, n), 0);
    if (s.a < 0.5) return vec3(0.0);                      // not filled yet
    float worldY = s.r * 255.0 + s.g * 255.0 * 256.0 + float(RC_MIN_Y);
    return vec3(clamp((worldY + 64.0) / 384.0, 0.0, 1.0));
}

int rc_slotAt(ivec2 cell, int texels) {
    return int(texelFetch(InSurfaceBiome, rc_wrap(cell, texels), 0).r * 255.0 + 0.5);
}

// The palette's REAL fog colour per cell, not a hue hashed from the slot index. A hash is easier to
// read as segmentation but it is a picture of nothing - it cannot be wrong. This is the palette
// itself on screen, so a slot resolving to the wrong biome, or an attribute landing in the wrong
// array entry, shows up here as a colour that does not belong.
vec3 rc_winBiome(vec2 uv) {
    int n = sb.SurfaceBiomeWindow.w;
    if (n <= 0) return vec3(0.0);                         // no biome layer allocated
    ivec2 cellOrigin = sb.SurfaceBiomeWindow.xy / sb.SurfaceBiomeWindow.z;
    ivec2 cell = cellOrigin + ivec2(int(uv.x * float(n)), int((1.0 - uv.y) * float(n)));
    int slot = rc_slotAt(cell, n);
    if (slot == 0) return vec3(0.0);                      // not filled yet
    if (slot == 255) return vec3(1.0, 0.0, 1.0);          // ran out of palette slots
    if (slot > sb.SurfaceBiomeInfo.y) return vec3(0.0);   // palette has not caught up with the layer

    // attribute 1 is fog_color in this pack's surface_map.json
    vec3 rgb = sb.SurfaceBiomePalette[slot * 8 + 1].rgb;

    // Neighbouring biomes often share a fog colour almost exactly, which would hide the cell
    // structure completely, so darken where the SLOT changes. Borders stay legible without tinting
    // the fill. The lookup wraps, so the window's own edge draws one - it sits under the border.
    if (rc_slotAt(cell + ivec2(1, 0), n) != slot || rc_slotAt(cell + ivec2(0, 1), n) != slot) rgb *= 0.45;
    return rgb;
}

vec3 rc_border(int window) {
    if (window == 0) return vec3(1.0, 0.85, 0.1);
    if (window == 1) return vec3(0.1, 0.85, 1.0);
    if (window == 2) return vec3(0.2, 1.0, 0.2);
    return vec3(1.0, 0.4, 0.1);
}

void main() {
    vec4 scene = texture(InSampler, texCoord);
    vec2 scr = vec2(textureSize(InSampler, 0));

    // ⚠ texCoord is (0,0) at the BOTTOM-left in this pipeline, which is the opposite of what the
    // first version of this file assumed - it put the windows at 1 - VIEW_SIZE and they came out
    // along the TOP. Everything below works in pixels from the TOP-left and converts once, here.
    vec2 px = vec2(texCoord.x, 1.0 - texCoord.y) * scr;

    // ── the readout, top-left ─────────────────────────────────────────────────────────────────
    //   B <slot>  [swatch]   the biome slot under you, and that slot's palette colour
    //   H <y>                the surface height under you, in world Y
    vec2 panelLo = vec2(6.0);
    vec2 panelHi = panelLo + vec2(104.0, 46.0);
    if (all(greaterThanEqual(px, panelLo)) && all(lessThan(px, panelHi))) {
        ivec2 blockXZ = PolyPlayerBlockPos.xz;

        vec4 ground = texelFetch(InSurfaceGround, rc_wrap(blockXZ, RC_GROUND_TEXELS), 0);
        int height = int(ground.r * 255.0 + 0.5) + int(ground.g * 255.0 + 0.5) * 256 + RC_MIN_Y;

        int slot = 0;
        if (sb.SurfaceBiomeWindow.w > 0) {
            vec4 cell = texelFetch(InSurfaceBiome, rc_wrap(blockXZ >> 2, sb.SurfaceBiomeWindow.w), 0);
            slot = cell.a < 0.5 ? 0 : int(cell.r * 255.0 + 0.5);
        }

        fragColor = vec4(mix(scene.rgb, vec3(0.0), 0.7), 1.0);

        vec2 row0 = panelLo + vec2(5.0);
        vec2 row1 = row0 + vec2(0.0, 20.0);
        vec2 value = vec2(RC_ADV * 1.5, 0.0);

        // Attribute 1 is fog_color in this pack's surface_map.json. Slot 0 is "no data" and slot 255
        // is overflow; neither has a palette entry, so neither gets a swatch.
        vec2 swatchLo = vec2(panelHi.x - 22.0, row0.y);
        if (all(greaterThanEqual(px, swatchLo)) && all(lessThan(px, swatchLo + vec2(RC_CELL.y)))) {
            bool live = slot > 0 && slot < 255 && slot <= sb.SurfaceBiomeInfo.y;
            fragColor = vec4(live ? sb.SurfaceBiomePalette[slot * 8 + 1].rgb : vec3(0.12), 1.0);
        }

        float ink = rc_glyph(RC_FONT[12], px - row0);            // 'B'
        ink = max(ink, rc_int(px, row0 + value, slot));
        ink = max(ink, rc_glyph(RC_FONT[11], px - row1));        // 'H'
        ink = max(ink, ground.a < 0.5
                ? rc_glyph(RC_FONT[10], px - (row1 + value))     // '-', nothing written there yet
                : rc_int(px, row1 + value, height));
        if (ink > 0.5) fragColor = vec4(1.0);
        return;
    }

    // ── the window strip, along the bottom ────────────────────────────────────────────────────
    float side = VIEW_SIZE * scr.y;
    float stripTop = scr.y - side;
    int window = int(floor(px.x / side));
    if (px.y >= stripTop && window < WINDOWS) {
        // y flipped back to up, so every capture keeps the orientation it had
        vec2 uv = vec2(fract(px.x / side), 1.0 - (px.y - stripTop) / side);

        vec3 rgb;
        if (window == 0) {
            rgb = rc_winDepth(uv);
        } else if (window == 1) {
            rgb = rc_winColor(uv);
        } else if (window == 2) {
            rgb = rc_winGround(uv);
            if (rc_mark(uv, rc_playerUv(rc_groundOrigin(), RC_GROUND_TEXELS))) rgb = vec3(1.0, 0.2, 0.2);
        } else {
            rgb = rc_winBiome(uv);
            int span = sb.SurfaceBiomeWindow.w * sb.SurfaceBiomeWindow.z;
            if (span > 0 && rc_mark(uv, rc_playerUv(sb.SurfaceBiomeWindow.xy, span))) rgb = vec3(1.0, 0.2, 0.2);
        }

        // 1px border so the window edge is unambiguous against dark terrain
        vec2 edge = min(uv, 1.0 - uv);
        fragColor = vec4(min(edge.x, edge.y) < 0.004 ? rc_border(window) : rgb, 1.0);
        return;
    }

    fragColor = scene;
}

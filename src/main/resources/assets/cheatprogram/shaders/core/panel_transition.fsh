#version 150

uniform sampler2D Underlay;
uniform sampler2D Current;
uniform sampler2D Previous;
uniform vec2 Screen;
uniform vec4 Panel;    // x, y, width, height in top-left framebuffer pixels
uniform vec4 Shape;    // header height, corner radius, animation strength, original shadow softness
uniform vec4 Progress; // global visibility, page progress, global active, page active
uniform vec4 Modes;    // global mode, page mode, closing, reserved
in vec2 texCoord;
out vec4 fragColor;

const float PI = 3.14159265359;

// Snapshots have the framebuffer's exact resolution. Sampling pixel centers also
// makes the identity pass identical to ordinary drawing, including its thin glass rim.
vec2 uv(vec2 p) {
    vec2 clamped = clamp(p, vec2(0.5), Screen - vec2(0.5));
    return vec2(clamped.x / Screen.x, 1.0 - clamped.y / Screen.y);
}
vec4 nowAt(vec2 p) { return texture(Current, uv(p)); }
vec4 oldAt(vec2 p) { return texture(Previous, uv(p)); }
vec4 underAt(vec2 p) { return texture(Underlay, uv(p)); }
vec2 bodyPoint(vec2 p, vec2 origin, vec2 size) { return clamp(p, origin + vec2(0.5), origin + size - vec2(0.5)); }
vec4 bodyNow(vec2 p, vec2 origin, vec2 size) { return nowAt(bodyPoint(p, origin, size)); }
vec4 bodyOld(vec2 p, vec2 origin, vec2 size) { return oldAt(bodyPoint(p, origin, size)); }
float ease(float t) { t = clamp(t, 0.0, 1.0); return 1.0 - pow(1.0 - t, 3.0); }
float settle(float t) { t = clamp(t, 0.0, 1.0); return t * t * t * (t * (6.0 * t - 15.0) + 10.0); }
float spring(float t) {
    if (t <= 0.0) return 0.0;
    if (t >= 1.0) return 1.0;
    return 1.0 - exp(-6.8 * t) * cos(10.8 * t);
}
float rounded(vec2 p, vec2 origin, vec2 size, float r) {
    vec2 q = abs(p - origin - size * 0.5) - size * 0.5 + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
float mask(vec2 p, vec2 origin, vec2 size, float r) {
    float d = rounded(p, origin, size, r);
    float aa = max(0.8, fwidth(d));
    return 1.0 - smoothstep(-aa, aa, d);
}
bool inside(vec2 p, vec2 origin, vec2 size) {
    return all(greaterThanEqual(p, origin)) && all(lessThanEqual(p, origin + size));
}

// Inverse of a Y-rotated physical plane under perspective, rather than a flat X squeeze.
vec2 inverseTurn(vec2 p, vec2 pivot, float angle, float depth, out float valid) {
    vec2 q = p - pivot;
    float c = cos(angle), s = sin(angle);
    float denominator = c + q.x * s / depth;
    valid = step(0.035, denominator);
    float localX = q.x / max(0.035, denominator);
    float perspective = 1.0 - localX * s / depth;
    valid *= step(0.08, perspective);
    return pivot + vec2(localX, q.y * perspective);
}

vec4 pageInner(vec2 p, vec2 origin, vec2 size) {
    vec4 next = nowAt(p);
    float t = clamp(Progress.y, 0.0, 1.0), e = ease(t), s = Shape.z;
    int mode = int(Modes.y + 0.5);
    if (mode == 6) return next;
    if (mode == 2) return mix(oldAt(p), next, smoothstep(0.0, 1.0, t));
    if (mode == 0) {
        // The old page is hinged on its left edge; the new page is already underneath.
        float angle = min(1.55, e * 1.72);
        float valid;
        vec2 src = inverseTurn(p, origin + vec2(0.0, size.y * 0.5), angle, size.x * 1.7, valid);
        float cover = valid * mask(src, origin, size, 0.0) * (1.0 - smoothstep(0.82, 1.0, t));
        vec4 leaf = bodyOld(src, origin, size);
        float foldShade = 0.58 + 0.42 * cos(angle);
        leaf.rgb *= foldShade;
        float crease = exp(-abs(src.x - origin.x) / max(1.0, 12.0 * s)) * sin(angle);
        leaf.rgb *= 1.0 - crease * 0.18;
        return mix(next, leaf, clamp(cover, 0.0, 1.0));
    }
    if (mode == 1) {
        vec2 oldP = p + vec2(e * size.x * (0.7 + 0.3 * s), 0.0);
        vec2 newP = p - vec2((1.0 - e) * size.x * (0.7 + 0.3 * s), 0.0);
        vec4 base = texture(Underlay, uv(p));
        base = mix(base, bodyOld(oldP, origin, size), mask(oldP, origin, size, 0.0) * (1.0 - e * 0.28));
        return mix(base, bodyNow(newP, origin, size), mask(newP, origin, size, 0.0));
    }
    if (mode == 3) {
        // Each half of the page turn accelerates and settles, instead of rotating at a fixed speed.
        float valid, angle = t < 0.5 ? settle(t * 2.0) * PI * 0.5 : -(1.0 - settle((t - 0.5) * 2.0)) * PI * 0.5;
        vec2 src = inverseTurn(p, origin + size * 0.5, angle, size.x * 1.8, valid);
        vec4 sheet = t < 0.5 ? bodyOld(src, origin, size) : bodyNow(src, origin, size);
        sheet.rgb *= 0.62 + 0.38 * abs(cos(angle));
        return mix(texture(Underlay, uv(p)), sheet, valid * mask(src, origin, size, 0.0));
    }
    if (mode == 4) {
        float stretch = sin(PI * t) * s;
        vec2 center = origin + size * 0.5;
        vec2 oldP = center + (p - center) / vec2(max(0.2, 1.0 - e * 0.18 * s), 1.0 + stretch * 0.14);
        vec2 newP = center + (p - center) / vec2(0.68 + e * 0.32, max(0.25, 1.0 - stretch * 0.18));
        newP.x += sin((p.y - origin.y) / size.y * PI) * stretch * 16.0 * (1.0 - e);
        vec4 base = mix(texture(Underlay, uv(p)), bodyOld(oldP, origin, size), mask(oldP, origin, size, 0.0));
        return mix(base, bodyNow(newP, origin, size), e * mask(newP, origin, size, 0.0));
    }
    // Liquid page turnover: propagating radial refraction with mild chromatic dispersion.
    vec2 center = origin + size * vec2(0.28, 0.5);
    vec2 delta = p - center;
    float distance = length(delta) / max(1.0, length(size));
    float envelope = sin(PI * t) * s;
    float wave = sin(distance * 32.0 - t * 15.0) * envelope * 13.0;
    vec2 offset = normalize(delta + vec2(0.001)) * wave;
    vec4 a = bodyOld(p + offset, origin, size), b = bodyNow(p - offset, origin, size);
    float blend = clamp(t + sin(distance * 18.0 - t * 9.0) * envelope * 0.09, 0.0, 1.0);
    float easedBlend = smoothstep(0.0, 1.0, blend);
    vec4 result = mix(a, b, easedBlend);
    result.r = mix(bodyOld(p + offset * 1.035, origin, size).r, bodyNow(p - offset * 1.035, origin, size).r, easedBlend);
    result.b = mix(bodyOld(p + offset * 0.965, origin, size).b, bodyNow(p - offset * 0.965, origin, size).b, easedBlend);
    return result;
}

vec4 pageAt(vec2 p) {
    vec4 next = nowAt(p);
    if (Progress.w < 0.5) return next;
    float inset = max(2.0, Shape.y * 0.45);
    vec2 origin = Panel.xy + vec2(inset, Shape.x + inset);
    vec2 size = max(vec2(1.0), Panel.zw - vec2(inset * 2.0, Shape.x + inset * 2.0));
    if (!inside(p, origin, size)) return next;
    // Keep a quiet strip of the real surface around every page. In particular,
    // no second rounded mask or shadow touches the control panel's outer edge.
    vec2 innerDistance = min(p - origin, origin + size - p);
    float seam = smoothstep(0.0, max(1.5, Shape.y * 0.1), min(innerDistance.x, innerDistance.y));
    seam *= smoothstep(Shape.y * 0.4, Shape.y * 0.6,
                       -rounded(p, Panel.xy, Panel.zw, Shape.y));
    seam *= 1.0 - smoothstep(0.9, 1.0, Progress.y);
    return mix(next, pageInner(p, origin, size), seam);
}

float footprint(vec2 p) {
    // This shell only excludes unrelated overlays. It is fully opaque across the
    // already-antialiased glass AND the entire captured original drop shadow.
    float unit = max(0.01, Shape.y / 26.0);
    float d = min(rounded(p, Panel.xy, Panel.zw, Shape.y),
                  rounded(p, Panel.xy + vec2(0.0, 10.0 * unit), Panel.zw, Shape.y));
    return 1.0 - smoothstep(Shape.w + 1.0, Shape.w + 3.0, d);
}

void main() {
    vec2 p = vec2(gl_FragCoord.x, Screen.y - gl_FragCoord.y);
    vec4 identity = nowAt(p);
    if (Progress.z < 0.5) {
        // Page-only animations never remove or redraw the panel shell.
        fragColor = pageAt(p);
        return;
    }
    vec4 background = underAt(p);
    vec4 base = mix(identity, background, footprint(p));
    vec2 src = p, center = Panel.xy + Panel.zw * 0.5;
    float alpha = 1.0, valid = 1.0, curtain = 1.0, light = 1.0;
    if (Progress.z > 0.5) {
        float t = clamp(Progress.x, 0.0, 1.0), e = ease(t), s = Shape.z;
        int mode = int(Modes.x + 0.5);
        alpha = smoothstep(0.0, 0.18, t);
        if (mode == 0) {
            float b = spring(t), scale = max(0.05, 1.0 + (b - 1.0) * 0.28 * s);
            src = center + (p - center - vec2(0.0, (1.0 - b) * -22.0 * s)) / scale;
        } else if (mode == 1) {
            src.y -= (1.0 - e) * -14.0 * s; alpha = e;
        } else if (mode == 2) {
            float stretch = sin(PI * t) * s;
            vec2 scale = vec2(1.0 + stretch * 0.12, max(0.05, 1.0 - (1.0 - e) * 0.87 * min(1.0, s)));
            vec2 pivot = Panel.xy + vec2(Panel.z * 0.5, 0.0);
            src = pivot + (p - pivot) / scale;
            src.x += sin((src.y - Panel.y) / Panel.w * PI) * stretch * 8.0;
        } else if (mode == 3) {
            float angle = -(1.0 - e) * min(1.48, 1.25 * s);
            src = inverseTurn(p, center, angle, Panel.z * 1.65, valid);
            light = 0.72 + 0.28 * cos(angle);
        } else if (mode == 4) {
            src.y -= (1.0 - e) * Panel.w * 0.18 * s; alpha = smoothstep(0.0, 0.68, t);
        } else if (mode == 5) {
            float zoom = max(0.025, 1.0 - (1.0 - spring(t)) * 0.92 * min(1.0, s));
            src = center + (p - center) / zoom;
        } else {
            // Include the captured drop shadow at the fully-open endpoint. Cutting
            // the curtain at the window's exact width left a half-covered final rim.
            float span = (Panel.z * 0.5 + Shape.w + 3.0) * e;
            float rim = abs(p.x - center.x) - span;
            rim += sin((p.y - Panel.y) * 0.023 + t * 5.0) * sin(PI * t) * s * 9.0;
            curtain = 1.0 - smoothstep(-1.2, 1.2, rim);
        }
    }
    float onScreen = inside(src, vec2(0.0), Screen) ? 1.0 : 0.0;
    float cover = clamp(footprint(src) * alpha * valid * curtain * onScreen, 0.0, 1.0);
    // The capture is already composited glass, with true coverage and its original
    // shadow. Move its signed contribution relative to the same-frame underlay.
    // Multiplying another rounded alpha would thin the rim and produce a double edge.
    vec4 contribution = pageAt(src) - underAt(src);
    contribution.rgb *= light;
    // Settle onto the exact ordinary pixels before the renderer hands control
    // back, rather than exposing a final subpixel spring/warp at the boundary.
    fragColor = mix(base + contribution * cover, pageAt(p),
                    smoothstep(0.92, 1.0, clamp(Progress.x, 0.0, 1.0)));
}

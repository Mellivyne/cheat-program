#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform vec2 ScreenSize;
uniform vec2 Size;
uniform vec4 Radius;
uniform vec4 Glass;
uniform vec4 Rim;
uniform vec2 Flow; // normalized injection progress, elapsed seconds since this entry was created
uniform vec4 ColorModulator;

in vec2 localPos;
in vec4 vertexColor;
out vec4 fragColor;

float roundedBox(vec2 p, vec2 halfSize, vec4 radii) {
    float r = p.x < 0.0 ? (p.y < 0.0 ? radii.x : radii.w) : (p.y < 0.0 ? radii.y : radii.z);
    r = min(r, min(halfSize.x, halfSize.y));
    vec2 q = abs(p) - halfSize + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

float frontAt(float y) {
    float t = clamp(y / max(Size.y, 1.0) * 2.0 - 1.0, -1.0, 1.0);
    float dome = max(0.0, 1.0 - t * t);
    float wave = sin(t * 5.0 + Flow.y * 3.8) * 0.055 * dome;
    wave += sin(t * 13.0 - Flow.y * 5.1) * 0.012 * dome;
    // The advancing meniscus bows outward in the centre. It starts fully outside the left edge.
    return mix(-Size.y * 0.35, Size.x + Size.y * 0.65, clamp(Flow.x, 0.0, 1.0)) + Size.y * (dome * 0.17 + wave);
}

vec3 scene(vec2 uv, float clarity) {
    uv = clamp(uv, vec2(0.001), vec2(0.999));
    return mix(texture(Sampler0, uv).rgb, texture(Sampler1, uv).rgb, clamp(clarity, 0.0, 1.0));
}

void main() {
    vec2 halfSize = Size * 0.5;
    vec2 p = localPos - halfSize;
    float distance = roundedBox(p, halfSize, Radius);
    float aa = max(fwidth(distance), 0.001);
    float coverage = smoothstep(0.0, max(Rim.x, aa), -distance + aa * 0.5);
    if (coverage <= 0.0) discard;

    vec2 e = vec2(0.65, 0.0);
    vec2 normal = vec2(roundedBox(p + e.xy, halfSize, Radius) - roundedBox(p - e.xy, halfSize, Radius),
                       roundedBox(p + e.yx, halfSize, Radius) - roundedBox(p - e.yx, halfSize, Radius));
    normal = length(normal) > 0.001 ? normalize(normal) : vec2(0.0, -1.0);
    float edge = clamp(1.0 + distance / max(Glass.y, 1.0), 0.0, 1.0);
    float rimLens = edge * edge * (3.0 - 2.0 * edge);
    rimLens *= rimLens;

    float frontDistance = localPos.x - frontAt(localPos.y);
    float frontAA = max(fwidth(frontDistance), 0.7);
    float filled = 1.0 - smoothstep(-frontAA, frontAA, frontDistance);
    float derivative = frontAt(localPos.y + 0.5) - frontAt(localPos.y - 0.5);
    vec2 frontNormal = normalize(vec2(1.0, -derivative));
    float meniscus = exp(-abs(frontDistance) / max(2.0, Size.y * 0.22)) * filled;
    float dome = max(0.0, 1.0 - pow(p.y / max(halfSize.y, 1.0), 2.0));
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    vec2 inward = vec2(-normal.x, normal.y) / ScreenSize;
    vec2 frontInward = vec2(-frontNormal.x, frontNormal.y) / ScreenSize;

    // The empty vessel stays softly frosted. Injected liquid is clearer, deeper and more convex.
    vec2 emptyShift = inward * Glass.x * rimLens * 0.72;
    vec2 liquidShift = inward * Glass.x * rimLens * 1.55;
    liquidShift += frontInward * Glass.x * (meniscus * 1.7 + dome * 0.16);
    liquidShift.y += (p.y / max(halfSize.y, 1.0)) * Glass.x * 0.18 * dome / ScreenSize.y;
    vec3 emptyGlass = scene(uv + emptyShift, 0.12 + edge * Rim.w * 0.55);
    vec3 liquidGlass = scene(uv + liquidShift, 0.56 + meniscus * 0.27 + edge * 0.12);
    vec3 color = mix(emptyGlass, liquidGlass, filled);

    float glint = exp(-pow(frontDistance / max(1.1, Size.y * 0.05), 2.0)) * dome;
    float whiteLift = vertexColor.a * mix(0.30, 1.35, filled) + glint * 0.10;
    color = mix(color, vec3(1.0), clamp(whiteLift + Rim.z * mix(0.5, 1.0, filled), 0.0, 0.28));
    fragColor = vec4(min(color, vec3(1.0)), coverage) * ColorModulator;
}

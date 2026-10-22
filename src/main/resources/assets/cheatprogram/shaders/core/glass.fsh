#version 150

// Liquid glass without outlines or rim highlights.
// Sampler0 holds a (possibly blurred) copy of whatever is behind the surface, Sampler1 the same image
// unblurred. Towards the rim the clear copy is pulled inward like a lens, so the scene bends around
// the edges; the edge itself is feathered so the glass melts into what is behind it. The vertex colour
// tints the body (black for the main window, a faint white lift for clear glass elements).

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform vec2 ScreenSize;
uniform vec2 Size;
uniform vec4 Radius;   // top-left, top-right, bottom-right, bottom-left
uniform vec4 Glass;    // x: refraction (px), y: bevel width (px), z: dispersion, w: unused
uniform vec4 Rim;      // x: edge feather (px), y: unused, z: haze, w: rim clarity
uniform vec4 ColorModulator;

in vec2 localPos;
in vec4 vertexColor;

out vec4 fragColor;

float roundedBox(vec2 p, vec2 halfSize, vec4 r) {
    float radius = p.x < 0.0 ? (p.y < 0.0 ? r.x : r.w) : (p.y < 0.0 ? r.y : r.z);
    radius = min(radius, min(halfSize.x, halfSize.y));
    vec2 q = abs(p) - halfSize + radius;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
}

vec3 backdrop(vec2 uv, float clarity) {
    vec3 frosted = texture(Sampler0, uv).rgb;
    if (clarity <= 0.0) return frosted;
    return mix(frosted, texture(Sampler1, uv).rgb, clarity);
}

void main() {
    vec2 halfSize = Size * 0.5;
    vec2 p = localPos - halfSize;
    float d = roundedBox(p, halfSize, Radius);
    float aa = max(fwidth(d), 0.0001);
    float feather = max(Rim.x, aa);
    float coverage = smoothstep(0.0, feather, -d + aa * 0.5);
    if (coverage <= 0.0) discard;

    // Outward surface normal from the gradient of the distance field.
    vec2 e = vec2(0.75, 0.0);
    vec2 n = vec2(roundedBox(p + e.xy, halfSize, Radius) - roundedBox(p - e.xy, halfSize, Radius),
                  roundedBox(p + e.yx, halfSize, Radius) - roundedBox(p - e.yx, halfSize, Radius));
    float nl = length(n);
    n = nl > 0.0001 ? n / nl : vec2(0.0, -1.0);

    float bevel = max(Glass.y, 1.0);
    float edge = clamp(1.0 + d / bevel, 0.0, 1.0);   // 1 at the rim, 0 once past the bevel
    float bend = edge * edge * (3.0 - 2.0 * edge);
    bend *= bend;
    float clarity = Rim.w > 1.0 ? 1.0 : Rim.w * smoothstep(0.0, 0.85, edge);

    // Local space has y pointing down while texture space has y pointing up.
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    vec2 inward = vec2(-n.x, n.y) / ScreenSize;
    float shift = Glass.x * bend;
    vec3 col;
    if (Glass.z > 0.0) {
        col.r = backdrop(uv + inward * shift * (1.0 + Glass.z), clarity).r;
        col.g = backdrop(uv + inward * shift, clarity).g;
        col.b = backdrop(uv + inward * shift * (1.0 - Glass.z), clarity).b;
    } else {
        col = backdrop(uv + inward * shift, clarity);
    }

    col = mix(col, vertexColor.rgb, vertexColor.a);
    col = mix(col, vec3(1.0), Rim.z);

    fragColor = vec4(min(col, vec3(1.0)), coverage) * ColorModulator;
}

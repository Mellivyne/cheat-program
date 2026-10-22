#version 150

// Anti-aliased rounded rectangle drawn from a signed distance field.
// localPos is in framebuffer pixels relative to the top-left corner of the rectangle.

uniform vec2 Size;
uniform vec4 Radius;        // top-left, top-right, bottom-right, bottom-left
uniform vec4 Params;        // x: border width, y: softness (> 0 draws a soft shadow or glow)
uniform vec4 BorderTop;     // border colour along the top edge
uniform vec4 BorderBottom;  // border colour along the bottom edge
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

void main() {
    vec2 halfSize = Size * 0.5;
    float d = roundedBox(localPos - halfSize, halfSize, Radius);

    float softness = Params.y;
    if (softness > 0.0) {
        float a = 1.0 - smoothstep(-softness * 0.35, softness, d);
        a *= a;
        fragColor = vec4(vertexColor.rgb, vertexColor.a * a) * ColorModulator;
        if (fragColor.a <= 0.002) discard;
        return;
    }

    float aa = max(fwidth(d), 0.0001);
    float coverage = clamp(0.5 - d / aa, 0.0, 1.0);
    if (coverage <= 0.0) discard;

    vec4 color = vertexColor;
    float borderWidth = Params.x;
    if (borderWidth > 0.0) {
        float t = clamp(localPos.y / max(Size.y, 1.0), 0.0, 1.0);
        vec4 border = mix(BorderTop, BorderBottom, t);
        float ring = clamp(0.5 + (d + borderWidth) / aa, 0.0, 1.0) * border.a;
        float outA = ring + color.a * (1.0 - ring);
        vec3 outRgb = outA > 0.0 ? (border.rgb * ring + color.rgb * color.a * (1.0 - ring)) / outA : vec3(0.0);
        color = vec4(outRgb, outA);
    }

    fragColor = vec4(color.rgb, color.a * coverage) * ColorModulator;
    if (fragColor.a <= 0.0) discard;
}

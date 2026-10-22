#version 150
uniform sampler2D Sampler0;
uniform vec2 Size;
uniform float Radius;
uniform vec4 UVRect;
uniform float Opacity;
uniform vec4 ColorModulator;
in vec2 localPos;
in vec4 vertexColor;
out vec4 fragColor;
void main() {
    float r = min(max(0.0, Radius), min(Size.x, Size.y) * 0.5);
    vec2 q = abs(localPos - Size * 0.5) - Size * 0.5 + r;
    float d = min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
    float coverage = 1.0 - smoothstep(-max(fwidth(d), 0.75), 0.0, d);
    if (coverage <= 0.0) discard;
    vec4 sampleColor = texture(Sampler0, UVRect.xy + localPos / Size * UVRect.zw);
    fragColor = vec4(sampleColor.rgb, sampleColor.a * coverage * Opacity) * vertexColor * ColorModulator;
}

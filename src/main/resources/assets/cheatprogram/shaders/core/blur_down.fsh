#version 150

// Dual Kawase downsample pass.
uniform sampler2D InSampler;
uniform vec2 HalfPixel;
uniform float Offset;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 o = HalfPixel * Offset;
    vec3 sum = texture(InSampler, texCoord).rgb * 4.0;
    sum += texture(InSampler, texCoord - o).rgb;
    sum += texture(InSampler, texCoord + o).rgb;
    sum += texture(InSampler, texCoord + vec2(o.x, -o.y)).rgb;
    sum += texture(InSampler, texCoord - vec2(o.x, -o.y)).rgb;
    fragColor = vec4(sum / 8.0, 1.0);
}

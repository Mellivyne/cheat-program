#version 150

// Full screen backdrop behind the GUI: the blurred scene, darkened, partly desaturated and vignetted.
uniform sampler2D InSampler;
uniform vec4 Params; // x: dim, y: desaturation, z: vignette, w: opacity

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec3 col = texture(InSampler, texCoord).rgb;
    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(col, vec3(luma), Params.y);
    col *= 1.0 - Params.x;
    vec2 c = (texCoord - 0.5) * vec2(1.0, 0.85);
    float vignette = smoothstep(0.3, 0.95, length(c) * 1.25);
    col *= 1.0 - vignette * Params.z;
    fragColor = vec4(col, Params.w);
}

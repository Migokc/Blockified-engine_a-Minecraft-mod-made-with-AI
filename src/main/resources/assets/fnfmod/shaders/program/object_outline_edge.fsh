#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D OriginalSampler;

in vec2 texCoord;
in vec2 oneTexel;

out vec4 fragColor;

void main() {
    vec4 expanded = texture(DiffuseSampler, texCoord);
    vec4 original = texture(OriginalSampler, texCoord);
    if (expanded.a <= 0.001 || original.a > 0.001) {
        fragColor = vec4(0.0);
    } else {
        fragColor = vec4(expanded.rgb, 1.0);
    }
}

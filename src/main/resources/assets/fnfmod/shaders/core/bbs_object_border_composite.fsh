#version 150

uniform sampler2D DiffuseSampler;
uniform vec4 BorderColor;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    if (texture(DiffuseSampler, texCoord).a <= 0.001) discard;
    fragColor = BorderColor;
}

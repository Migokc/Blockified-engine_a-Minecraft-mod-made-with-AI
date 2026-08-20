#version 150

uniform sampler2D Sampler0;
uniform vec4 BorderColor;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    float alpha = texture(Sampler0, texCoord0).a * vertexColor.a;
    if (alpha < 0.1) discard;
    fragColor = BorderColor;
}

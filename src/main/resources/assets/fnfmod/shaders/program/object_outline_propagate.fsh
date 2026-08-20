#version 150

uniform sampler2D DiffuseSampler;
uniform float Step;

in vec2 texCoord;
in vec2 oneTexel;

out vec4 fragColor;

float remainingRadius(float encoded) {
    if (encoded <= 0.001) return -1.0;
    /* Opaque alpha belongs to ordinary vanilla glowing entities. */
    if (encoded >= 0.999) return 2.0;
    return max(0.0, encoded * 255.0 - 1.0);
}

float encodeRadius(float radius) {
    return (clamp(radius, 0.0, 253.0) + 1.0) / 255.0;
}

void considerSample(inout vec4 best, inout float bestRadius, vec2 direction) {
    vec4 candidate = texture(DiffuseSampler, texCoord + direction * oneTexel * Step);
    float radius = remainingRadius(candidate.a) - length(direction) * Step;
    if (radius >= 0.0 && radius > bestRadius) {
        best = candidate;
        bestRadius = radius;
    }
}

void main() {
    vec4 best = texture(DiffuseSampler, texCoord);
    float bestRadius = remainingRadius(best.a);
    considerSample(best, bestRadius, vec2(-1.0, -1.0));
    considerSample(best, bestRadius, vec2( 0.0, -1.0));
    considerSample(best, bestRadius, vec2( 1.0, -1.0));
    considerSample(best, bestRadius, vec2(-1.0,  0.0));
    considerSample(best, bestRadius, vec2( 1.0,  0.0));
    considerSample(best, bestRadius, vec2(-1.0,  1.0));
    considerSample(best, bestRadius, vec2( 0.0,  1.0));
    considerSample(best, bestRadius, vec2( 1.0,  1.0));
    fragColor = bestRadius < 0.0 ? vec4(0.0) : vec4(best.rgb, encodeRadius(bestRadius));
}

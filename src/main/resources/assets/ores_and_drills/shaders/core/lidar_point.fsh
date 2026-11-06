#version 150

in vec4 vertexColor;
in vec2 corner;

uniform float Softness;

out vec4 fragColor;

// Softness 0 draws the crisp pixel square; 1 draws the soft round halo of the glow pass.
void main() {
    float alpha = vertexColor.a;
    if (Softness > 0.5) {
        float falloff = clamp(1.0 - length(corner), 0.0, 1.0);
        alpha *= falloff * falloff;
    }
    if (alpha <= 0.004) {
        discard;
    }
    fragColor = vec4(vertexColor.rgb, alpha);
}

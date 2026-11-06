#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec2 ViewSize;
uniform float LidarTime;
uniform float PointScale;
uniform float Brightness;
uniform float PulseRadius;
uniform float PulseWidth;
uniform float CavePulseRadius;

out vec4 vertexColor;
out vec2 corner;

// One LiDAR return: a screen-aligned square of a few pixels around its position. UV0 is the
// corner (-1/1), UV2.x the birth tick and UV2.y the size class. New returns fade in with a short
// flash; returns near a travelling survey front glow as the front sweeps past them.
void main() {
    vec4 clip = ProjMat * ModelViewMat * vec4(Position, 1.0);
    float age = LidarTime - float(UV2.x);
    float appear = clamp(age / 8.0, 0.0, 1.0);
    float flash = clamp(1.0 - age / 30.0, 0.0, 1.0);
    float horizontal = length(Position.xz);
    float width = max(PulseWidth, 1.0);
    float pulse = 0.0;
    if (PulseRadius > 0.0) {
        float d = (horizontal - PulseRadius) / width;
        pulse = exp(-d * d);
    }
    if (CavePulseRadius > 0.0) {
        float d = (horizontal - CavePulseRadius) / width;
        pulse = max(pulse, exp(-d * d));
    }
    float size = PointScale * float(UV2.y) * (0.5 + 0.5 * appear) * (1.0 + 0.8 * flash + 0.5 * pulse);
    clip.xy += UV0 * size / ViewSize * clip.w;
    gl_Position = clip;
    vec3 rgb = Color.rgb * (1.0 + 1.4 * flash + 0.9 * pulse) + vec3(0.35 * flash + 0.2 * pulse);
    vertexColor = vec4(rgb * Brightness, Color.a * appear);
    corner = UV0;
}

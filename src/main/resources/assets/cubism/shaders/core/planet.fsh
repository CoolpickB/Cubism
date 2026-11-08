#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

// ColorModulator.x is the cube radius and z how far the camera is into orbit. ModelOffset is the direction toward the sun.
// TextureMat column 3 is the camera position in the cube frame.
layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec3 cubePosition;

layout(location = 0) out vec4 fragColor;

// The rounded edges of the cube surface have this radius, as in CubeTime.
const float EDGE = 32.0;

void main() {
    float inner = ColorModulator.x - EDGE;
    vec3 normal = normalize(cubePosition - clamp(cubePosition, -inner, inner));
    float sunward = dot(normal, ModelOffset);
    float day = smoothstep(-0.15, 0.35, sunward);
    // The night side keeps a dim blue, as under starlight.
    vec3 color = vertexColor.rgb * mix(vec3(0.07, 0.09, 0.16), vec3(1.0), day);
    // A thin haze of air over the disc, thicker toward the rim, only on the day side.
    vec3 view = normalize(cubePosition - TextureMat[3].xyz);
    float rim = pow(1.0 - clamp(dot(normal, -view), 0.0, 1.0), 3.0);
    color = mix(color, vec3(0.45, 0.65, 1.0) * mix(0.15, 1.0, day), rim * 0.6);
    fragColor = vec4(color, smoothstep(0.0, 0.25, ColorModulator.z));
}

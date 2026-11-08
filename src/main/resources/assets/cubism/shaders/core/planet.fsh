#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

// ColorModulator.x is the cube radius and z how far the camera is into orbit. ModelOffset is the direction toward the sun.
layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec3 cubePosition;

layout(location = 0) out vec4 fragColor;

// The rounded edges of the cube surface have this radius, as in CubeTime.
const float EDGE = 32.0;

void main() {
    float inner = ColorModulator.x - EDGE;
    vec3 normal = normalize(cubePosition - clamp(cubePosition, -inner, inner));
    float day = smoothstep(-0.12, 0.3, dot(normal, ModelOffset));
    fragColor = vec4(vertexColor.rgb * mix(0.03, 1.0, day), smoothstep(0.0, 0.25, ColorModulator.z));
}

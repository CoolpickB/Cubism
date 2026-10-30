#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:oit.glsl>

layout(location = 0) in float vertexDistance;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in vec4 cubeClip;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

vec4 calculateFinalColor(vec4 color) {
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #endif
    return color;
}

void main() {
    if (cubeClip.w > 0.0 && max(abs(cubeClip.x), abs(cubeClip.z)) > cubeClip.y) {
        discard;
    }
    vec4 color = vertexColor;
    #ifndef OIT_DEPTH_BOUNDS
    float cloudsEnd = cubeClip.w > 0.0 ? min(cubeClip.w, FogCloudsEnd) : FogCloudsEnd;
    color.a *= 1.0f - linear_fog_value(vertexDistance, 0, cloudsEnd);
    #endif

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}

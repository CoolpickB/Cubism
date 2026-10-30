#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;

// The view direction in the frame of the camera face.
layout(location = 0) out vec3 faceDirection;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    faceDirection = Position;
}

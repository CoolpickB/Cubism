#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// The position is in the cube frame, from the center of the cube. TextureMat is packed by CubeSky as for the air:
// columns 0-2 turn the camera face frame into the cube frame, and column 3 is the camera position in the cube frame.
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec3 cubePosition;

void main() {
    vec3 direction = transpose(mat3(TextureMat)) * (Position - TextureMat[3].xyz);
    // The pass has no depth, so each vertex goes to a fixed distance along its view ray. The picture on the screen is the same.
    gl_Position = ProjMat * ModelViewMat * vec4(normalize(direction) * 40.0, 1.0);
    vertexColor = Color;
    cubePosition = Position;
}

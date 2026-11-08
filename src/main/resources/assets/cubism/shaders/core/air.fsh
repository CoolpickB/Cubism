#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>

// Inputs, packed into the transform uniforms by CubeSky:
//   TextureMat: columns 0-2 turn the camera face frame into the cube frame. Column 3 is the camera position in the
//               cube frame, and in w the local time at the camera.
//   ColorModulator: x the cube radius, y the brightest stars of the day, z how far the camera is into orbit, 0 to 1, w the sky fog distance of the planet.
//   ModelOffset: the direction toward the sun in the cube frame.
// Sampler0 holds the vanilla colors over one day: row 0 the sky color with the star brightness in alpha, row 1 the
// fog color, row 2 the sunrise and sunset glow with its strength in alpha.
uniform sampler2D Sampler0;

layout(location = 0) in vec3 faceDirection;

layout(location = 0) out vec4 fragColor;

const float DAY = 24000.0;
const float PI = 3.14159265;
// The rounded edges of the cube surface have this radius, as in CubeTime.
const float EDGE = 32.0;
// The air ends this high above the surface, at the build limit.
const float TOP = 256.0;
const float SCALE_HEIGHT = 48.0;
const float DENSITY = 0.15;
// The highest the sun stands over a face, as a sine: 54.7 degrees.
const float SUN_REACH = 0.81649658;
const vec3 AXIS = vec3(0.57735027);
const int STEPS = 48;

void main() {
#ifdef SPACE
    fragColor = vec4(0.0, 0.0, 0.0, 1.0);
#else
    vec3 view = normalize(faceDirection);
    vec3 direction = mat3(TextureMat) * view;
    vec3 camera = TextureMat[3].xyz;
    float inner = ColorModulator.x - EDGE;
    float starMax = ColorModulator.y;
    vec3 sun = ModelOffset;
    // The fog values of vanilla belong to the camera and change with the face under it, so they turn into the same
    // value for the whole planet as the camera rises.
    float skyEnd = mix(FogSkyEnd, ColorModulator.w, ColorModulator.z);
    float fogAlpha = mix(FogColor.a, 1.0, ColorModulator.z);
    vec3 sunMotion = cross(AXIS, sun);

    // The fog color of vanilla at the camera has extra tints, such as the blend toward the sky at a short render
    // distance, which also go onto the fog of the other times.
    // From orbit it goes away: the fog of vanilla then belongs to one face and would tint the whole planet with it.
    vec3 fogTint = (FogColor.rgb - texture(Sampler0, vec2(TextureMat[3].w / DAY, 0.5)).rgb) * (1.0 - ColorModulator.z);

    vec3 q0 = camera - clamp(camera, -inner, inner);
    float ground = min(-48.0, length(q0) - EDGE - 16.0);

    float far = 2.0 * (inner + EDGE + TOP) * 1.7320508;
    float transmittance = 1.0;
    float starlight = 0.0;
    vec3 color = vec3(0.0);
    float previous = 0.0;
    // Each pixel takes its samples at a different place in the steps, so the long far steps show as fine noise, not
    // as rings.
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    float topDensity = exp(-TOP / SCALE_HEIGHT);
    for (int i = 1; i <= STEPS; i++) {
        // Short steps near the camera, where the air is thick and the colors change.
        float f = float(i) / float(STEPS);
        float distance = far * f * f;
        float step = distance - previous;
        vec3 p = camera + direction * (previous + jitter * step);
        previous = distance;

        vec3 q = p - clamp(p, -inner, inner);
        float r = length(q);
        float height = r - EDGE;
        vec3 normal = r > 0.0 ? q / r : vec3(0.0, 1.0, 0.0);

        // The local time is the vanilla time with the same height of the sun over this bit of air.
        float rise = acos(clamp(dot(sun, normal) / SUN_REACH, -1.0, 1.0)) / PI * 12000.0;
        float ticks = dot(sunMotion, normal) >= 0.0 ? 6000.0 - rise : 6000.0 + rise;
        vec4 sky = texture(Sampler0, vec2(ticks / DAY, 1.0 / 6.0));
        vec3 fog = texture(Sampler0, vec2(ticks / DAY, 0.5)).rgb;
        // Vanilla tints the fog with the glow by how much the view looks toward the sun along the horizon. Here that
        // is the horizon of the surface under this bit of air.
        vec4 glow = texture(Sampler0, vec2(ticks / DAY, 5.0 / 6.0));
        vec3 sunAlong = sun - normal * dot(sun, normal);
        float toSun = dot(sunAlong, sunAlong) > 1.0E-8 ? max(dot(direction, normalize(sunAlong)), 0.0) : 0.0;
        fog = max(mix(fog, glow.rgb, toSun * glow.a) + fogTint, 0.0);
        // Vanilla draws the sky as a disc 16 blocks over the camera, faded into the fog by distance, so the sky turns
        // to fog toward the horizon. Here the horizon is that of the surface under this bit of air, so the haze bends
        // over the edges with the air.
        float elevation = dot(direction, normal);
        float haze = elevation <= 0.0 ? 1.0 : clamp(16.0 / (elevation * skyEnd), 0.0, 1.0);
        vec3 air = mix(sky.rgb, fog, haze * fogAlpha);

        float opacity;
        if (height < ground) {
            opacity = 1.0;
        } else if (height < TOP) {
            // The density goes to zero at the top, so the end of the air has no edge.
            float density = (exp(-max(height, 0.0) / SCALE_HEIGHT) - topDensity) / (1.0 - topDensity);
            opacity = 1.0 - exp(-DENSITY * density * step);
        } else {
            opacity = 0.0;
        }
        float weight = transmittance * opacity;
        color += weight * air;
        starlight += weight * sky.a / starMax;
        transmittance -= weight;
        if (height < ground) {
            break;
        }
    }
    // The stars under the air were drawn at the brightest of the day. The alpha dims them to the vanilla brightness of
    // the air they shine through, and leaves them full in space.
    fragColor = vec4(color, 1.0 - transmittance - starlight);
#endif
}

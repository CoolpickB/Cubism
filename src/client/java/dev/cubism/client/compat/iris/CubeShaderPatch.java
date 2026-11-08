package dev.cubism.client.compat.iris;

import dev.cubism.client.compat.sodium.SeamFaces;

import dev.cubism.client.SeamClient;
import dev.cubism.client.render.CubeSky;
import dev.cubism.worldgen.SeamLayout;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Edits the source of Complementary Unbound r5.9.3 as Iris reads it, so that it lights with the one real sun of the cube,
 * takes the sky up from the rounded surface under the camera, and puts its volumetric clouds on a shell around the cube.
 * Every edit replaces an exact piece of text. If a pack does not hold that text, the edit does nothing, and the pack
 * keeps the tilted sun that every pack gets.
 */
public final class CubeShaderPatch {
	private static final String COMMON = "/lib/util/commonFunctions.glsl";
	private static final String COMMON_ROOT = "/lib/common.glsl";
	private static final String TIME_ANCHOR = "    #if SHADOW_QUALITY == -1\n      float timeAngle = worldTime / 24000.0;";
	private static final String SUN_ANGLE = "float tAmin     = fract(sunAngle - 0.033333333);";
	private static final String AURORA = "/lib/atmospherics/auroraBorealis.glsl";
	private static final String STARS = "/lib/atmospherics/stars.glsl";
	private static final String MAIN_CLOUDS = "/lib/atmospherics/clouds/mainClouds.glsl";
	private static final String UNBOUND_CLOUDS = "/lib/atmospherics/clouds/unboundClouds.glsl";
	private static final String MAIN_FOG = "/lib/atmospherics/fog/mainFog.glsl";
	private static final String MAIN_LIGHTING = "/lib/lighting/mainLighting.glsl";
	/** The programs that draw once for the whole view. They take the rounded up at the camera. */
	private static final String[] CAMERA_UP_PROGRAMS = {
		"composite", "composite1", "composite3", "composite4", "composite5", "deferred1",
		"gbuffers_skybasic", "gbuffers_skytextured", "gbuffers_clouds", "gbuffers_weather", "gbuffers_hand"
	};
	/** The programs that draw the ground and what stands on it. They take the rounded up at each point. */
	private static final String[] POINT_UP_PROGRAMS = {
		"gbuffers_terrain", "gbuffers_water", "gbuffers_block", "gbuffers_entities", "gbuffers_textured", "gbuffers_basic", "voxy_opaque", "voxy_translucent"
	};

	/**
	 * Voxy draws the levels of detail of every face with the values of the camera face, from a uniform block that the
	 * program cannot change. These copies stand in for them, so that each point can take the values of its own face, as
	 * {@link IrisFaceUniforms} gives the pack while a folded face of Sodium draws.
	 */
	private static final String VOXY_FACE_VALUES = """
		mat3 cw_viewToFeet = mat3(vxModelViewInv);
		mat4 cw_vxModelView = vxModelView;
		mat4 cw_vxModelViewInv = vxModelViewInv;
		mat4 cw_vxModelViewPrev = vxModelViewPrev;
		mat4 cw_shadowModelView = shadowModelView;
		mat4 cw_shadowModelViewInverse = shadowModelViewInverse;
		vec3 cw_cameraPosition = cameraPosition;
		vec3 cw_previousCameraPosition = previousCameraPosition;
		mat3 cw_faceTurn = mat3(1.0);
		#define vxModelView cw_vxModelView
		#define vxModelViewInv cw_vxModelViewInv
		#define vxModelViewPrev cw_vxModelViewPrev
		#define shadowModelView cw_shadowModelView
		#define shadowModelViewInverse cw_shadowModelViewInverse
		#define cameraPosition cw_cameraPosition
		#define previousCameraPosition cw_previousCameraPosition
		""";

	/**
	 * The face of a point is the one whose up points most along the point from the center of the cube, the same diagonal
	 * split as the storage. The values of that face are the ones of the camera face turned by its motion.
	 */
	private static final String VOXY_FACE_SETUP = """
		void cubismVoxyFace() {
		    if (cubism_active == 0) return;
		    vec3 view = ScreenToView(vec3(gl_FragCoord.xy / vec2(viewWidth, viewHeight), gl_FragCoord.z));
		    vec3 cube = (cubism_cubeFromView * vec4(view, 1.0)).xyz;
		    mat4 motion = mat4(1.0);
		    float best = -1.0e30;
		    #define CW_FACE(m) { float d = dot(cube, mat3(cubism_cubeFromFeet) * m[1].xyz); if (d > best) { best = d; motion = m; } }
		    CW_FACE(cubism_voxyTurn0) CW_FACE(cubism_voxyTurn1) CW_FACE(cubism_voxyTurn2)
		    CW_FACE(cubism_voxyTurn3) CW_FACE(cubism_voxyTurn4) CW_FACE(cubism_voxyTurn5)
		    #undef CW_FACE
		    mat4 turn = mat4(mat3(motion));
		    mat4 back = transpose(turn);
		    vxModelView = vxModelView * turn;
		    vxModelViewInv = back * vxModelViewInv;
		    vxModelViewPrev = vxModelViewPrev * turn;
		    gbufferModelView = vxModelView;
		    gbufferModelViewInverse = vxModelViewInv;
		    gbufferPreviousModelView = vxModelViewPrev;
		    shadowModelView = shadowModelView * turn;
		    shadowModelViewInverse = back * shadowModelViewInverse;
		    cameraPosition = mat3(back) * (cameraPosition - motion[3].xyz);
		    previousCameraPosition = mat3(back) * (previousCameraPosition - motion[3].xyz);
		    cw_faceTurn = mat3(motion);
		    eastVec = normalize(gbufferModelView[0].xyz);
		    northVec = normalize(gbufferModelView[2].xyz);
		}

		""";

	private static final String SUN_DEFINE =
		"    #define GetSunVector() normalize((gbufferModelView * vec4(vec3(-sin(overworldAngle), cos(overworldAngle) * SUN_ROTATION_DATA) * 2000.0, 1.0)).xyz)";
	private static final String UP = "upVec = normalize(gbufferModelView[1].xyz);";

	/**
	 * The cube coordinates of the clouds are about the center of the cube. The altitude of a point is the height it would
	 * have on a face, over a rounded box: the core box, and around it the distance to the core. Over the middle of a face
	 * it is the plain y of the face storage, so the cloud height of the pack stays as it is.
	 */
	private static final String HELPERS = """
		#define CUBEWORLD
		#ifndef VOXY_PATCH
		    uniform int cubism_active;
		    uniform vec3 cubism_sunPosition;
		    uniform vec3 cubism_upPosition;
		    uniform mat4 cubism_cubeFromFeet;
		    uniform mat4 cubism_cubeFromView;
		    uniform float cubism_core;
		    uniform float cubism_centerY;
		    uniform mat3 cubism_celestialFrame;
		    uniform mat3 cubism_viewToFeet;
		    uniform vec3 cubism_shadowLight;
		    uniform vec3 cubism_daySkyColor;
		    uniform float cubism_orbit;
		    // The columns of the planet mesh, see PlanetView. Voxy binds no textures of the pack.
		    #define CUBISM_PLANET
		    uniform sampler2D cubism_planetTex;
		    // From the storage frame of the face that draws into the one of the camera face. It is the identity except while a
		    // folded face draws.
		    uniform mat3 cubism_faceTurn;
		    #define cubismTurn(v) (cubism_faceTurn * (v))
		    #define cubismFolded() (cubism_faceTurn != mat3(1.0))
		    // From the frame of the camera face back into the one of the face it left, on the frame of a crossing. Else the identity.
		    uniform mat3 cubism_crossTurn;
		    // The normals that the programs of the ground store for the passes after them are read back with the view matrix of the
		    // camera face. A folded face draws with the matrix of its own face, so it stores them in the frame of the camera face.
		    #define cubism_normalOut(n) ((cubism_active != 0 ? cubism_viewToFeet : mat3(gbufferModelViewInverse)) * (n))
		#else
		    // Voxy takes the other uniforms from the list that CubeShaderPatch adds to voxy.json. That list holds no 3 by 3
		    // matrices. The program sets the values of the face of each point, see VOXY_FACE_VALUES.
		    #define cubismTurn(v) (cw_faceTurn * (v))
		    #define cubismFolded() (cw_faceTurn != mat3(1.0))
		    #define cubism_normalOut(n) (cw_viewToFeet * (n))
		    #define cubism_celestialFrame mat3(gbufferModelViewInverse)
		#endif
		    // The sky color of Iris follows the time at the camera. The light that the sky gives a point follows the time of the
		    // point, as vanilla dims the sky color of the full day: by the height of the sun.
		    #define cubismSkyColor() (cubism_active != 0 ? cubism_daySkyColor * clamp(sin(cubismSunAngle() * 6.28318531) * 2.0 + 0.5, 0.0, 1.0) : skyColor)
		    // 0 on the ground, 1 well above the air. In orbit the air no longer lies between the camera and what it sees.
		    #define cubismSpace() (cubism_active != 0 ? cubism_orbit : 0.0)
		    #define CUBEWORLD_UP_VEC (cubism_active != 0 ? normalize(cubism_upPosition) : normalize(gbufferModelView[1].xyz))
		    float cubismAlt(vec3 p) {
		        if (cubism_active == 0) return p.y;
		        return cubism_centerY + cubism_core + length(max(abs(p) - cubism_core, 0.0));
		    }
		    vec3 cubismNormal(vec3 p) {
		        vec3 e = sign(p) * max(abs(p) - cubism_core, 0.0);
		        return dot(e, e) > 0.0 ? normalize(e) : normalize(p);
		    }
		    vec3 cubismToFeet(vec3 d) {
		        return cubism_active != 0 ? transpose(mat3(cubism_cubeFromFeet)) * d : d;
		    }
		    // The height of a point relative to the camera. It goes through view space, which every face shares, so it
		    // holds in programs that draw a folded face with the matrices of that face.
		    float cubismFeetAlt(vec3 playerPos) {
		        if (cubism_active == 0) return playerPos.y + cameraPosition.y;
		        return cubismAlt((cubism_cubeFromView * vec4(mat3(gbufferModelView) * playerPos, 1.0)).xyz);
		    }
		    float cubismCameraAlt() {
		        return cubism_active != 0 ? cubismAlt(cubism_cubeFromFeet[3].xyz) : cameraPosition.y;
		    }
		    // The distance that the border fog grows with. The pack takes a box upright in the frame it draws in, which is the
		    // frame of the face in the programs of the ground and the frame of the camera face in the deferred pass. The two
		    // did not agree on a folded face, and the fog over the far ground jumped at a crossing. The straight distance is
		    // the same in every frame, and it reaches the render distance before the chunks of any face end.
		    float cubismBorderDist(vec3 playerPos) {
		        return cubism_active != 0 ? length(playerPos) : max(length(playerPos.xz), abs(playerPos.y));
		    }
		    // The rounded up at a point of view space.
		    vec3 cubismUpAtView(vec3 viewPos) {
		        vec3 p = (cubism_cubeFromView * vec4(viewPos, 1.0)).xyz;
		        return normalize(transpose(mat3(cubism_cubeFromView)) * cubismNormal(p));
		    }
		    // 0 where a ray of view space from the camera meets the sea surface of the planet, else 1. It steps by the height
		    // over the sea, which is the distance to that rounded surface, so it never steps through it.
		    float cubismPlanetMiss(vec3 viewDir) {
		        if (cubism_active == 0) return 1.0;
		        vec3 cam = cubism_cubeFromFeet[3].xyz;
		        vec3 dir = normalize(mat3(cubism_cubeFromView) * viewDir);
		        float t = 0.0;
		        for (int j = 0; j < 48; j++) {
		            float gap = cubismAlt(cam + t * dir) - 62.0;
		            if (gap < 1.0) return 0.0;
		            t += gap;
		        }
		        return 1.0;
		    }
		#ifdef CUBISM_PLANET
		    // Two bytes of a texel of the planet texture, the low one in red.
		    float cubismPlanetShort(ivec2 at) {
		        vec4 t = texelFetch(cubism_planetTex, at, 0);
		        return floor(t.r * 255.0 + 0.5) + floor(t.g * 255.0 + 0.5) * 256.0 - 1024.0;
		    }
		    // Where a point of the cube frame is over the planet mesh: xy the place on its face in cells, z the height over the
		    // rounded surface of the mesh, w the face. The mesh is rounded by the edge rounding of the world, not by the 32
		    // blocks of cubismAlt, and over an edge the place follows the normal of that rounding, as the mesh does.
		    vec4 cubismPlanetPlace(vec3 p) {
		        float rounding = cubismPlanetShort(ivec2(0, 64));
		        float radius = cubism_core + 32.0;
		        float core = radius - rounding;
		        vec3 q = clamp(p, -core, core);
		        vec3 e = p - q;
		        vec3 a = abs(e);
		        float m = max(a.x, max(a.y, a.z));
		        if (m < 0.001) return vec4(0.0, 0.0, -1.0e6, 0.0);
		        vec2 uv;
		        float face;
		        if (a.x == m) { face = e.x < 0.0 ? 1.0 : 0.0; uv = q.yz + e.yz * rounding / m; }
		        else if (a.y == m) { face = e.y < 0.0 ? 3.0 : 2.0; uv = q.xz + e.xz * rounding / m; }
		        else { face = e.z < 0.0 ? 5.0 : 4.0; uv = q.xy + e.xy * rounding / m; }
		        return vec4((uv / radius + 1.0) * 32.0, length(e) - rounding, face);
		    }
		    // How far a ray of the cube frame goes before it meets a column of the planet mesh, at most maxT. The mesh writes no
		    // depth. Over the highest column the ray steps by its height over that, and among the columns by half a cell.
		    float cubismPlanetHit(vec3 origin, vec3 dir, float maxT) {
		        float highest = cubismPlanetShort(ivec2(1, 64));
		        float halfCell = (cubism_core + 32.0) / 64.0;
		        float t = 0.0;
		        for (int j = 0; j < 96; j++) {
		            vec3 p = origin + t * dir;
		            vec4 place = cubismPlanetPlace(p);
		            ivec2 cell = clamp(ivec2(floor(place.xy)), ivec2(0), ivec2(63));
		            if (place.z < cubismPlanetShort(ivec2(int(place.w) * 64 + cell.x, cell.y))) return t;
		            // The planet is convex, so a ray over every column that goes away from it never comes back.
		            if (place.z > highest && dot(dir, cubismNormal(p)) >= 0.0) return maxT;
		            t += max(place.z - highest, halfCell);
		            if (t > maxT) return maxT;
		        }
		        return maxT;
		    }
		    // 1 over a cold biome, 0 elsewhere, blended between the centers of the cells.
		    float cubismPlanetCold(vec3 p) {
		        vec4 place = cubismPlanetPlace(p);
		        vec2 c = clamp(place.xy - 0.5, vec2(0.0), vec2(63.0));
		        ivec2 i = ivec2(min(floor(c), vec2(62.0)));
		        vec2 f = c - vec2(i);
		        int x = int(place.w) * 64 + i.x;
		        float c00 = texelFetch(cubism_planetTex, ivec2(x, i.y), 0).b;
		        float c10 = texelFetch(cubism_planetTex, ivec2(x + 1, i.y), 0).b;
		        float c01 = texelFetch(cubism_planetTex, ivec2(x, i.y + 1), 0).b;
		        float c11 = texelFetch(cubism_planetTex, ivec2(x + 1, i.y + 1), 0).b;
		        return mix(mix(c00, c10, f.x), mix(c01, c11, f.x), f.y);
		    }
		#endif
		    // The blocks of a face all face its flat up, while the time of day turns with the rounded up over an edge. Seen
		    // from the light, the top of a block next to an edge then took the full light of its own face, and the one across
		    // the edge none, a hard line. The light is turned by the tilt of the rounded up from the flat one, so the direct
		    // light turns over the same width as the time does, and both faces get the same light at the diagonal.
		    vec3 cubismBendLight(vec3 lightView, vec3 viewPos) {
		        if (cubism_active == 0) return lightView;
		        vec3 p = (cubism_cubeFromView * vec4(viewPos, 1.0)).xyz;
		        vec3 a = abs(p);
		        vec3 flatUp = a.y >= a.x && a.y >= a.z ? vec3(0.0, sign(p.y), 0.0) : a.x >= a.z ? vec3(sign(p.x), 0.0, 0.0) : vec3(0.0, 0.0, sign(p.z));
		        vec3 n = cubismNormal(p);
		        vec3 axis = cross(n, flatUp);
		        float s = length(axis);
		        if (s < 0.0001) return lightView;
		        axis /= s;
		        float c = dot(n, flatUp);
		        mat3 toCube = mat3(cubism_cubeFromView);
		        vec3 l = toCube * lightView;
		        l = l * c + cross(axis, l) * s + axis * dot(axis, l) * (1.0 - c);
		        return transpose(toCube) * l;
		    }
		    // The programs that draw the ground take the up, and so the time of day, of each point they draw. Two faces
		    // then meet at an edge with the same light, and the light turns smoothly around it.
		    #if defined CUBEWORLD_POINT_UP && defined FRAGMENT_SHADER
		        vec3 cubismPointUp() {
		            if (cubism_active == 0) return normalize(gbufferModelView[1].xyz);
		            vec4 clip = vec4(vec3(gl_FragCoord.xy / vec2(viewWidth, viewHeight), gl_FragCoord.z) * 2.0 - 1.0, 1.0);
		            vec4 view = gbufferProjectionInverse * clip;
		            return cubismUpAtView(view.xyz / view.w);
		        }
		    #elif defined CUBEWORLD_POINT_UP && defined VERTEX_SHADER
		        vec3 cubismPointUp() {
		            if (cubism_active == 0) return normalize(gbufferModelView[1].xyz);
		            return cubismUpAtView((gl_ModelViewMatrix * gl_Vertex).xyz);
		        }
		    #endif
		    // The time of day where the sky is seen from: the rounded up at the camera for the programs that draw the whole
		    // view, the up at each point for the programs that draw the ground, and the up of the face for the rest. It follows the height of the sun over that
		    // up, scaled so that the sun at its highest over the middle of a face is noon. The angle around the axis of the
		    // sun is not used for it: at the two corners on the axis the sun circles the horizon, and that angle has no
		    // stable value there. Morning or afternoon comes from the side the sun is on. Same in CubeShaderPatch.java.
		    float cubismSunAngle() {
		        if (cubism_active == 0) return sunAngle;
		        #if defined CUBEWORLD_CAMERA_UP
		            vec3 upView = cubism_upPosition;
		        #elif defined CUBEWORLD_POINT_UP && (defined FRAGMENT_SHADER || defined VERTEX_SHADER)
		            vec3 upView = cubismPointUp();
		        #else
		            vec3 upView = gbufferModelView[1].xyz;
		        #endif
		        vec3 n = normalize(mat3(cubism_cubeFromView) * upView);
		        vec3 s = normalize(mat3(cubism_cubeFromView) * cubism_sunPosition);
		        float local = acos(clamp(dot(s, n) / 0.81649658, -1.0, 1.0));
		        if (dot(s, cross(vec3(0.57735027), n)) < 0.0) local = -local;
		        float d = fract(local / 6.28318531);
		        float timeOfDay = (2.0 * d + 0.5 - cos(d * 3.14159265) * 0.5) / 3.0;
		        return fract(timeOfDay + 0.25);
		    }
		""";

	/**
	 * The aurora of the cube hangs over the rounded surface like the clouds, between two heights. A point of it shows only
	 * where it is night under it. The aurora condition of the pack picks where: a snowy biome is a cold cell of the planet
	 * texture under the point, and the full moon is the phase of the one moon of the world (see CubeTime). The pattern of
	 * the pack lies on the three axis planes, blended by the normal so that it bends over an edge. Over the middle of a
	 * face only one plane counts.
	 */
	private static final String CUBE_AURORA = """

		#ifdef CUBEWORLD
		#define CUBISM_CUBE_AURORA
		const float cubismAuroraLow = 320.0;
		const float cubismAuroraDepth = 240.0;

		float cubismAuroraNoise(vec2 planePos, float auroraAnimate) {
		    #if AURORA_STYLE == 1
		        planePos = floor(planePos) * 0.0007;
		        float noise = texture2DLod(noisetex, planePos, 0.0).b;
		        noise = pow2(pow2(pow2(pow2(1.0 - 2.0 * abs(noise - 0.5)))));
		        noise *= pow1_5(texture2DLod(noisetex, planePos * 100.0 + auroraAnimate, 0.0).b);
		    #else
		        planePos *= 0.0007;
		        float noise = texture2DLod(noisetex, planePos, 0.0).r;
		        noise = pow2(pow2(pow2(pow2(1.0 - 2.0 * abs(noise - 0.5)))));
		        noise *= texture2DLod(noisetex, planePos * 3.0 + auroraAnimate, 0.0).b;
		        noise *= texture2DLod(noisetex, planePos * 5.0 - auroraAnimate, 0.0).b;
		    #endif
		    return noise;
		}



		// The ray ends at maxT, where it meets the ground.
		vec3 GetAuroraBorealisCube(vec3 viewPos, float VdotU, float dither, float maxT) {
		    // The rain is at the camera, and from orbit the camera sees past it.
		    float vis = 1.0 - rainFactor * (1.0 - cubismSpace());
		    #if AURORA_CONDITION == 1 || AURORA_CONDITION == 3
		        vis -= float(moonPhase);
		    #endif
		    // 1 where the aurora shows over any ground, 0 where it shows over cold ground only.
		    #if AURORA_CONDITION == 2 || AURORA_CONDITION == 3
		        float everywhere = 0.0;
		    #elif AURORA_CONDITION == 4
		        float everywhere = moonPhase == 0 ? 1.0 : 0.0;
		    #else
		        float everywhere = 1.0;
		    #endif
		    vis = max0(vis);
		    if (vis <= 0.0 || maxBlindnessDarkness >= 1.0) return vec3(0.0);

		    vec3 dir = normalize(mat3(cubism_cubeFromView) * viewPos);
		    vec3 cam = cubism_cubeFromFeet[3].xyz;
		    vec3 sunDir = normalize(mat3(cubism_cubeFromView) * cubism_sunPosition);
		    float top = cubismAuroraLow + cubismAuroraDepth;

		    // Step along the ray into the band of the aurora, from below or from above, by the gap to it.
		    float t = 0.0;
		    bool hit = false;
		    for (int j = 0; j < 64; j++) {
		        float alt = cubismAlt(cam + t * dir);
		        float gap = max(cubismAuroraLow - alt, alt - top);
		        if (gap < 1.0) { hit = true; break; }
		        t += max(gap, max(2.0, t * 0.01));
		        if (t > min(maxT, 100000.0)) break;
		    }
		    if (!hit) return vec3(0.0);

		    #ifdef DEFERRED1
		        int sampleCount = 25;
		    #else
		        int sampleCount = 10;
		    #endif
		    float layer = cubismAuroraDepth / float(sampleCount);

		    float auroraAnimate = frameTimeCounter * 0.001;
		    vec2 wind = vec2(syncedTime * 0.04, 0.0);
		    vec3 aurora = vec3(0.0);
		    float path = 0.0;
		    t += layer * dither;
		    for (int i = 0; i < sampleCount * 2; i++) {
		        if (t > maxT) break;
		        vec3 p = cam + t * dir;
		        float alt = cubismAlt(p);
		        // Out of the top into space, or out of the bottom toward the ground, which hides the far side of the band.
		        if (alt < cubismAuroraLow - 1.0 || alt > top + 1.0) break;
		        vec3 n = cubismNormal(p);
		        // About one sample for each layer that a ray straight up crosses. A slanted ray takes longer steps, which
		        // weigh more, as it sees more of the aurora.
		        float stepLen = layer / max(abs(dot(dir, n)), 0.2);
		        float weight = stepLen / cubismAuroraDepth;
		        t += stepLen;
		        path += weight;

		        float where = 1.0 - clamp01((dot(sunDir, n) + 0.0625) / 0.125);
		        if (where > 0.0 && everywhere < 1.0) {
		            #ifdef CUBISM_PLANET
		                where *= cubismPlanetCold(p);
		            #else
		                where *= inSnowy;
		            #endif
		        }
		        if (where > 0.0) {
		            vec3 w = pow2(pow2(n));
		            w /= w.x + w.y + w.z;
		            vec3 q = p * 0.037;
		            float noise = 0.0;
		            if (w.x > 0.01) noise += w.x * cubismAuroraNoise(q.zy + wind, auroraAnimate);
		            if (w.y > 0.01) noise += w.y * cubismAuroraNoise(q.xz + wind, auroraAnimate);
		            if (w.z > 0.01) noise += w.z * cubismAuroraNoise(q.xy + wind, auroraAnimate);

		            float current = clamp01((alt - cubismAuroraLow) / cubismAuroraDepth);
		            float currentM = 1.0 - current;
		            // The pack puts more of its samples near the bottom of the aurora, which weighs it there.
		            float density = min(0.5 / sqrt(max(current, 0.01)), 3.0);
		            aurora += where * weight * density * noise * currentM * mix(vec3(7.0, 2.2, 12.0), vec3(6.0, 16.0, 12.0), pow2(pow2(currentM)));
		        }
		        if (path > 3.0) break;
		    }

		    // The pack dims its flat aurora overhead, where the band is thin to the eye. Here the length of the ray in the band
		    // does that, and this scale brings the band seen edge on to the brightness of the pack at the horizon.
		    #if AURORA_STYLE == 1
		        aurora *= 1.3 * 0.35;
		    #else
		        aurora *= 1.8 * 0.35;
		    #endif
		    #ifdef ATM_COLOR_MULTS
		        aurora *= sqrtAtmColorMult;
		    #endif
		    return aurora * vis * (1.0 - maxBlindnessDarkness);
		}
		#endif

		vec3 GetAuroraBorealis(vec3 viewPos, float VdotU, float dither) {
		    #ifdef CUBEWORLD
		        if (cubism_active != 0) return GetAuroraBorealisCube(viewPos, VdotU, dither, 1.0e9);
		    #endif
		    return GetAuroraBorealisFlat(viewPos, VdotU, dither);
		}
		""";

	/**
	 * Put on its own lines before an edit to the voxel volumes, so that it still compiles in a pack that did not take the
	 * helpers. It goes at the edit and not at the top of the file, which comes before the helpers in some programs.
	 */
	private static final String VOXEL_FALLBACK = "\n#ifndef cubismTurn\n#define cubismTurn(v) (v)\n#define cubismFolded() false\n#endif\n";

	private static boolean pending;
	private static boolean active;

	private CubeShaderPatch() {}

	/** True when the shader pack in use took the patch, so it lights with the real sun. */
	public static boolean active() {
		return active;
	}

	public static boolean packInUse() {
		return IrisApi.getInstance().isShaderPackInUse();
	}

	/** Called when Iris has read all the files of a pack. */
	public static void finishPack() {
		active = pending;
		pending = false;
	}

	public static String patch(Path path, String source) {
		String name = path.toString().replace('\\', '/');
		// The pack is written with Windows line ends, and the edits match on whole lines.
		source = source.replace("\r\n", "\n");
		if (name.endsWith(COMMON)) {
			if (!source.contains(SUN_DEFINE)) {
				return source;
			}
			pending = true;
			String sun = "    #ifdef CUBEWORLD\n"
				+ "        #define GetSunVector() (cubism_active != 0 ? normalize(cubism_sunPosition) : "
				+ SUN_DEFINE.substring(SUN_DEFINE.indexOf("normalize(")) + ")\n"
				+ "    #else\n" + SUN_DEFINE + "\n    #endif";
			return source.replace(SUN_DEFINE, sun);
		}
		if (name.endsWith(COMMON_ROOT)) {
			// The helpers go after the uniforms of the pack and before the time of day, which they replace.
			// Without a shadow map the pack takes the time of the world. On the cube it takes the local sun as with one.
			return source.replace(TIME_ANCHOR, HELPERS + TIME_ANCHOR.replace("#if SHADOW_QUALITY == -1", "#if SHADOW_QUALITY == -1 && !defined CUBEWORLD"))
				.replace(SUN_ANGLE, "float tAmin     = fract(cubismSunAngle() - 0.033333333);");
		}
		if (name.endsWith(AURORA)) {
			String header = "vec3 GetAuroraBorealis(vec3 viewPos, float VdotU, float dither) {";
			if (!source.contains(header)) {
				return source;
			}
			return source.replace(header, "vec3 GetAuroraBorealisFlat(vec3 viewPos, float VdotU, float dither) {") + CUBE_AURORA;
		}
		if (name.endsWith("/lib/atmospherics/sky.glsl")) {
			// Above the air the sky is black. The fog toward the sky and the sky in reflections take this color too.
			source = source.replace("        return finalSky;\n", "        finalSky *= 1.0 - cubismSpace();\n        return finalSky;\n");
		}
		if (name.endsWith("/lib/atmospherics/nightNebula.glsl")) {
			source = source.replace("float nebulaFactor = pow2(max0(VdotU) * min1(nightFactor * 2.0))",
				"float nebulaFactor = mix(pow2(max0(VdotU) * min1(nightFactor * 2.0)), cubismSpace() > 0.0 ? cubismPlanetMiss(viewPos) : 1.0, cubismSpace())");
		}
		if (name.endsWith(STARS)) {
			// In orbit the stars show by day and below the horizon of the ground under the camera. The planet hides them.
			source = source.replace("    if (VdotU < 0.0) return vec3(0.0);\n", "    if (VdotU < 0.0 && cubismSpace() == 0.0) return vec3(0.0);\n")
				.replace("    star *= min1(VdotU * 3.0) * max0(", "    star *= mix(min1(max0(VdotU) * 3.0), 1.0, cubismSpace()) * max0(")
				.replace("    star *= invRainFactor * pow2(pow2(invNoonFactor2)) * (1.0 - 0.5 * sunVisibility);\n",
					"    star *= mix(invRainFactor * pow2(pow2(invNoonFactor2)) * (1.0 - 0.5 * sunVisibility), 1.0, cubismSpace());\n");
			// The stars keep their place on the sky, which turns with the sun. Its pole is the axis of the sun, and the
			// half of the sky past the equator shows the mirror of the other half, as the projection needs y above 0.
			return source.replace(
				"    vec3 wpos = normalize((gbufferModelViewInverse * vec4(viewPos * 1000.0, 1.0)).xyz);\n",
				"""
				    vec3 wpos = normalize((gbufferModelViewInverse * vec4(viewPos * 1000.0, 1.0)).xyz);
				    if (cubism_active != 0) {
				        wpos = normalize(cubism_celestialFrame * viewPos);
				        wpos.y = abs(wpos.y);
				    }
				""");
		}
		if (name.endsWith("/lib/voxelization/lightVoxelization.glsl")) {
			// The light volume of colored lighting, and the block volume of the reflections, are laid out in the frame of the
			// camera face. A folded face draws, and writes its blocks in the shadow pass, in its own frame, so its positions
			// are turned into the one of the camera face. The volumes stay one grid, since the turn takes blocks to blocks.
			return source.replace("return scenePos + cameraPositionBestFract", VOXEL_FALLBACK + "return cubismTurn(scenePos) + cameraPositionBestFract");
		}
		if (name.endsWith("/program/shadowcomp.glsl")) {
			// The flood fill of colored lighting spreads the light of the last frame. At a crossing that volume is laid out in the
			// frame of the old face, so its cells are found with the turn back, about the center of each cell. Without it the
			// light builds up from nothing over the next frames.
			return source.replace("\tivec3 previousPos = pos - ivec3(posOffset);\n", """
					\tivec3 previousPos = pos - ivec3(posOffset);
					#ifdef CUBEWORLD
					\tpreviousPos = ivec3(floor(cubism_crossTurn * (vec3(previousPos - voxelVolumeSize / 2) + 0.5))) + voxelVolumeSize / 2;
					#endif
					""");
		}
		if (name.endsWith("/lib/voxelization/reflectionVoxelization.glsl")) {
			return source.replace("return playerPos + cameraPositionBestFract", VOXEL_FALLBACK + "return cubismTurn(playerPos) + cameraPositionBestFract")
				.replace("return previousPlayerPos + previousCameraPositionBestFract", VOXEL_FALLBACK + "return cubismTurn(previousPlayerPos) + previousCameraPositionBestFract");
		}
		if (name.endsWith("/lib/voxelization/puddleVoxelization.glsl")) {
			// The puddle map is a map of columns, and a folded face stands on its side in it. Its puddles skip the map.
			return source.replace("return scenePos + fract(cameraPosition)", VOXEL_FALLBACK + "if (cubismFolded()) return vec3(-1.0);\n\treturn scenePos + fract(cameraPosition)");
		}
		if (name.endsWith("/program/shadow.glsl")) {
			// The faces of the blocks for the reflections are stored by their normal in the frame of the camera face. The
			// normal matrix of Iris lacks the fold of a folded face, which the matrix of the draw has.
			source = source.replace("vec3 normal = mat3(shadowModelViewInverse) * gl_NormalMatrix * gl_Normal;\n                UpdateSceneVoxelMap",
				VOXEL_FALLBACK + "vec3 normal = cubismTurn(mat3(shadowModelViewInverse) * mat3(gl_ModelViewMatrix) * gl_Normal);\n                UpdateSceneVoxelMap");
		}
		if (name.endsWith("/lib/colors/lightAndAmbientColors.glsl")) {
			// The light of the sky on the ground. Seen from a face at night, the ground of a face in day would get the dark
			// sky of the camera, and all of it out of the sun would be black.
			source = source.replace("vec3 noonClearAmbientColor = pow(skyColor, vec3(0.75)) * 0.85;",
				"\n#ifndef cubismSkyColor\n#define cubismSkyColor() skyColor\n#endif\n        vec3 noonClearAmbientColor = pow(cubismSkyColor(), vec3(0.75)) * 0.85;");
		}
		if (name.endsWith(MAIN_LIGHTING)) {
			source = source.replace("voxelPos = voxelPos + worldGeoNormal * 0.55;", VOXEL_FALLBACK + "voxelPos = voxelPos + cubismTurn(worldGeoNormal) * 0.55;");
			// There is one shadow map, cast from the sun or the moon after the time at the camera. A point lit by the
			// other one would read the shadows of the wrong side, so it takes the light of the pack past the shadow
			// distance. At the line where the light turns over, the shadows are faded out in any case.
			source = source.replace("    float NdotL = dot(normalM, lightVec);\n", """
				#ifdef CUBEWORLD
				    vec3 cubismLightBent = cubismBendLight(lightVec, viewPos);
				#else
				    vec3 cubismLightBent = lightVec;
				#endif
				    float NdotL = dot(normalM, cubismLightBent);
				""").replace("float geoNdotL = dot(geoNormal, lightVec);", "float geoNdotL = dot(geoNormal, cubismLightBent);");
			return source.replace(
				"float shadowLength = shadowDistance * 0.9166667 - lViewPos;",
				"""
				float shadowLength = shadowDistance * 0.9166667 - lViewPos;
				                    #ifdef CUBEWORLD
				                        if (cubism_active != 0 && dot(lightVec, cubism_shadowLight) < 0.0) shadowLength = 0.0;
				                    #endif""");
		}
		if (name.endsWith("/program/gbuffers_skybasic.glsl") || name.endsWith("/program/gbuffers_skytextured.glsl")) {
			// The sun and moon set at the horizon of the ground under the camera. In orbit they set behind the planet,
			// which draws over them.
			source = source.replace("GetHorizonFactor(SdotU)", "mix(GetHorizonFactor(SdotU), 1.0, cubismSpace())")
				.replace("GetHorizonFactor(-SdotU)", "mix(GetHorizonFactor(-SdotU), 1.0, cubismSpace())")
				.replace("GetHorizonFactor(VdotU)", "mix(GetHorizonFactor(VdotU), 1.0, cubismSpace())");
		}
		if (name.endsWith("/lib/atmospherics/volumetricLight/volumetricLight.glsl")) {
			// The light shafts are traced from the camera through the air around it, and past the shadow map they count all
			// of it as lit. In orbit there is no air around the camera, and the planet counts as sky to them.
			source = source.replace("    if (vlMult < 0.0001) return vec4(0.0);\n", "    vlMult *= 1.0 - cubismSpace();\n    if (vlMult < 0.0001) return vec4(0.0);\n");
		}
		if (name.endsWith(MAIN_FOG)) {
			// From orbit the planet is seen through little air, and the far ground ends at the edge of the planet, not in
			// the sky. Without this the haze and the fog at the end of the view cover the whole planet.
			source = source.replace("        fog *= altitudeFactor;\n", "        fog *= altitudeFactor * (1.0 - cubismSpace());\n")
				.replace("        #ifdef IRIS_FEATURE_FADE_VARIABLE\n", "        fog *= 1.0 - cubismSpace();\n\n        #ifdef IRIS_FEATURE_FADE_VARIABLE\n");
		}
		if (name.endsWith(MAIN_FOG) || name.endsWith("/program/gbuffers_terrain.glsl") || name.endsWith("/program/gbuffers_water.glsl")) {
			source = source.replace("max(length(playerPos.xz), abs(playerPos.y))", "cubismBorderDist(playerPos)");
		}
		if (name.endsWith(MAIN_FOG) || name.endsWith("/program/deferred1.glsl")) {
			// The fog thickens toward the ground. Its height must follow the cube, or the ground of the camera face
			// cuts the sky along a plane that turns at each crossing.
			source = source.replace("GetAtmFogAltitudeFactor(playerPos.y + cameraPosition.y)", "GetAtmFogAltitudeFactor(cubismFeetAlt(playerPos))")
				.replace("GetAtmFogAltitudeFactor(cameraPosition.y + ", "GetAtmFogAltitudeFactor(cubismCameraAlt() + ");
		}
		if (name.endsWith("/program/deferred1.glsl")) {
			// From orbit the aurora also shows over the ground of the planet, in front of the ground that the ray meets.
			source = source.replace("DoFog(color, skyFade, lViewPos, playerPos, VdotU, VdotS, dither, false, 0.0);\n",
				"""
				DoFog(color, skyFade, lViewPos, playerPos, VdotU, VdotS, dither, false, 0.0);
				        #if AURORA_STYLE > 0 && defined OVERWORLD && defined CUBISM_CUBE_AURORA
				            if (cubismSpace() > 0.0) color.rgb += GetAuroraBorealisCube(viewPos.xyz, VdotU, dither, lViewPos) * cubismSpace();
				        #endif
				""");
		}
		if (name.endsWith("/lib/lighting/cloudShadows.glsl")) {
			source = source.replace("    float GetCloudShadow(vec3 playerPos) {\n", CLOUD_SHADOW_LAYER + "    float GetCloudShadow(vec3 playerPos) {\n")
				.replace("        vec3 worldPos = playerPos + cameraPosition;\n", "        vec3 worldPos = playerPos + cameraPosition;\n" + CLOUD_SHADOW_CUBE);
		}
		if (name.endsWith(MAIN_CLOUDS)) {
			return patchMainClouds(source);
		}
		if (name.endsWith(UNBOUND_CLOUDS)) {
			return patchUnboundClouds(source);
		}
		if (name.endsWith("/lib/atmospherics/clouds/reimaginedClouds.glsl")) {
			return patchReimaginedClouds(source);
		}
		if (name.endsWith("/lib/materials/materialMethods/reflectionBackground.glsl")) {
			// The clouds in a reflection are traced from the feet of the camera face. A folded face draws its water with
			// its own matrices, so the directions and the position must be turned into the frame of the camera face first.
			source = source
				.replace("normalize(mat3(gbufferModelViewInverse) * normalMR)", "normalize(cubism_normalOut(normalMR))")
				.replace("dot(playerPos, worldNormalMR)", "dot(cubism_normalOut(mat3(gbufferModelView) * playerPos), worldNormalMR)")
				.replace("normalize(mat3(gbufferModelViewInverse) * nViewPosR)", "normalize(cubism_normalOut(nViewPosR))");
		}
		if (name.endsWith("/lib/materials/materialMethods/refraction.glsl")) {
			// The refraction is drawn once for the whole view, in the frame of the camera face. There the water of a folded
			// face stands on edge, so its noise stretched into streaks and changed at a crossing. The noise lies on the face
			// that the point of the cube is on, which does not depend on the face of the camera.
			source = source.replace("    vec3 worldPos = playerPos.xyz + cameraPosition.xyz;\n",
				"""
				    vec3 worldPos = playerPos.xyz + cameraPosition.xyz;
				    #ifdef CUBEWORLD
				        if (cubism_active != 0) {
				            vec3 cubePos = (cubism_cubeFromView * vec4(viewPos.xyz, 1.0)).xyz;
				            vec3 a = abs(cubePos);
				            worldPos = a.y >= a.x && a.y >= a.z ? cubePos.xyz : a.x >= a.z ? cubePos.zxy : cubePos.xzy;
				        }
				    #endif
				""");
		}
		if (name.endsWith("/lib/materials/materialMethods/reflections.glsl")) {
			// Water is flat, so its sky reflection is measured from the up of its own face. The rounded up of the pack is
			// tilted near an edge, and a folded face has to use the matrix it draws with.
			source = source.replace("    float RVdotU = dot(nViewPosR, upVec);",
				"""
				    #if defined GBUFFERS_WATER && defined CUBEWORLD
				        float RVdotU = dot(nViewPosR, cubism_active != 0 ? normalize(gbufferModelView[1].xyz) : upVec);
				    #else
				        float RVdotU = dot(nViewPosR, upVec);
				    #endif""");
		}
		if (name.endsWith("/program/gbuffers_water.glsl") || name.endsWith("/program/gbuffers_terrain.glsl")) {
			// The normal matrix of the pack comes from the global model view matrix of Iris, which is the one of the camera
			// face. The model view matrix of the draw carries the fold of a face next to it, so the normals follow it.
			source = source.replace("gl_NormalMatrix", "mat3(gl_ModelViewMatrix)");
		}
		if (name.endsWith("/program/gbuffers_water.glsl")) {
			// The up of the face in view space, from the matrix of the draw. The foam on the edge of the water needs the
			// flat up of the face it is on, not the rounded up of the pack that tilts near an edge.
			source = source.replace("\nin vec3 playerPos;\n", "\nin vec3 playerPos;\nflat in vec3 cwFaceUp;\n")
				.replace("\nout vec3 playerPos;\n", "\nout vec3 playerPos;\nflat out vec3 cwFaceUp;\n")
				.replace("    normal = normalize(mat3(gl_ModelViewMatrix) * gl_Normal);\n",
					"    normal = normalize(mat3(gl_ModelViewMatrix) * gl_Normal);\n    cwFaceUp = normalize(mat3(gl_ModelViewMatrix) * vec3(0.0, 1.0, 0.0));\n");
		}
		if (name.endsWith("/lib/materials/specificMaterials/translucents/water.glsl")) {
			// The height of the world of a folded face is not the one of the camera face, so its fraction says nothing.
			source = source.replace("foam *= clamp((fract(worldPos.y) - 0.7) * 10.0, 0.0, 1.0);", "if (cubism_active == 0) foam *= clamp((fract(worldPos.y) - 0.7) * 10.0, 0.0, 1.0);");
			source = source.replace("                if (NdotU > 0.99) {", "                if (dot(normal, cwFaceUp) > 0.99) {")
				.replace("""
                    vec3 matrixM = vec3(
                        gbufferModelViewInverse[0].y,
                        gbufferModelViewInverse[1].y,
                        gbufferModelViewInverse[2].y
                    );
                    float playerPosTY = dot(matrixM, viewPosT) + gbufferModelViewInverse[3].y;
                    float yPosDif = playerPosTY - playerPos.y;
""", "                    float yPosDif = dot(viewPosT - viewPos, cwFaceUp);\n");
		}
		if (name.contains("/program/")) {
			source = source.replace("mat3(gbufferModelViewInverse) * normalM", "cubism_normalOut(normalM)");
		}
		if (name.endsWith("/program/voxy.json")) {
			// The uniforms of the cube that the helpers read in the program of Voxy, which declares only the listed ones.
			source = source.replace("        \"shadowProjectionInverse\",\n", """
				        "shadowProjectionInverse",
				        "cubism_active", "cubism_sunPosition", "cubism_upPosition", "cubism_cubeFromFeet", "cubism_cubeFromView",
				        "cubism_core", "cubism_centerY", "cubism_shadowLight", "cubism_daySkyColor", "cubism_orbit",
				        "cubism_voxyTurn0", "cubism_voxyTurn1", "cubism_voxyTurn2", "cubism_voxyTurn3", "cubism_voxyTurn4", "cubism_voxyTurn5",
				""");
		}
		if (name.endsWith("/program/voxy_opaque.glsl") || name.endsWith("/program/voxy_translucent.glsl")) {
			source = source.replace("mat4 gbufferPreviousProjection = vxProjPrev;\n", "mat4 gbufferPreviousProjection = vxProjPrev;\n" + VOXY_FACE_VALUES)
				.replace("void voxy_emitFragment(VoxyFragmentParameters parameters) {\n",
					VOXY_FACE_SETUP + "void voxy_emitFragment(VoxyFragmentParameters parameters) {\n    cubismVoxyFace();\n");
		}
		for (String program : POINT_UP_PROGRAMS) {
			if (name.endsWith("/program/" + program + ".glsl")) {
				return "#define CUBEWORLD_POINT_UP\n" + source.replace(UP, "upVec = cubismPointUp();");
			}
		}
		for (String program : CAMERA_UP_PROGRAMS) {
			if (name.endsWith("/program/" + program + ".glsl")) {
				return "#define CUBEWORLD_CAMERA_UP\n" + source.replace(UP, "upVec = CUBEWORLD_UP_VEC;");
			}
		}
		return source;
	}

	private static String patchMainClouds(String source) {
		source = source.replace(
			"vec3 wpos = PlayerToShadow(tracePos - cameraPos);",
			"""
			#if defined CLOUDS_UNBOUND || defined CLOUDS_REIMAGINED
			        vec3 wpos = PlayerToShadow(cubismToFeet(tracePos - cameraPos));
			#else
			        vec3 wpos = PlayerToShadow(tracePos - cameraPos);
			#endif""");
		// The two layers of the reimagined clouds: the one nearer to the camera is traced first.
		source = source.replace("if (abs(cameraPos.y - minCloudAlt) < abs(cameraPos.y - maxCloudAlt)) {",
			"if (abs(cubismAlt(cameraPos) - minCloudAlt) < abs(cubismAlt(cameraPos) - maxCloudAlt)) {");
		return source.replace(
			"    cameraPos += cameraPosOffset;\n",
			"""
			    cameraPos += cameraPosOffset;
			#if defined CLOUDS_UNBOUND || defined CLOUDS_REIMAGINED
			    if (cubism_active != 0) cameraPos = (cubism_cubeFromFeet * vec4(cameraPosOffset, 1.0)).xyz;
			#endif
			""");
	}

	/**
	 * The shadow of a layer of the reimagined clouds: where the ray from a point to the light crosses the height of the
	 * layer, the map of the clouds there, on the plane of the face under it as the clouds of the cube read it.
	 */
	private static final String CLOUD_SHADOW_LAYER = """
		    #if defined CUBEWORLD && defined CLOUDS_REIMAGINED
		        float cubismCloudShadowLayer(vec3 p, float pAlt, vec3 l, float s, int cloudAltitude) {
		            float dist = float(cloudAltitude) - pAlt;
		            vec3 q = p + l * dist / max(s, 0.1);
		            vec3 a = abs(q);
		            vec3 planar = a.y >= a.x && a.y >= a.z ? q : a.x >= a.z ? q.zxy : q.xzy;
		            vec2 coord = GetRoundedCloudCoord(ModifyTracePos(vec3(planar.x, float(cloudAltitude), planar.z), cloudAltitude).xz, 0.35);
		            #ifndef COMPOSITE
		                float value = texture2D(gaux4, coord).b;
		            #else
		                float value = texture2D(cloudWaterTex, coord).b;
		            #endif
		            return value * clamp(dist * 0.1, 0.0, 1.0);
		        }
		    #endif
		""";

	/**
	 * The shadows of the clouds of the pack lie on the flat world, with the height and the light of the camera face. On
	 * the cube they take the height over the rounded cube, the light at the point, and the plane of the face under it.
	 */
	private static final String CLOUD_SHADOW_CUBE = """
		        #ifdef CUBEWORLD
		        if (cubism_active != 0) {
		            vec3 cubismP = (cubism_cubeFromView * vec4(mat3(gbufferModelView) * playerPos, 1.0)).xyz;
		            float cubismPAlt = cubismAlt(cubismP);
		            #ifdef CLOUDS_REIMAGINED
		                vec3 cubismL = normalize(mat3(cubism_cubeFromView) * lightVec);
		                float cubismS = clamp(dot(cubismL, cubismNormal(cubismP)), 0.0, 1.0);
		                float cubismShade = cubismCloudShadowLayer(cubismP, cubismPAlt, cubismL, cubismS, cloudAlt1i);
		                #ifdef DOUBLE_REIM_CLOUDS
		                    float cubismShade2 = cubismCloudShadowLayer(cubismP, cubismPAlt, cubismL, cubismS, cloudAlt2i);
		                    cubismShade = 1.0 - (1.0 - cubismShade) * (1.0 - cubismShade2);
		                #endif
		                // The pack fades the shadows as the light goes down to the horizon.
		                cubismShade *= sqrt3(1.0 - sqrt(1.0 - cubismS * cubismS));
		                return 1.0 - 0.85 * cubismShade;
		            #else
		                vec3 cubismA = abs(cubismP);
		                vec3 cubismFlat = cubismA.y >= cubismA.x && cubismA.y >= cubismA.z ? cubismP : cubismA.x >= cubismA.z ? cubismP.zxy : cubismP.xzy;
		                worldPos = vec3(cubismFlat.x, cubismPAlt, cubismFlat.z);
		            #endif
		        }
		        #endif
		""";

	/**
	 * The clouds that read a flat map take it on the plane of the face that a point of the cube is over: the axis of the
	 * point that is largest in size becomes y, the up of that face. The map then lies flat over each face, and changes
	 * plane at the diagonal over an edge.
	 */
	private static final String CLOUD_PLANE = """

		#ifndef CUBEWORLD_CLOUD_PLANE
		#define CUBEWORLD_CLOUD_PLANE
		vec3 cubismPlanar(vec3 v, vec3 p) {
		    if (cubism_active == 0) return v;
		    vec3 a = abs(p);
		    return a.y >= a.x && a.y >= a.z ? v : a.x >= a.z ? v.zxy : v.xzy;
		}

		// The distance along the ray from t on to where it comes into the band of the clouds, or -1. It steps by the gap to
		// the band. A ray that skims the shell closes the gap slowly, and a floor on the step keeps it from running out of
		// steps. The floor is held to what crosses less than the depth of the band, so it never steps over the band.
		// Below the floor height the ray is in the planet, and the clouds past it are hidden.
		float cubismBandEntry(vec3 origin, vec3 dir, float t, float cloudAlt, float floorAlt, float maxT, int steps) {
		    float tOut = t;
		    bool hit = false;
		    for (int j = 0; j < steps; j++) {
		        vec3 p = origin + t * dir;
		        float alt = cubismAlt(p);
		        float gap = abs(alt - cloudAlt) - cloudStretch;
		        if (gap <= 0.0) { hit = true; break; }
		        if (alt < floorAlt) return -1.0;
		        tOut = t;
		        t += max(gap, min(max(2.0, t * 0.01), 1.8 * cloudStretch / max(abs(dot(dir, cubismNormal(p))), 0.01)));
		        if (t > maxT) return -1.0;
		    }
		    if (!hit) return -1.0;
		    // The step lands up to a step deep in the band, by a depth that changes with the distance. The clouds start
		    // their march there, which drew dark rings about the camera. Find the edge of the band.
		    for (int j = 0; j < 8; j++) {
		        float mid = 0.5 * (tOut + t);
		        if (abs(cubismAlt(origin + mid * dir) - cloudAlt) <= cloudStretch) t = mid;
		        else tOut = mid;
		    }
		    return t;
		}

		// The colors of the clouds follow the time at the camera. A cloud over a part of the cube with another time, and
		// every cloud seen from orbit, takes the light of the sun at its own place, as the planet mesh does.
		vec3 cubismCloudColor(vec3 colorSample, vec3 tracePos, float cloudShading) {
		    if (cubism_active == 0) return colorSample;
		    vec3 sunCube = normalize(mat3(cubism_cubeFromView) * cubism_sunPosition);
		    vec3 upCube = normalize(mat3(cubism_cubeFromView) * cubism_upPosition);
		    float sunHere = dot(cubismNormal(tracePos), sunCube);
		    float local = max(cubismSpace(), smoothstep(0.05, 0.3, abs(sunHere - dot(upCube, sunCube))));
		    if (local <= 0.0) return colorSample;
		    vec3 lit = (0.35 + 0.65 * cloudShading) * mix(vec3(0.03, 0.035, 0.05), vec3(1.1, 1.05, 1.0), smoothstep(-0.15, 0.35, sunHere));
		    return mix(colorSample, lit, local);
		}
		#endif
		""";

	private static String patchReimaginedClouds(String source) {
		source = source.replace("const float cloudRoundness = 0.125;\n", "const float cloudRoundness = 0.125;\n" + CLOUD_PLANE)
			.replace("tracePosM = ModifyTracePos(tracePos, cloudAltitude);",
				"tracePosM = ModifyTracePos(cubism_active != 0 ? vec3(cubismPlanar(tracePos, tracePos).x, cubismAlt(tracePos), cubismPlanar(tracePos, tracePos).z) : tracePos, cloudAltitude);")
			.replace("vec3 cLightPosAdd = normalize(ViewToPlayer(lightVec * 1000000000.0)) * vec3(0.08);",
				"vec3 cLightPosAdd = normalize(ViewToPlayer(lightVec * 1000000000.0)) * vec3(0.08);\n"
					+ "                if (cubism_active != 0) cLightPosAdd = cubismPlanar(mat3(cubism_cubeFromFeet) * cLightPosAdd, tracePos);")
			.replace("float aboveClouds = clamp01((cameraPos.y - lowerPlaneAltitude) * 0.05);",
				"float aboveClouds = clamp01((cubismAlt(cameraPos) - lowerPlaneAltitude) * 0.05);");
		// The band of these clouds is thin, and they take one step per block of it. On the cube the march is cut to the
		// length that a ray at its angle to the shell stays in the band, so a ray along the shell does not take thousands.
		source = patchCloudShell(source,
			"min(max(distanceThreshold - cubismT, 0.0), cloudTallness / max(abs(dot(nPlayerPos, cubismNormal(cameraPos + cubismT * nPlayerPos))), 0.02))");
		// The march has a fixed number of steps over that length, and the shell curves away under it. A step out of the band
		// read the map anyway, and above the band it lit the cloud by more than full light, the bright spots along the rim of
		// the planet. A ray that leaves the band looks for the next entry, past an edge or on the far side of the planet,
		// and marches the band again from there.
		source = source.replace("    for (int i = 0; i < sampleCount; i++) {\n        tracePos += traceAdd;\n\n        vec3 cloudPlayerPos = tracePos - cameraPos;\n", """
			    int cubismCount = cubism_active != 0 ? sampleCount * 3 : sampleCount;
			    for (int i = 0; i < cubismCount; i++) {
			        tracePos += traceAdd;

			        if (cubism_active != 0 && abs(cubismAlt(tracePos) - cloudAltitude) > cloudStretch) {
			            float cubismT2 = cubismBandEntry(cameraPos, nPlayerPos, length(tracePos - cameraPos), float(cloudAltitude), cubismFloor, cubismStop, 64);
			            if (cubismT2 < 0.0) break;
			            float cubismLength = min(max(distanceThreshold - cubismT2, 0.0), cloudTallness / max(abs(dot(nPlayerPos, cubismNormal(cameraPos + cubismT2 * nPlayerPos))), 0.02));
			            traceAdd = nPlayerPos * (cubismLength / float(sampleCount));
			            tracePos = cameraPos + cubismT2 * nPlayerPos + traceAdd * dither;
			        }

			        vec3 cloudPlayerPos = tracePos - cameraPos;
			""");
		source = source.replace(
			"            vec3 colorSample = cloudAmbientColor * 0.95 * (1.0 - 0.35 * cloudShading) + cloudLightColor * 0.9 * (0.1 + cloudShading);\n",
			"            vec3 colorSample = cubismCloudColor(cloudAmbientColor * 0.95 * (1.0 - 0.35 * cloudShading) + cloudLightColor * 0.9 * (0.1 + cloudShading), tracePos, cloudShading);\n")
			.replace("            colorSample = mix(cloudSkyColor, colorSample, cloudFogFactor * 0.707);\n",
				"            colorSample = mix(cloudSkyColor, colorSample, mix(cloudFogFactor * 0.707, 1.0, cubismSpace()));\n");
		return source;
	}

	/** The march of the volumetric clouds over the rounded shell of the cube. The length is how far it marches from the entry. */
	private static String patchCloudShell(String source, String length) {
		source = source.replace(
			"float threshold = clamp(abs(cloudAltitude - tracePos.y) / cloudStretch, 0.001, 0.999);",
			"float threshold = clamp(abs(cloudAltitude - cubismAlt(tracePos)) / cloudStretch, 0.001, 0.999);");
		// Over the cube the band of the clouds is a rounded shell. Step along the ray to it by the gap to the band,
		// which never steps over it.
		source = replaceBetween(source,
			"    float lowerPlaneDistance  = (lowerPlaneAltitude - cameraPos.y) / nPlayerPos.y;",
			"    float planeDistanceDif = maxPlaneDistance - minPlaneDistance;\n",
			original -> """
				    float minPlaneDistance;
				    float planeDistanceDif;
				    // Under the sea the ray is in the planet. A band set lower than the sea, or a camera under it, moves that down.
				    float cubismFloor = min(min(oceanAltitude, float(cloudAltitude) - cloudStretch), cubismAlt(cameraPos));
				    // The planet mesh, which draws from orbit where the terrain does not, writes no depth. The march stops at it.
				    float cubismStop = distanceThreshold;
				    if (cubism_active != 0) {
				        nPlayerPos = normalize(mat3(cubism_cubeFromFeet) * nPlayerPos);
				        #ifdef CUBISM_PLANET
				            if (cubismSpace() > 0.0) cubismStop = cubismPlanetHit(cameraPos, nPlayerPos, distanceThreshold);
				        #endif
				        float cubismT = cubismBandEntry(cameraPos, nPlayerPos, 0.0, float(cloudAltitude), cubismFloor, cubismStop, 160);
				        if (cubismT < 0.0) return vec4(0.0);
				        minPlaneDistance = cubismT;
				        planeDistanceDif = LENGTH;
				    } else {
				""".replace("LENGTH", length) + original.replace("    float minPlaneDistance = ", "    minPlaneDistance = ")
					.replace("    float planeDistanceDif = ", "    planeDistanceDif = ") + "    }\n");
		source = source.replace(
			"    tracePos.y -= traceAdd.y;\n",
			"    if (cubism_active != 0) tracePos -= traceAdd; else tracePos.y -= traceAdd.y;\n");
		// The haze and the end of the march read the distance on the ground. A ray that bends over an edge covers little of it
		// while it travels far, so on the cube they read the true distance.
		source = source.replace("float lTracePosXZ = length(cloudPlayerPos.xz);", "float lTracePosXZ = cubism_active != 0 ? lTracePos : length(cloudPlayerPos.xz);");
		source = source.replace("if (lTracePosXZ > distanceThreshold) break;", "if (lTracePosXZ > distanceThreshold || (cubism_active != 0 && lTracePos > cubismStop)) break;");
		return source.replace(
			"float cloudShading = 1.0 - (higherPlaneAltitude - tracePos.y) / cloudTallness;",
			"float cloudShading = 1.0 - (higherPlaneAltitude - cubismAlt(tracePos)) / cloudTallness;");
	}

	private static String patchUnboundClouds(String source) {
		// The march stops where the ray leaves the band. A ray can follow the shell around an edge onto the next face, so no
		// estimate of the length from the slope at the entry holds.
		source = patchCloudShell(source, "max(distanceThreshold - cubismT, 0.0)");
		// The low cloud quality reads a flat map of noise.
		source = source.replace("noise += texture2DLod(noisetex, tracePosM.xz - vec2(0.0, wind), 0.0).b * currentPersist;",
			"noise += texture2DLod(noisetex, cubismPlanar(tracePosM, tracePos).xz - vec2(0.0, wind), 0.0).b * currentPersist;")
			.replace("const float cloudTallness = cloudStretch * 2.0;\n", "const float cloudTallness = cloudStretch * 2.0;\n" + CLOUD_PLANE);
		// The closed area check reads the shadow map at points far from the camera. On the cube the body of the planet shades
		// the far side from the sun, so the check would remove the clouds there.
		source = source.replace("if (shadowLength < lTracePos)", "if (cubism_active == 0 && shadowLength < lTracePos)");
		source = source.replace(
			"float aboveClouds = clamp01((cameraPos.y - lowerPlaneAltitude) * 0.01);",
			"float aboveClouds = clamp01((cubismAlt(cameraPos) - lowerPlaneAltitude) * 0.01);");
		source = source.replace(
			"if (abs(tracePos.y - cloudAltitude) > cloudStretch) break;",
			"if (abs(cubismAlt(tracePos) - cloudAltitude) > cloudStretch) {\n    if (cubism_active == 0) break;\n    // Over the edge of the cube the ray can come back into the shell farther on, so look for the next entry.\n    float cubismT2 = cubismBandEntry(cameraPos, nPlayerPos, length(tracePos - cameraPos), float(cloudAltitude), cubismFloor, cubismStop, 64);\n    if (cubismT2 < 0.0) break;\n    // The offset by the dither of each pixel keeps the slices of neighbouring pixels apart, as at the first entry.\n    tracePos = cameraPos + (cubismT2 + stepMult * (dither - 1.0)) * nPlayerPos;\n}\n");
		// The light at the place of each cloud, and in orbit no fade toward the sky, which is black there.
		source = source.replace(
			"            vec3 colorSample = cloudAmbientColor * (0.4 + 0.6 * cloudShading) + cloudLightColor * cloudShading;\n",
			"            vec3 colorSample = cubismCloudColor(cloudAmbientColor * (0.4 + 0.6 * cloudShading) + cloudLightColor * cloudShading, tracePos, cloudShading);\n")
			.replace("            colorSample = mix(cloudSkyColor, colorSample, cloudFogFactor * 0.69);\n",
				"            colorSample = mix(cloudSkyColor, colorSample, mix(cloudFogFactor * 0.69, 1.0, cubismSpace()));\n");
		source = source.replace(
			"GetCloudNoise(tracePos, cloudAltitude, lTracePosXZ, cloudPlayerPos.y)",
			"GetCloudNoise(tracePos, cloudAltitude, lTracePosXZ, cubismAlt(tracePos) - cubismAlt(cameraPos))");
		return source;
	}

	private static String replaceBetween(String source, String start, String end, java.util.function.UnaryOperator<String> edit) {
		int from = source.indexOf(start);
		int to = from < 0 ? -1 : source.indexOf(end, from);
		if (to < 0) {
			return source;
		}
		to += end.length();
		return source.substring(0, from) + edit.apply(source.substring(from, to)) + source.substring(to);
	}

	public static void addUniforms(UniformHolder uniforms) {
		uniforms.uniform1i(UniformUpdateFrequency.PER_FRAME, "cubism_active", () -> CubeSky.active() && CubeSky.celestial() != null ? 1 : 0);
		uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "cubism_sunPosition", () -> {
			Matrix3f celestial = CubeSky.celestial();
			Vector3f sun = celestial == null ? new Vector3f(0.0F, 100.0F, 0.0F) : celestial.getColumn(1, new Vector3f()).mul(100.0F);
			return new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView()).transformDirection(sun);
		});
		uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "cubism_upPosition",
			() -> new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView()).transformDirection(new Vector3f(CubeSky.surfaceUp())));
		uniforms.uniformMatrix(UniformUpdateFrequency.PER_FRAME, "cubism_cubeFromFeet", CubeSky::cubeFromFeet);
		uniforms.uniformMatrix(UniformUpdateFrequency.PER_FRAME, "cubism_cubeFromView", () -> {
			// View to feet is the transpose of the rotation of the model view. Its translation is only view bobbing.
			Matrix4f feetFromView = new Matrix4f().set3x3(new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView())).transpose3x3();
			return new Matrix4f(CubeSky.cubeFromFeet()).mul(feetFromView);
		});
		uniforms.uniformMatrix3(UniformUpdateFrequency.PER_FRAME, "cubism_celestialFrame", () -> {
			// Rows: the side, the axis and the sun, so that y is the pole of the sky.
			Matrix3f celestial = CubeSky.celestial();
			Matrix3f rows = celestial == null ? new Matrix3f() : new Matrix3f(celestial).transpose();
			Vector3f axis = rows.getRow(2, new Vector3f());
			Vector3f sun = rows.getRow(1, new Vector3f());
			rows.setRow(1, axis).setRow(2, sun);
			Matrix3f viewToStorage = new Matrix3f().set(new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView())).transpose();
			return rows.mul(viewToStorage);
		});
		uniforms.uniformMatrix3(UniformUpdateFrequency.PER_FRAME, "cubism_viewToFeet",
			() -> new Matrix3f().set(new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView())).transpose());
		uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "cubism_shadowLight", () -> {
			// The light that the shadow map is cast from, as IrisShadowMatricesMixin picks it.
			Matrix3f celestial = CubeSky.celestial();
			if (celestial == null) {
				return new Vector3f(0.0F, 1.0F, 0.0F);
			}
			Vector3f light = celestial.getColumn(1, new Vector3f()).mul(CubeSky.shadowLightSign());
			return new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView()).transformDirection(light);
		});
		// IrisFaceUniforms writes the turn of a folded face while it draws, and puts the identity back.
		uniforms.uniformMatrix3(UniformUpdateFrequency.PER_FRAME, "cubism_faceTurn", Matrix3f::new);
		uniforms.uniformMatrix3(UniformUpdateFrequency.PER_FRAME, "cubism_crossTurn", FrameCrossing::backTurn);
		uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "cubism_daySkyColor", () -> new Vector3f(CubeSky.daySky()));
		// The turn of each face into the frame of the camera face, for the program of Voxy, which takes no arrays and no 3 by 3 matrices.
		for (int face = 0; face < SeamLayout.FACES.length; face++) {
			int f = face;
			uniforms.uniformMatrix(UniformUpdateFrequency.PER_FRAME, "cubism_voxyTurn" + f, () -> {
				SeamLayout layout = SeamClient.layout;
				if (layout == null) {
					return new Matrix4f();
				}
				// The face of the camera as this frame sees it. Sodium switches its camera face later in the frame.
				int[] m = layout.motion(f, SeamFaces.faceOfCamera(Minecraft.getInstance().gameRenderer.mainCamera()));
				return new Matrix4f(m[0], m[3], m[6], 0, m[1], m[4], m[7], 0, m[2], m[5], m[8], 0, m[9], m[10], m[11], 1);
			});
		}
		uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "cubism_core", CubeSky::core);
		uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "cubism_centerY", CubeSky::centerY);
		uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "cubism_orbit", CubeSky::orbit);
	}
}

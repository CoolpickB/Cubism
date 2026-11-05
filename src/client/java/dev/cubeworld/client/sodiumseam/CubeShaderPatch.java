package dev.cubeworld.client.sodiumseam;

import dev.cubeworld.client.render.CubeSky;
import java.nio.file.Path;
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
		"gbuffers_terrain", "gbuffers_water", "gbuffers_block", "gbuffers_entities", "gbuffers_textured", "gbuffers_basic"
	};

	private static final String SUN_DEFINE =
		"    #define GetSunVector() normalize((gbufferModelView * vec4(vec3(-sin(overworldAngle), cos(overworldAngle) * SUN_ROTATION_DATA) * 2000.0, 1.0)).xyz)";
	private static final String UP = "upVec = normalize(gbufferModelView[1].xyz);";

	/**
	 * The cube coordinates of the clouds are about the center of the cube. The altitude of a point is the height it would
	 * have on a face, over a rounded box: the core box, and around it the distance to the core. Over the middle of a face
	 * it is the plain y of the face storage, so the cloud height of the pack stays as it is.
	 */
	private static final String HELPERS = """
		#ifndef VOXY_PATCH
		    #define CUBEWORLD
		    uniform int cubeworld_active;
		    uniform vec3 cubeworld_sunPosition;
		    uniform vec3 cubeworld_upPosition;
		    uniform mat4 cubeworld_cubeFromFeet;
		    uniform mat4 cubeworld_cubeFromView;
		    uniform float cubeworld_core;
		    uniform float cubeworld_centerY;
		    uniform mat3 cubeworld_celestialFrame;
		    uniform mat3 cubeworld_viewToFeet;
		    uniform vec3 cubeworld_shadowLight;
		    // From the storage frame of the face that draws into the one of the camera face. It is the identity except while a
		    // folded face draws.
		    uniform mat3 cubeworld_faceTurn;
		    #define cubeworldTurn(v) (cubeworld_faceTurn * (v))
		    #define cubeworldFolded() (cubeworld_faceTurn != mat3(1.0))
		    // From the frame of the camera face back into the one of the face it left, on the frame of a crossing. Else the identity.
		    uniform mat3 cubeworld_crossTurn;
		    // The sky color of Iris follows the time at the camera. The light that the sky gives a point follows the time of the
		    // point, as vanilla dims the sky color of the full day: by the height of the sun.
		    uniform vec3 cubeworld_daySkyColor;
		    #define cubeworldSkyColor() (cubeworld_active != 0 ? cubeworld_daySkyColor * clamp(sin(cubeworldSunAngle() * 6.28318531) * 2.0 + 0.5, 0.0, 1.0) : skyColor)
		    // The normals that the programs of the ground store for the passes after them are read back with the view matrix of the
		    // camera face. A folded face draws with the matrix of its own face, so it stores them in the frame of the camera face.
		    #define cubeworld_normalOut(n) ((cubeworld_active != 0 ? cubeworld_viewToFeet : mat3(gbufferModelViewInverse)) * (n))
		    #define CUBEWORLD_UP_VEC (cubeworld_active != 0 ? normalize(cubeworld_upPosition) : normalize(gbufferModelView[1].xyz))
		    float cubeworldAlt(vec3 p) {
		        if (cubeworld_active == 0) return p.y;
		        return cubeworld_centerY + cubeworld_core + length(max(abs(p) - cubeworld_core, 0.0));
		    }
		    vec3 cubeworldNormal(vec3 p) {
		        vec3 e = sign(p) * max(abs(p) - cubeworld_core, 0.0);
		        return dot(e, e) > 0.0 ? normalize(e) : normalize(p);
		    }
		    vec3 cubeworldToFeet(vec3 d) {
		        return cubeworld_active != 0 ? transpose(mat3(cubeworld_cubeFromFeet)) * d : d;
		    }
		    // The height of a point relative to the camera. It goes through view space, which every face shares, so it
		    // holds in programs that draw a folded face with the matrices of that face.
		    float cubeworldFeetAlt(vec3 playerPos) {
		        if (cubeworld_active == 0) return playerPos.y + cameraPosition.y;
		        return cubeworldAlt((cubeworld_cubeFromView * vec4(mat3(gbufferModelView) * playerPos, 1.0)).xyz);
		    }
		    float cubeworldCameraAlt() {
		        return cubeworld_active != 0 ? cubeworldAlt(cubeworld_cubeFromFeet[3].xyz) : cameraPosition.y;
		    }
		    // The rounded up at a point of view space.
		    vec3 cubeworldUpAtView(vec3 viewPos) {
		        vec3 p = (cubeworld_cubeFromView * vec4(viewPos, 1.0)).xyz;
		        return normalize(transpose(mat3(cubeworld_cubeFromView)) * cubeworldNormal(p));
		    }
		    // The programs that draw the ground take the up, and so the time of day, of each point they draw. Two faces
		    // then meet at an edge with the same light, and the light turns smoothly around it.
		    #if defined CUBEWORLD_POINT_UP && defined FRAGMENT_SHADER
		        vec3 cubeworldPointUp() {
		            if (cubeworld_active == 0) return normalize(gbufferModelView[1].xyz);
		            vec4 clip = vec4(vec3(gl_FragCoord.xy / vec2(viewWidth, viewHeight), gl_FragCoord.z) * 2.0 - 1.0, 1.0);
		            vec4 view = gbufferProjectionInverse * clip;
		            return cubeworldUpAtView(view.xyz / view.w);
		        }
		    #elif defined CUBEWORLD_POINT_UP && defined VERTEX_SHADER
		        vec3 cubeworldPointUp() {
		            if (cubeworld_active == 0) return normalize(gbufferModelView[1].xyz);
		            return cubeworldUpAtView((gl_ModelViewMatrix * gl_Vertex).xyz);
		        }
		    #endif
		    // The time of day where the sky is seen from: the rounded up at the camera for the programs that draw the whole
		    // view, the up at each point for the programs that draw the ground, and the up of the face for the rest. It follows the height of the sun over that
		    // up, scaled so that the sun at its highest over the middle of a face is noon. The angle around the axis of the
		    // sun is not used for it: at the two corners on the axis the sun circles the horizon, and that angle has no
		    // stable value there. Morning or afternoon comes from the side the sun is on. Same in CubeShaderPatch.java.
		    float cubeworldSunAngle() {
		        if (cubeworld_active == 0) return sunAngle;
		        #if defined CUBEWORLD_CAMERA_UP
		            vec3 upView = cubeworld_upPosition;
		        #elif defined CUBEWORLD_POINT_UP && (defined FRAGMENT_SHADER || defined VERTEX_SHADER)
		            vec3 upView = cubeworldPointUp();
		        #else
		            vec3 upView = gbufferModelView[1].xyz;
		        #endif
		        vec3 n = normalize(mat3(cubeworld_cubeFromView) * upView);
		        vec3 s = normalize(mat3(cubeworld_cubeFromView) * cubeworld_sunPosition);
		        float local = acos(clamp(dot(s, n) / 0.81649658, -1.0, 1.0));
		        if (dot(s, cross(vec3(0.57735027), n)) < 0.0) local = -local;
		        float d = fract(local / 6.28318531);
		        float timeOfDay = (2.0 * d + 0.5 - cos(d * 3.14159265) * 0.5) / 3.0;
		        return fract(timeOfDay + 0.25);
		    }
		#else
		    // The programs of Voxy declare their own uniforms. They keep the flat world.
		    #define cubeworld_active 0
		    #define cubeworld_cubeFromFeet mat4(1.0)
		    #define cubeworld_normalOut(n) (mat3(gbufferModelViewInverse) * (n))
		    #define cubeworldAlt(p) ((p).y)
		    #define cubeworldNormal(p) (p)
		    #define cubeworldToFeet(d) (d)
		    #define cubeworldFeetAlt(p) ((p).y + cameraPosition.y)
		    #define cubeworldCameraAlt() (cameraPosition.y)
		    #define cubeworldSunAngle() (sunAngle)
		    #define cubeworldTurn(v) (v)
		    #define cubeworldFolded() false
		    #define cubeworldSkyColor() skyColor
		    #define cubeworld_celestialFrame mat3(gbufferModelViewInverse)
		#endif
		""";

	/**
	 * The aurora of the cube hangs over the rounded surface like the clouds, between two heights. A point of it shows only
	 * where it is night under it, and only over the part of the cube around the camera, since the biome and moon that allow
	 * it are those of the camera. The pattern of the pack lies on the three axis planes, blended by the normal so that it
	 * bends over an edge. Over the middle of a face only one plane counts.
	 */
	private static final String CUBE_AURORA = """

		#ifdef CUBEWORLD
		const float cubeworldAuroraLow = 320.0;
		const float cubeworldAuroraDepth = 240.0;

		float cubeworldAuroraNoise(vec2 planePos, float auroraAnimate) {
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

		vec3 GetAuroraBorealisCube(vec3 viewPos, float VdotU, float dither) {
		    // The flat aurora ends above the horizon. Over an edge the shell bends down, so it may show a bit below.
		    float visibility = sqrt1(clamp01(VdotU * 1.5 + 0.3)) - rainFactor - maxBlindnessDarkness;
		    visibility *= 1.0 - max0(VdotU) * 0.9;
		    #if AURORA_CONDITION == 1 || AURORA_CONDITION == 3
		        visibility -= moonPhase;
		    #endif
		    #if AURORA_CONDITION == 2 || AURORA_CONDITION == 3
		        visibility *= inSnowy;
		    #endif
		    #if AURORA_CONDITION == 4
		        visibility = max(visibility * inSnowy, visibility - moonPhase);
		    #endif
		    if (visibility <= 0.0) return vec3(0.0);

		    vec3 dir = normalize(mat3(cubeworld_cubeFromView) * viewPos);
		    vec3 cam = cubeworld_cubeFromFeet[3].xyz;
		    vec3 camUp = normalize(mat3(cubeworld_cubeFromView) * cubeworld_upPosition);
		    vec3 sunDir = normalize(mat3(cubeworld_cubeFromView) * cubeworld_sunPosition);

		    // Step along the ray to the lower height of the aurora, by the gap to it, which never steps over it.
		    float t = 0.0;
		    bool hit = false;
		    for (int j = 0; j < 64; j++) {
		        float gap = cubeworldAuroraLow - cubeworldAlt(cam + t * dir);
		        if (gap < 1.0) { hit = gap > -cubeworldAuroraDepth; break; }
		        t += max(gap, max(2.0, t * 0.01));
		        if (t > 4000.0) break;
		    }
		    if (!hit) return vec3(0.0);

		    #ifdef DEFERRED1
		        int sampleCount = 25;
		        int sampleCountP = sampleCount + 5;
		    #else
		        int sampleCount = 10;
		        int sampleCountP = sampleCount + 10;
		    #endif

		    float ditherM = dither + 5.0;
		    float auroraAnimate = frameTimeCounter * 0.001;
		    vec2 wind = vec2(syncedTime * 0.04, 0.0);
		    vec3 aurora = vec3(0.0);
		    for (int i = 0; i < sampleCount; i++) {
		        float current = pow2((i + ditherM) / sampleCountP);
		        float targetAlt = cubeworldAuroraLow + cubeworldAuroraDepth * current;
		        vec3 p = cam + t * dir;
		        for (int k = 0; k < 2; k++) {
		            t += (targetAlt - cubeworldAlt(p)) / max(dot(dir, cubeworldNormal(p)), 0.15);
		            p = cam + t * dir;
		        }
		        vec3 n = cubeworldNormal(p);
		        float night = 1.0 - clamp01((dot(sunDir, n) + 0.0625) / 0.125);
		        float near = smoothstep(0.0, 0.6, dot(n, camUp));
		        float weight = night * near;
		        if (weight <= 0.0) continue;

		        vec3 w = pow2(pow2(n));
		        w /= w.x + w.y + w.z;
		        vec3 q = p * 0.037;
		        float noise = 0.0;
		        if (w.x > 0.01) noise += w.x * cubeworldAuroraNoise(q.zy + wind, auroraAnimate);
		        if (w.y > 0.01) noise += w.y * cubeworldAuroraNoise(q.xz + wind, auroraAnimate);
		        if (w.z > 0.01) noise += w.z * cubeworldAuroraNoise(q.xy + wind, auroraAnimate);

		        float currentM = 1.0 - current;
		        aurora += weight * noise * currentM * mix(vec3(7.0, 2.2, 12.0), vec3(6.0, 16.0, 12.0), pow2(pow2(currentM)));
		    }

		    #if AURORA_STYLE == 1
		        aurora *= 1.3;
		    #else
		        aurora *= 1.8;
		    #endif
		    #ifdef ATM_COLOR_MULTS
		        aurora *= sqrtAtmColorMult;
		    #endif
		    return aurora * visibility / sampleCount;
		}
		#endif

		vec3 GetAuroraBorealis(vec3 viewPos, float VdotU, float dither) {
		    #ifdef CUBEWORLD
		        if (cubeworld_active != 0) return GetAuroraBorealisCube(viewPos, VdotU, dither);
		    #endif
		    return GetAuroraBorealisFlat(viewPos, VdotU, dither);
		}
		""";

	/**
	 * Put on its own lines before an edit to the voxel volumes, so that it still compiles in a pack that did not take the
	 * helpers. It goes at the edit and not at the top of the file, which comes before the helpers in some programs.
	 */
	private static final String VOXEL_FALLBACK = "\n#ifndef cubeworldTurn\n#define cubeworldTurn(v) (v)\n#define cubeworldFolded() false\n#endif\n";

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
				+ "        #define GetSunVector() (cubeworld_active != 0 ? normalize(cubeworld_sunPosition) : "
				+ SUN_DEFINE.substring(SUN_DEFINE.indexOf("normalize(")) + ")\n"
				+ "    #else\n" + SUN_DEFINE + "\n    #endif";
			return source.replace(SUN_DEFINE, sun);
		}
		if (name.endsWith(COMMON_ROOT)) {
			// The helpers go after the uniforms of the pack and before the time of day, which they replace.
			// Without a shadow map the pack takes the time of the world. On the cube it takes the local sun as with one.
			return source.replace(TIME_ANCHOR, HELPERS + TIME_ANCHOR.replace("#if SHADOW_QUALITY == -1", "#if SHADOW_QUALITY == -1 && !defined CUBEWORLD"))
				.replace(SUN_ANGLE, "float tAmin     = fract(cubeworldSunAngle() - 0.033333333);");
		}
		if (name.endsWith(AURORA)) {
			String header = "vec3 GetAuroraBorealis(vec3 viewPos, float VdotU, float dither) {";
			if (!source.contains(header)) {
				return source;
			}
			return source.replace(header, "vec3 GetAuroraBorealisFlat(vec3 viewPos, float VdotU, float dither) {") + CUBE_AURORA;
		}
		if (name.endsWith(STARS)) {
			// The stars keep their place on the sky, which turns with the sun. Its pole is the axis of the sun, and the
			// half of the sky past the equator shows the mirror of the other half, as the projection needs y above 0.
			return source.replace(
				"    vec3 wpos = normalize((gbufferModelViewInverse * vec4(viewPos * 1000.0, 1.0)).xyz);\n",
				"""
				    vec3 wpos = normalize((gbufferModelViewInverse * vec4(viewPos * 1000.0, 1.0)).xyz);
				    if (cubeworld_active != 0) {
				        wpos = normalize(cubeworld_celestialFrame * viewPos);
				        wpos.y = abs(wpos.y);
				    }
				""");
		}
		if (name.endsWith("/lib/voxelization/lightVoxelization.glsl")) {
			// The light volume of colored lighting, and the block volume of the reflections, are laid out in the frame of the
			// camera face. A folded face draws, and writes its blocks in the shadow pass, in its own frame, so its positions
			// are turned into the one of the camera face. The volumes stay one grid, since the turn takes blocks to blocks.
			return source.replace("return scenePos + cameraPositionBestFract", VOXEL_FALLBACK + "return cubeworldTurn(scenePos) + cameraPositionBestFract");
		}
		if (name.endsWith("/program/shadowcomp.glsl")) {
			// The flood fill of colored lighting spreads the light of the last frame. At a crossing that volume is laid out in the
			// frame of the old face, so its cells are found with the turn back, about the center of each cell. Without it the
			// light builds up from nothing over the next frames.
			return source.replace("\tivec3 previousPos = pos - ivec3(posOffset);\n", """
					\tivec3 previousPos = pos - ivec3(posOffset);
					#ifdef CUBEWORLD
					\tpreviousPos = ivec3(floor(cubeworld_crossTurn * (vec3(previousPos - voxelVolumeSize / 2) + 0.5))) + voxelVolumeSize / 2;
					#endif
					""");
		}
		if (name.endsWith("/lib/voxelization/reflectionVoxelization.glsl")) {
			return source.replace("return playerPos + cameraPositionBestFract", VOXEL_FALLBACK + "return cubeworldTurn(playerPos) + cameraPositionBestFract")
				.replace("return previousPlayerPos + previousCameraPositionBestFract", VOXEL_FALLBACK + "return cubeworldTurn(previousPlayerPos) + previousCameraPositionBestFract");
		}
		if (name.endsWith("/lib/voxelization/puddleVoxelization.glsl")) {
			// The puddle map is a map of columns, and a folded face stands on its side in it. Its puddles skip the map.
			return source.replace("return scenePos + fract(cameraPosition)", VOXEL_FALLBACK + "if (cubeworldFolded()) return vec3(-1.0);\n\treturn scenePos + fract(cameraPosition)");
		}
		if (name.endsWith("/program/shadow.glsl")) {
			// The faces of the blocks for the reflections are stored by their normal in the frame of the camera face. The
			// normal matrix of Iris lacks the fold of a folded face, which the matrix of the draw has.
			source = source.replace("vec3 normal = mat3(shadowModelViewInverse) * gl_NormalMatrix * gl_Normal;\n                UpdateSceneVoxelMap",
				VOXEL_FALLBACK + "vec3 normal = cubeworldTurn(mat3(shadowModelViewInverse) * mat3(gl_ModelViewMatrix) * gl_Normal);\n                UpdateSceneVoxelMap");
		}
		if (name.endsWith("/lib/colors/lightAndAmbientColors.glsl")) {
			// The light of the sky on the ground. Seen from a face at night, the ground of a face in day would get the dark
			// sky of the camera, and all of it out of the sun would be black.
			source = source.replace("vec3 noonClearAmbientColor = pow(skyColor, vec3(0.75)) * 0.85;",
				"\n#ifndef cubeworldSkyColor\n#define cubeworldSkyColor() skyColor\n#endif\n        vec3 noonClearAmbientColor = pow(cubeworldSkyColor(), vec3(0.75)) * 0.85;");
		}
		if (name.endsWith(MAIN_LIGHTING)) {
			source = source.replace("voxelPos = voxelPos + worldGeoNormal * 0.55;", VOXEL_FALLBACK + "voxelPos = voxelPos + cubeworldTurn(worldGeoNormal) * 0.55;");
			// There is one shadow map, cast from the sun or the moon after the time at the camera. A point lit by the
			// other one would read the shadows of the wrong side, so it takes the light of the pack past the shadow
			// distance. At the line where the light turns over, the shadows are faded out in any case.
			return source.replace(
				"float shadowLength = shadowDistance * 0.9166667 - lViewPos;",
				"""
				float shadowLength = shadowDistance * 0.9166667 - lViewPos;
				                    #ifdef CUBEWORLD
				                        if (cubeworld_active != 0 && dot(lightVec, cubeworld_shadowLight) < 0.0) shadowLength = 0.0;
				                    #endif""");
		}
		if (name.endsWith(MAIN_FOG) || name.endsWith("/program/deferred1.glsl")) {
			// The fog thickens toward the ground. Its height must follow the cube, or the ground of the camera face
			// cuts the sky along a plane that turns at each crossing.
			source = source.replace("GetAtmFogAltitudeFactor(playerPos.y + cameraPosition.y)", "GetAtmFogAltitudeFactor(cubeworldFeetAlt(playerPos))")
				.replace("GetAtmFogAltitudeFactor(cameraPosition.y + ", "GetAtmFogAltitudeFactor(cubeworldCameraAlt() + ");
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
				.replace("normalize(mat3(gbufferModelViewInverse) * normalMR)", "normalize(cubeworld_normalOut(normalMR))")
				.replace("dot(playerPos, worldNormalMR)", "dot(cubeworld_normalOut(mat3(gbufferModelView) * playerPos), worldNormalMR)")
				.replace("normalize(mat3(gbufferModelViewInverse) * nViewPosR)", "normalize(cubeworld_normalOut(nViewPosR))");
		}
		if (name.endsWith("/lib/materials/materialMethods/refraction.glsl")) {
			// The refraction is drawn once for the whole view, in the frame of the camera face. There the water of a folded
			// face stands on edge, so its noise stretched into streaks and changed at a crossing. The noise lies on the face
			// that the point of the cube is on, which does not depend on the face of the camera.
			source = source.replace("    vec3 worldPos = playerPos.xyz + cameraPosition.xyz;\n",
				"""
				    vec3 worldPos = playerPos.xyz + cameraPosition.xyz;
				    #ifdef CUBEWORLD
				        if (cubeworld_active != 0) {
				            vec3 cubePos = (cubeworld_cubeFromView * vec4(viewPos.xyz, 1.0)).xyz;
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
				        float RVdotU = dot(nViewPosR, cubeworld_active != 0 ? normalize(gbufferModelView[1].xyz) : upVec);
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
			source = source.replace("foam *= clamp((fract(worldPos.y) - 0.7) * 10.0, 0.0, 1.0);", "if (cubeworld_active == 0) foam *= clamp((fract(worldPos.y) - 0.7) * 10.0, 0.0, 1.0);");
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
			source = source.replace("mat3(gbufferModelViewInverse) * normalM", "cubeworld_normalOut(normalM)");
		}
		for (String program : POINT_UP_PROGRAMS) {
			if (name.endsWith("/program/" + program + ".glsl")) {
				return "#define CUBEWORLD_POINT_UP\n" + source.replace(UP, "upVec = cubeworldPointUp();");
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
			        vec3 wpos = PlayerToShadow(cubeworldToFeet(tracePos - cameraPos));
			#else
			        vec3 wpos = PlayerToShadow(tracePos - cameraPos);
			#endif""");
		// The two layers of the reimagined clouds: the one nearer to the camera is traced first.
		source = source.replace("if (abs(cameraPos.y - minCloudAlt) < abs(cameraPos.y - maxCloudAlt)) {",
			"if (abs(cubeworldAlt(cameraPos) - minCloudAlt) < abs(cubeworldAlt(cameraPos) - maxCloudAlt)) {");
		return source.replace(
			"    cameraPos += cameraPosOffset;\n",
			"""
			    cameraPos += cameraPosOffset;
			#if defined CLOUDS_UNBOUND || defined CLOUDS_REIMAGINED
			    if (cubeworld_active != 0) cameraPos = (cubeworld_cubeFromFeet * vec4(cameraPosOffset, 1.0)).xyz;
			#endif
			""");
	}

	/**
	 * The clouds that read a flat map take it on the plane of the face that a point of the cube is over: the axis of the
	 * point that is largest in size becomes y, the up of that face. The map then lies flat over each face, and changes
	 * plane at the diagonal over an edge.
	 */
	private static final String CLOUD_PLANE = """

		#ifndef CUBEWORLD_CLOUD_PLANE
		#define CUBEWORLD_CLOUD_PLANE
		vec3 cubeworldPlanar(vec3 v, vec3 p) {
		    if (cubeworld_active == 0) return v;
		    vec3 a = abs(p);
		    return a.y >= a.x && a.y >= a.z ? v : a.x >= a.z ? v.zxy : v.xzy;
		}
		#endif
		""";

	private static String patchReimaginedClouds(String source) {
		source = source.replace("const float cloudRoundness = 0.125;\n", "const float cloudRoundness = 0.125;\n" + CLOUD_PLANE)
			.replace("tracePosM = ModifyTracePos(tracePos, cloudAltitude);",
				"tracePosM = ModifyTracePos(cubeworld_active != 0 ? vec3(cubeworldPlanar(tracePos, tracePos).x, cubeworldAlt(tracePos), cubeworldPlanar(tracePos, tracePos).z) : tracePos, cloudAltitude);")
			.replace("vec3 cLightPosAdd = normalize(ViewToPlayer(lightVec * 1000000000.0)) * vec3(0.08);",
				"vec3 cLightPosAdd = normalize(ViewToPlayer(lightVec * 1000000000.0)) * vec3(0.08);\n"
					+ "                if (cubeworld_active != 0) cLightPosAdd = cubeworldPlanar(mat3(cubeworld_cubeFromFeet) * cLightPosAdd, tracePos);")
			.replace("float aboveClouds = clamp01((cameraPos.y - lowerPlaneAltitude) * 0.05);",
				"float aboveClouds = clamp01((cubeworldAlt(cameraPos) - lowerPlaneAltitude) * 0.05);");
		// The band of these clouds is thin, and they take one step per block of it. On the cube the march is cut to the
		// length that a ray at its angle to the shell stays in the band, so a ray along the shell does not take thousands.
		source = patchCloudShell(source,
			"min(max(distanceThreshold - cubeworldT, 0.0), cloudTallness / max(abs(dot(nPlayerPos, cubeworldNormal(cameraPos + cubeworldT * nPlayerPos))), 0.02))");
		return source;
	}

	/** The march of the volumetric clouds over the rounded shell of the cube. The length is how far it marches from the entry. */
	private static String patchCloudShell(String source, String length) {
		source = source.replace(
			"float threshold = clamp(abs(cloudAltitude - tracePos.y) / cloudStretch, 0.001, 0.999);",
			"float threshold = clamp(abs(cloudAltitude - cubeworldAlt(tracePos)) / cloudStretch, 0.001, 0.999);");
		// Over the cube the band of the clouds is a rounded shell. Step along the ray to it by the gap to the band,
		// which never steps over it.
		source = replaceBetween(source,
			"    float lowerPlaneDistance  = (lowerPlaneAltitude - cameraPos.y) / nPlayerPos.y;",
			"    float planeDistanceDif = maxPlaneDistance - minPlaneDistance;\n",
			original -> """
				    float minPlaneDistance;
				    float planeDistanceDif;
				    if (cubeworld_active != 0) {
				        nPlayerPos = normalize(mat3(cubeworld_cubeFromFeet) * nPlayerPos);
				        float cubeworldT = 0.0;
				        float cubeworldOut = 0.0;
				        bool cubeworldHit = false;
				        for (int j = 0; j < 160; j++) {
				            float gap = abs(cubeworldAlt(cameraPos + cubeworldT * nPlayerPos) - cloudAltitude) - cloudStretch;
				            if (gap <= 0.0) { cubeworldHit = true; break; }
				            // A ray that skims the shell closes the gap slowly. The floor on the step keeps it from running
				            // out of steps, at the cost of missing the thin tip of a skimming hit.
				            cubeworldOut = cubeworldT;
				            cubeworldT += max(gap, max(2.0, cubeworldT * 0.01));
				            if (cubeworldT > distanceThreshold) break;
				        }
				        if (!cubeworldHit) return vec4(0.0);
				        // The floor on the step lands up to a step deep in the band, by a depth that changes with the distance.
				        // The clouds start their march there, which drew dark rings about the camera. Find the edge of the band.
				        for (int j = 0; j < 8; j++) {
				            float mid = 0.5 * (cubeworldOut + cubeworldT);
				            if (abs(cubeworldAlt(cameraPos + mid * nPlayerPos) - cloudAltitude) <= cloudStretch) cubeworldT = mid;
				            else cubeworldOut = mid;
				        }
				        minPlaneDistance = cubeworldT;
				        planeDistanceDif = LENGTH;
				    } else {
				""".replace("LENGTH", length) + original.replace("    float minPlaneDistance = ", "    minPlaneDistance = ")
					.replace("    float planeDistanceDif = ", "    planeDistanceDif = ") + "    }\n");
		source = source.replace(
			"    tracePos.y -= traceAdd.y;\n",
			"    if (cubeworld_active != 0) tracePos -= traceAdd; else tracePos.y -= traceAdd.y;\n");
		// The haze and the end of the march read the distance on the ground. A ray that bends over an edge covers little of it
		// while it travels far, so on the cube they read the true distance.
		source = source.replace("float lTracePosXZ = length(cloudPlayerPos.xz);", "float lTracePosXZ = cubeworld_active != 0 ? lTracePos : length(cloudPlayerPos.xz);");
		return source.replace(
			"float cloudShading = 1.0 - (higherPlaneAltitude - tracePos.y) / cloudTallness;",
			"float cloudShading = 1.0 - (higherPlaneAltitude - cubeworldAlt(tracePos)) / cloudTallness;");
	}

	private static String patchUnboundClouds(String source) {
		// The march stops where the ray leaves the band. A ray can follow the shell around an edge onto the next face, so no
		// estimate of the length from the slope at the entry holds.
		source = patchCloudShell(source, "max(distanceThreshold - cubeworldT, 0.0)");
		// The low cloud quality reads a flat map of noise.
		source = source.replace("noise += texture2DLod(noisetex, tracePosM.xz - vec2(0.0, wind), 0.0).b * currentPersist;",
			"noise += texture2DLod(noisetex, cubeworldPlanar(tracePosM, tracePos).xz - vec2(0.0, wind), 0.0).b * currentPersist;")
			.replace("const float cloudTallness = cloudStretch * 2.0;\n", "const float cloudTallness = cloudStretch * 2.0;\n" + CLOUD_PLANE);
		// The closed area check reads the shadow map at points far from the camera. On the cube the body of the planet shades
		// the far side from the sun, so the check would remove the clouds there.
		source = source.replace("if (shadowLength < lTracePos)", "if (cubeworld_active == 0 && shadowLength < lTracePos)");
		source = source.replace(
			"float aboveClouds = clamp01((cameraPos.y - lowerPlaneAltitude) * 0.01);",
			"float aboveClouds = clamp01((cubeworldAlt(cameraPos) - lowerPlaneAltitude) * 0.01);");
		source = source.replace(
			"if (abs(tracePos.y - cloudAltitude) > cloudStretch) break;",
			"if (abs(cubeworldAlt(tracePos) - cloudAltitude) > cloudStretch) {\n    if (cubeworld_active == 0) break;\n    // Over the edge of the cube the ray can come back into the shell farther on, so look for the next entry.\n    float cubeworldT2 = length(tracePos - cameraPos);\n    bool cubeworldBack = false;\n    for (int j = 0; j < 64; j++) {\n        float gap2 = abs(cubeworldAlt(cameraPos + cubeworldT2 * nPlayerPos) - cloudAltitude) - cloudStretch;\n        if (gap2 <= 0.0) { cubeworldBack = true; break; }\n        cubeworldT2 += max(gap2, max(2.0, cubeworldT2 * 0.01));\n        if (cubeworldT2 > distanceThreshold) break;\n    }\n    if (!cubeworldBack) break;\n    // The offset by the dither of each pixel keeps the slices of neighbouring pixels apart, as at the first entry.\n    tracePos = cameraPos + (cubeworldT2 + stepMult * (dither - 1.0)) * nPlayerPos;\n}\n");
		source = source.replace(
			"GetCloudNoise(tracePos, cloudAltitude, lTracePosXZ, cloudPlayerPos.y)",
			"GetCloudNoise(tracePos, cloudAltitude, lTracePosXZ, cubeworldAlt(tracePos) - cubeworldAlt(cameraPos))");
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
		uniforms.uniform1i(UniformUpdateFrequency.PER_FRAME, "cubeworld_active", () -> CubeSky.active() && CubeSky.celestial() != null ? 1 : 0);
		uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "cubeworld_sunPosition", () -> {
			Matrix3f celestial = CubeSky.celestial();
			Vector3f sun = celestial == null ? new Vector3f(0.0F, 100.0F, 0.0F) : celestial.getColumn(1, new Vector3f()).mul(100.0F);
			return new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView()).transformDirection(sun);
		});
		uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "cubeworld_upPosition",
			() -> new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView()).transformDirection(new Vector3f(CubeSky.surfaceUp())));
		uniforms.uniformMatrix(UniformUpdateFrequency.PER_FRAME, "cubeworld_cubeFromFeet", CubeSky::cubeFromFeet);
		uniforms.uniformMatrix(UniformUpdateFrequency.PER_FRAME, "cubeworld_cubeFromView", () -> {
			// View to feet is the transpose of the rotation of the model view. Its translation is only view bobbing.
			Matrix4f feetFromView = new Matrix4f().set3x3(new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView())).transpose3x3();
			return new Matrix4f(CubeSky.cubeFromFeet()).mul(feetFromView);
		});
		uniforms.uniformMatrix3(UniformUpdateFrequency.PER_FRAME, "cubeworld_celestialFrame", () -> {
			// Rows: the side, the axis and the sun, so that y is the pole of the sky.
			Matrix3f celestial = CubeSky.celestial();
			Matrix3f rows = celestial == null ? new Matrix3f() : new Matrix3f(celestial).transpose();
			Vector3f axis = rows.getRow(2, new Vector3f());
			Vector3f sun = rows.getRow(1, new Vector3f());
			rows.setRow(1, axis).setRow(2, sun);
			Matrix3f viewToStorage = new Matrix3f().set(new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView())).transpose();
			return rows.mul(viewToStorage);
		});
		uniforms.uniformMatrix3(UniformUpdateFrequency.PER_FRAME, "cubeworld_viewToFeet",
			() -> new Matrix3f().set(new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView())).transpose());
		uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "cubeworld_shadowLight", () -> {
			// The light that the shadow map is cast from, as IrisShadowMatricesMixin picks it.
			Matrix3f celestial = CubeSky.celestial();
			if (celestial == null) {
				return new Vector3f(0.0F, 1.0F, 0.0F);
			}
			Vector3f light = celestial.getColumn(1, new Vector3f()).mul(CubeSky.shadowLightSign());
			return new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView()).transformDirection(light);
		});
		// IrisFaceUniforms writes the turn of a folded face while it draws, and puts the identity back.
		uniforms.uniformMatrix3(UniformUpdateFrequency.PER_FRAME, "cubeworld_faceTurn", Matrix3f::new);
		uniforms.uniformMatrix3(UniformUpdateFrequency.PER_FRAME, "cubeworld_crossTurn", FrameCrossing::backTurn);
		uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "cubeworld_daySkyColor", () -> new Vector3f(CubeSky.daySky()));
		uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "cubeworld_core", CubeSky::core);
		uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "cubeworld_centerY", CubeSky::centerY);
	}
}

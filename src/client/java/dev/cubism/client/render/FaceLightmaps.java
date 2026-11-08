package dev.cubism.client.render;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.cubism.client.SeamClient;
import dev.cubism.client.compat.sodium.SeamFaces;
import dev.cubism.worldgen.SeamLayout;
import java.util.function.BiConsumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import org.joml.Vector4fc;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * The lightmap of vanilla is one texture for the frame, read at the local time under the camera. From orbit the faces
 * of the cube have different local times, so each face gets a lightmap of its own, read at the middle of the face, and
 * it is bound while the terrain and the levels of detail of that face draw. The lightmaps turn from the one of the camera
 * to these with {@link CubeSky#orbit()}, so there is no change on the ground.
 */
public final class FaceLightmaps {
	private static final int FACES = SeamLayout.FACES.length;
	private static final Lightmap[] MAPS = new Lightmap[FACES];
	private static final LightmapRenderState[] STATES = new LightmapRenderState[FACES];
	private static final EnvironmentAttributeProbe[] PROBES = new EnvironmentAttributeProbe[FACES];
	private static final boolean[] PENDING = new boolean[FACES];
	private static boolean ready, rendering;
	/** The probe that the camera gives to the extractor of the lightmap while it reads a face. */
	private static @Nullable EnvironmentAttributeProbe override;

	private FaceLightmaps() {}

	public static @Nullable EnvironmentAttributeProbe probeOverride() {
		return override;
	}

	public static boolean extracting() {
		return override != null;
	}

	/** Called after the extractor filled the state of the camera. {@code fill} reads a state, with the probe that it gets from the camera. */
	public static void extract(ClientLevel level, LightmapRenderState camera, BiConsumer<LightmapRenderState, EnvironmentAttributeProbe> fill) {
		SeamLayout layout = SeamLayout.of(level);
		float orbit = CubeSky.orbit();
		ready = false;
		if (layout == null || orbit <= 0.0F) {
			return;
		}
		for (int f = 0; f < FACES; f++) {
			if (PROBES[f] == null) {
				PROBES[f] = new EnvironmentAttributeProbe();
				STATES[f] = new LightmapRenderState();
			}
			// The middle of the top of the face: the surface there has the local time of the face.
			PROBES[f].tick(level, new Vec3(layout.offsetX(f) + 0.5, layout.centerY + layout.radius, 0.5));
			override = PROBES[f];
			try {
				fill.accept(STATES[f], PROBES[f]);
			} finally {
				override = null;
			}
			LightmapRenderState state = STATES[f];
			state.skyFactor = Mth.lerp(orbit, camera.skyFactor, state.skyFactor);
			state.skyLightColor = lerp(orbit, camera.skyLightColor, state.skyLightColor);
			state.ambientColor = lerp(orbit, camera.ambientColor, state.ambientColor);
			// Everything else of a face is the same as of the camera.
			state.blockFactor = camera.blockFactor;
			state.blockLightTint = camera.blockLightTint;
			state.brightness = camera.brightness;
			state.darknessEffectScale = camera.darknessEffectScale;
			state.nightVisionEffectIntensity = camera.nightVisionEffectIntensity;
			state.nightVisionColor = camera.nightVisionColor;
			state.bossOverlayWorldDarkening = camera.bossOverlayWorldDarkening;
			state.needsUpdate = true;
			PENDING[f] = true;
		}
		ready = true;
	}

	private static Vector3fc lerp(float t, Vector3fc from, Vector3fc to) {
		return new Vector3f(Mth.lerp(t, from.x(), to.x()), Mth.lerp(t, from.y(), to.y()), Mth.lerp(t, from.z(), to.z()));
	}

	/** Draws the lightmaps of the faces, right after the lightmap of the camera. */
	public static void render() {
		if (rendering || !ready) {
			return;
		}
		rendering = true;
		try {
			for (int f = 0; f < FACES; f++) {
				if (PENDING[f]) {
					if (MAPS[f] == null) {
						MAPS[f] = new Lightmap();
					}
					MAPS[f].render(STATES[f]);
					PENDING[f] = false;
				}
			}
		} finally {
			rendering = false;
		}
	}

	/** The color of the cloud layer of a face: the color at the local time of the face, turned in from the camera color with the orbit. */
	public static int cloudColor(int face, int cameraColor, float partialTicks) {
		float orbit = CubeSky.orbit();
		if (!ready || orbit <= 0.0F || face < 0 || face >= FACES || PROBES[face] == null) {
			return cameraColor;
		}
		Vector4fc own = PROBES[face].getValue(EnvironmentAttributes.CLOUD_COLOR, partialTicks);
		return ARGB.colorFromFloat(
			Mth.lerp(orbit, ARGB.alphaFloat(cameraColor), own.w()),
			Mth.lerp(orbit, ARGB.redFloat(cameraColor), own.x()),
			Mth.lerp(orbit, ARGB.greenFloat(cameraColor), own.y()),
			Mth.lerp(orbit, ARGB.blueFloat(cameraColor), own.z())
		);
	}

	public static boolean ready() {
		return ready;
	}

	/**
	 * The lightmaps of the faces across the +X, -X, +Z and -Z edges of a face in its storage frame, for the terrain
	 * shader to blend toward near the diagonals. A face without a lightmap gets {@code fallback}.
	 */
	public static GpuTextureView[] neighbors(int face, GpuTextureView fallback) {
		GpuTextureView[] views = {fallback, fallback, fallback, fallback};
		SeamLayout layout = SeamClient.layout;
		if (layout == null || face < 0 || face >= FACES) {
			return views;
		}
		int[] m = layout.rotation(face);
		int[][] sides = {{m[0], m[3], m[6]}, {-m[0], -m[3], -m[6]}, {m[2], m[5], m[8]}, {-m[2], -m[5], -m[8]}};
		for (int side = 0; side < 4; side++) {
			for (int other = 0; other < FACES; other++) {
				int[] n = layout.rotation(other);
				if (MAPS[other] != null && n[1] == sides[side][0] && n[4] == sides[side][1] && n[7] == sides[side][2]) {
					views[side] = MAPS[other].getTextureView();
				}
			}
		}
		return views;
	}

	/** The lightmap of the face that draws now, where the one of the camera is asked for. */
	public static GpuTextureView view(GpuTextureView requested, GpuTextureView camera) {
		SeamLayout layout = SeamClient.layout;
		if (!ready || requested != camera || layout == null || !SeamClient.SODIUM) {
			return requested;
		}
		int face = SeamFaces.currentFace();
		return face < 0 || face >= FACES || MAPS[face] == null ? requested : MAPS[face].getTextureView();
	}
}

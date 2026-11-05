package dev.cubeworld.client.sodiumseam;

import dev.cubeworld.client.SeamClient;
import dev.cubeworld.worldgen.SeamLayout;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.bounding.StreamedBoundStore;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Voxy on the cube. Voxy draws from inside the chunk renderer of Sodium, so it draws once for every face that
 * {@link SeamFaces} draws, with the folded matrices and camera of that face. Each of these draws keeps to the region of
 * its face, and has its own viewport and its own set of the sections that Sodium draws there. Only load this class when
 * Voxy is present.
 */
public final class VoxyFaces {
	private static final Logger LOGGER = LoggerFactory.getLogger("cubeworld");
	private static final int FACES = SeamLayout.FACES.length;
	private static final Object[] VIEWPORT_KEYS = new Object[FACES];
	private static final StreamedBoundStore[] STORES = new StreamedBoundStore[FACES];
	private static @Nullable VoxyRenderSystem storesOf;

	static {
		for (int f = 0; f < FACES; f++) {
			VIEWPORT_KEYS[f] = new Object();
		}
	}

	private VoxyFaces() {}

	public static boolean active() {
		return SeamClient.layout != null && SeamFaces.active();
	}

	public static Object viewportKey() {
		return VIEWPORT_KEYS[SeamFaces.currentFace()];
	}

	/** The sections that the Sodium manager of the current face draws, where Voxy draws none of its own. */
	public static StreamedBoundStore store(VoxyRenderSystem system) {
		if (storesOf != system) {
			free();
			storesOf = system;
		}
		int face = SeamFaces.currentFace();
		if (STORES[face] == null) {
			STORES[face] = new StreamedBoundStore();
		}
		return STORES[face];
	}

	public static void free() {
		for (int f = 0; f < FACES; f++) {
			if (STORES[f] != null) {
				STORES[f].free();
				STORES[f] = null;
			}
		}
		storesOf = null;
	}

	/**
	 * The storage region of the current face, relative to the corner of the 32 block section of the camera that the quads
	 * of Voxy are placed from: min x, max x, min z, max z. The faces lie side by side in storage, so a draw that leaves its
	 * region shows another face in the wrong place.
	 */
	public static float[] region(Viewport<?> viewport) {
		SeamLayout layout = SeamClient.layout;
		if (layout == null || !SeamFaces.active()) {
			return new float[] {-1.0E9F, 1.0E9F, -1.0E9F, 1.0E9F};
		}
		int center = layout.offsetX(SeamFaces.currentFace());
		int half = layout.spacing / 2;
		int baseX = viewport.section.x << 5, baseZ = viewport.section.z << 5;
		return new float[] {center - half - baseX, center + half - baseX, -half - baseZ, half - baseZ};
	}

	/** Adds the face region to the scene block of the shaders of Voxy, and clips each quad corner to it. */
	public static String patchShader(String path, String source) {
		if (source == null) {
			return null;
		}
		if (path.endsWith("lod/gl46/bindings.glsl")) {
			return replaceOnce(path, source, "    vec3 cameraSubPos;\n};", "    vec3 cameraSubPos;\n    vec4 cubeworldRegion;\n};");
		}
		if (path.endsWith("lod/quad_util.glsl")) {
			String pos = "    vec4 pos = MVP * vec4(point, 1.0f);";
			return replaceOnce(path, source, pos, """
				    gl_ClipDistance[0] = point.x - cubeworldRegion.x;
				    gl_ClipDistance[1] = cubeworldRegion.y - point.x;
				    gl_ClipDistance[2] = point.z - cubeworldRegion.z;
				    gl_ClipDistance[3] = cubeworldRegion.w - point.z;
				""" + pos);
		}
		return source;
	}

	private static String replaceOnce(String path, String source, String find, String replacement) {
		String text = source.replace("\r\n", "\n");
		if (!text.contains(find)) {
			LOGGER.warn("Cube World could not patch the Voxy shader {}: the faces of the cube will overlap", path);
			return source;
		}
		return text.replace(find, replacement);
	}

	/**
	 * Voxy loads its levels of detail in a square about the camera, in storage. The cube draws all faces, which lie along
	 * +X in storage, so the square stays on the middle of the row of faces.
	 */
	public static double centerX(double cameraX) {
		SeamLayout layout = SeamClient.layout;
		return layout == null ? cameraX : layout.offsetX(FACES - 1) / 2.0;
	}

	public static double centerZ(double cameraZ) {
		return SeamClient.layout == null ? cameraZ : 0.0;
	}

	/**
	 * Turns the blocks of a section that are outside the wedge of its face into air, as the meshers of the face do. A face
	 * region holds blocks past its wedge that belong to the next face, and the folded face would draw them over it.
	 */
	public static VoxelizedSection cutToWedge(VoxelizedSection section) {
		SeamLayout layout = SeamClient.layout;
		if (layout == null) {
			return section;
		}
		int bx = section.x << 4, by = section.y << 4, bz = section.z << 4;
		// The wedge is convex, so a section with all its corners inside is all inside.
		boolean inside = true;
		for (int c = 0; c < 8 && inside; c++) {
			inside = layout.insideWedge(bx + (c & 1) * 15, by + (c >> 1 & 1) * 15, bz + (c >> 2) * 15);
		}
		if (inside) {
			return section;
		}
		long[] data = section.section;
		int nonAir = 0;
		for (int i = 0; i < 4096; i++) {
			if (!layout.insideWedge(bx + (i & 15), by + (i >> 8 & 15), bz + (i >> 4 & 15))) {
				data[i] = Mapper.airWithLight(Mapper.getLightId(data[i]));
			} else if (Mapper.getBlockId(data[i]) != 0) {
				nonAir++;
			}
		}
		section.lvl0NonAirCount = nonAir;
		return section;
	}
}

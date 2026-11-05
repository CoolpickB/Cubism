package dev.cubeworld.client.sodiumseam;

import dev.cubeworld.client.SeamClient;
import dev.cubeworld.worldgen.SeamLayout;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.bounding.StreamedBoundStore;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL45C;
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
	private static int depthCopy, copyWidth, copyHeight, copyFormat;

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
		freeDepthCopy();
	}

	/**
	 * The wedge of the current face, relative to the corner of the 32 block section of the camera that the quads of Voxy
	 * are placed from: the center of the cube in x, y and z, and half the width of the face region. Each face is stored
	 * upright, so its wedge is where the height above the center is at least the distance from the center in x and in z.
	 * The draw of a face keeps to its wedge and its region, and the draw of the next face starts at the same diagonal.
	 */
	public static float[] region(Viewport<?> viewport) {
		SeamLayout layout = SeamClient.layout;
		if (layout == null || !SeamFaces.active()) {
			// With y far below, every point is inside the wedge and the region.
			return new float[] {0.0F, -1.0E9F, 0.0F, 1.0E9F};
		}
		int baseX = viewport.section.x << 5, baseY = viewport.section.y << 5, baseZ = viewport.section.z << 5;
		return new float[] {
			layout.offsetX(SeamFaces.currentFace()) + 0.5F - baseX, layout.centerY + 0.5F - baseY, 0.5F - baseZ, layout.spacing / 2.0F
		};
	}

	/** Adds the wedge of the face to the scene block of the shaders of Voxy, and clips each quad corner to it. */
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
				    vec3 cubeworldD = point - cubeworldRegion.xyz;
				    gl_ClipDistance[0] = cubeworldD.y - cubeworldD.x;
				    gl_ClipDistance[1] = cubeworldD.y + cubeworldD.x;
				    gl_ClipDistance[2] = cubeworldD.y - cubeworldD.z;
				    gl_ClipDistance[3] = cubeworldD.y + cubeworldD.z;
				    gl_ClipDistance[4] = cubeworldRegion.w - cubeworldD.x;
				    gl_ClipDistance[5] = cubeworldRegion.w + cubeworldD.x;
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
	 * The depth texture that Voxy marks the pixels with terrain from, where it draws no levels of detail. Voxy writes the
	 * depth of its levels of detail into the depth of the frame after each draw, so a later face would take the levels of
	 * detail of an earlier face for terrain and leave holes where it is in front of them. Each face reads a copy of the
	 * depth from before the first draw of Voxy in the frame, which is the draw of the camera face. The depth that Voxy
	 * writes is depth tested, so the nearer face still wins where the levels of detail of two faces overlap.
	 */
	public static int terrainDepth(int sourceDepthTexture) {
		if (!active()) {
			return sourceDepthTexture;
		}
		if (SeamFaces.onCameraFace()) {
			int width = GL45C.glGetTextureLevelParameteri(sourceDepthTexture, 0, GL45C.GL_TEXTURE_WIDTH);
			int height = GL45C.glGetTextureLevelParameteri(sourceDepthTexture, 0, GL45C.GL_TEXTURE_HEIGHT);
			int format = GL45C.glGetTextureLevelParameteri(sourceDepthTexture, 0, GL45C.GL_TEXTURE_INTERNAL_FORMAT);
			if (depthCopy == 0 || width != copyWidth || height != copyHeight || format != copyFormat) {
				freeDepthCopy();
				depthCopy = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
				GL45C.glTextureStorage2D(depthCopy, 1, format, width, height);
				copyWidth = width;
				copyHeight = height;
				copyFormat = format;
			}
			GL45C.glCopyImageSubData(sourceDepthTexture, GL45C.GL_TEXTURE_2D, 0, 0, 0, 0, depthCopy, GL45C.GL_TEXTURE_2D, 0, 0, 0, 0, width, height, 1);
		}
		return depthCopy == 0 ? sourceDepthTexture : depthCopy;
	}

	private static void freeDepthCopy() {
		if (depthCopy != 0) {
			GL45C.glDeleteTextures(depthCopy);
			depthCopy = 0;
		}
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
	 * Fills the voxels of a section that are past the wedge of its face with the real blocks there, from the face that
	 * owns them, as the meshers of Sodium read them (see LevelSliceMixin). The region of a face holds only air past its
	 * wedge, so Voxy would draw the cut faces of the ground along the diagonal. The draw of the face clips these voxels away.
	 * This runs on a Voxy thread and reads the client level, as the meshers of Sodium do from their threads.
	 */
	public static VoxelizedSection fillPastWedge(VoxelizedSection section, Mapper mapper) {
		SeamLayout layout = SeamClient.layout;
		ClientLevel level = Minecraft.getInstance().level;
		if (layout == null || level == null) {
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
		LayerLightEventListener sky = level.getLightEngine().getLayerListener(LightLayer.SKY);
		LayerLightEventListener block = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
		long[] data = section.section;
		int[] owner = new int[3];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int nonAir = 0;
		for (int i = 0; i < 4096; i++) {
			int x = bx + (i & 15), y = by + (i >> 8 & 15), z = bz + (i >> 4 & 15);
			if (!layout.insideWedge(x, y, z) && layout.ownerStorage(x, y, z, owner)) {
				pos.set(owner[0], owner[1], owner[2]);
				BlockState state = level.getBlockState(pos);
				if (!state.isSolidRender() && state.getFluidState().isEmpty()) {
					// A block that is not a full cube keeps the orientation of its own face, which is turned here.
					state = Blocks.AIR.defaultBlockState();
				}
				int light = Math.min(15, sky.getLightValue(pos)) | Math.min(15, block.getLightValue(pos)) << 4;
				data[i] = Mapper.composeMappingId((byte) light, mapper.getIdForBlockState(state), Mapper.getBiomeId(data[i]));
			}
			if (!Mapper.isAir(data[i])) {
				nonAir++;
			}
		}
		section.lvl0NonAirCount = nonAir;
		return section;
	}
}

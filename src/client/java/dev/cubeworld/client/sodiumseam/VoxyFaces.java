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
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
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
	private static final java.util.List<Deferred> DEFERRED = new java.util.ArrayList<>();
	private static boolean drawingDeferred, copied;

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
	 * The wedge of the current face for the shaders of Voxy, relative to the corner of the 32 block section of the camera
	 * that the quads of Voxy are placed from: the center block of the cube in x, y and z, half the width of the face
	 * region, and then the least height above the distance from the center in x and in z that a block needs to belong to
	 * the face. Each face is stored upright, so its wedge is where the height above the center is at least the distance
	 * from the center in x and in z. A block on the diagonal belongs to one of the two faces, as {@link SeamLayout#ownerFace}
	 * decides, so the shader keeps whole blocks as the meshers of Sodium do.
	 */
	public static float[] region(Viewport<?> viewport) {
		SeamLayout layout = SeamClient.layout;
		if (layout == null || !SeamFaces.active()) {
			// With the center far below, every block is inside the wedge and the region.
			return new float[] {0.0F, -1.0E9F, 0.0F, 1.0E9F, 0.0F, 0.0F, 0.0F, 0.0F};
		}
		int face = SeamFaces.currentFace();
		int baseX = viewport.section.x << 5, baseY = viewport.section.y << 5, baseZ = viewport.section.z << 5;
		int normal = cubeAxisRank(layout, face, 0, 1, 0);
		return new float[] {
			layout.offsetX(face) - baseX, layout.centerY - baseY, -baseZ, layout.spacing / 2.0F,
			normal > cubeAxisRank(layout, face, 1, 0, 0) ? 0.0F : 1.0F, normal > cubeAxisRank(layout, face, 0, 0, 1) ? 0.0F : 1.0F, 0.0F, 0.0F
		};
	}

	/** Where the axis of the cube that a storage axis of the face turns to stands when faces tie: Y over X over Z. */
	private static int cubeAxisRank(SeamLayout layout, int face, int x, int y, int z) {
		int[] cube = new int[3];
		layout.toCube(face, layout.offsetX(face) + x, layout.centerY + y, z, cube);
		return cube[1] != layout.centerY ? 2 : cube[0] != 0 ? 1 : 0;
	}

	/** Adds the wedge of the face to the scene block of the shaders of Voxy, and drops the fragments of blocks outside it. */
	public static String patchShader(String path, String source) {
		if (source == null) {
			return null;
		}
		if (path.endsWith("lod/gl46/bindings.glsl")) {
			return replaceOnce(path, source, "    vec3 cameraSubPos;\n};", "    vec3 cameraSubPos;\n    vec4 cubeworldRegion;\n    vec4 cubeworldTies;\n};");
		}
		if (path.endsWith("lod/quad_util.glsl")) {
			String pos = "    vec4 pos = MVP * vec4(point, 1.0f);";
			source = replaceOnce(path, source, "vec4 getQuadCornerPos(", "vec3 cubeworldCorner;\n\nvec4 getQuadCornerPos(");
			return replaceOnce(path, source, pos, "    cubeworldCorner = point;\n" + pos);
		}
		if (path.endsWith("lod/gl46/quads3.vert")) {
			source = replaceOnce(path, source, "void main() {", "layout(location = 6) out vec3 cubeworldPoint;\n\nvoid main() {");
			return replaceOnce(path, source, "    #ifndef USE_NV_BARRY\n    uv = getCornerUV", "    cubeworldPoint = cubeworldCorner;\n\n    #ifndef USE_NV_BARRY\n    uv = getCornerUV");
		}
		if (path.endsWith("lod/gl46/quads.frag")) {
			source = replaceOnce(path, source, "\nvoid main() {", "\nlayout(location = 6) in vec3 cubeworldPoint;\n\nvoid main() {");
			// The block of a fragment lies just behind its face, against the direction that the face points to.
			return replaceOnce(path, source, "    if (any(notEqual(clamp(tile,", """
				    {
				        uint cubeworldFace = getFace();
				        vec3 cubeworldNormal = vec3(0);
				        float cubeworldSign = (cubeworldFace & 1u) == 1u ? 1.0 : -1.0;
				        if ((cubeworldFace >> 1) == 0u) cubeworldNormal.y = cubeworldSign;
				        else if ((cubeworldFace >> 1) == 1u) cubeworldNormal.z = cubeworldSign;
				        else cubeworldNormal.x = cubeworldSign;
				        ivec3 cubeworldBlock = ivec3(floor(cubeworldPoint - cubeworldNormal * 0.01 - cubeworldRegion.xyz));
				        int cubeworldHalf = int(cubeworldRegion.w);
				        if (cubeworldBlock.y - abs(cubeworldBlock.x) < int(cubeworldTies.x)
				            || cubeworldBlock.y - abs(cubeworldBlock.z) < int(cubeworldTies.y)
				            || cubeworldBlock.x < -cubeworldHalf || cubeworldBlock.x >= cubeworldHalf) {
				            discard;
				            return;
				        }
				    }

				    if (any(notEqual(clamp(tile,""");
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
	 * depth from before the first draw of Voxy in the frame, after the opaque terrain of every face, see {@link #defer}. The depth that Voxy
	 * writes is depth tested, so the nearer face still wins where the levels of detail of two faces overlap.
	 */
	public static int terrainDepth(int sourceDepthTexture) {
		if (!active()) {
			return sourceDepthTexture;
		}
		if (!copied) {
			copied = true;
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

	/**
	 * Voxy draws at the end of the cutout pass of each face, and the camera face draws first. A face that draws later
	 * was not in the depth that {@link #terrainDepth} copied, so Voxy drew its levels of detail over the blocks of that
	 * face. All draws of Voxy wait until the opaque terrain of every face is drawn. Returns true when the draw waits.
	 */
	public static boolean defer(VoxyRenderSystem system, Viewport<?> viewport, int depth, int color) {
		if (!active() || drawingDeferred) {
			return false;
		}
		SeamFaces.afterOpaque = () -> drawDeferred(system);
		DEFERRED.add(new Deferred(SeamFaces.currentFace(), viewport, depth, color));
		return true;
	}

	private static void drawDeferred(VoxyRenderSystem system) {
		drawingDeferred = true;
		copied = false;
		try {
			for (Deferred draw : DEFERRED) {
				SeamFaces.setWorking(draw.face);
				system.renderOpaque(draw.viewport, draw.depth, draw.color);
			}
		} finally {
			SeamFaces.setWorking(-1);
			DEFERRED.clear();
			drawingDeferred = false;
		}
	}

	private record Deferred(int face, Viewport<?> viewport, int depth, int color) {}

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
			// The face across the seam is loaded later or not at all; reingestAcrossSeam fills the voxel when it loads.
			if (!layout.insideWedge(x, y, z) && layout.ownerStorage(x, y, z, owner) && level.hasChunk(owner[0] >> 4, owner[2] >> 4)) {
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

	/**
	 * Has Voxy store again the chunks of other faces whose voxels past their wedge are filled from the chunk that just
	 * loaded, see {@link #fillPastWedge}. Those voxels lie within a section of the diagonal of their own face.
	 */
	public static void reingestAcrossSeam(ClientLevel level, int chunkX, int chunkZ) {
		SeamLayout layout = SeamClient.layout;
		int x0 = chunkX << 4, z0 = chunkZ << 4;
		if (layout == null || !layout.inRegion(x0 + 8, z0 + 8)) {
			return;
		}
		int face = layout.faceIndexAt(x0 + 8);
		LongOpenHashSet chunks = new LongOpenHashSet();
		int[] cube = new int[3], other = new int[3];
		for (int y = level.getMinY(); y <= level.getMaxY(); y += 16) {
			for (int c = 0; c < 8; c++) {
				layout.toCube(face, x0 + (c & 1) * 15, y + (c >> 1 & 1) * 15, z0 + (c >> 2) * 15, cube);
				for (int g = 0; g < FACES; g++) {
					if (g == face) {
						continue;
					}
					layout.toStorage(g, cube[0], cube[1], cube[2], other);
					int dx = Math.abs(other[0] - layout.offsetX(g)), dy = other[1] - layout.centerY, dz = Math.abs(other[2]);
					if (layout.inRegion(other[0], other[2]) && layout.faceIndexAt(other[0]) == g && dy >= Math.max(dx, dz) - 32) {
						chunks.add(ChunkPos.pack(other[0] >> 4, other[2] >> 4));
					}
				}
			}
		}
		for (long key : chunks) {
			LevelChunk chunk = level.getChunkSource().getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), ChunkStatus.FULL, false);
			if (chunk != null) {
				VoxelIngestService.tryAutoIngestChunk(chunk);
			}
		}
	}
}

package dev.cubism.client.compat.voxy;

import dev.cubism.client.compat.sodium.SeamFaces;

import dev.cubism.client.SeamClient;
import dev.cubism.worldgen.SeamLayout;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.bounding.StreamedBoundStore;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
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
import net.minecraft.world.phys.Vec3;
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
	private static final Logger LOGGER = LoggerFactory.getLogger("cubism");
	private static final int FACES = SeamLayout.FACES.length;
	/** The faces and space: Sodium has a manager for the empty chunks of space too, and Voxy needs a store and a viewport for it. */
	private static final int REGIONS = FACES + 1;
	private static final Object[] VIEWPORT_KEYS = new Object[REGIONS];
	private static final StreamedBoundStore[] STORES = new StreamedBoundStore[REGIONS];
	private static @Nullable VoxyRenderSystem storesOf;
	private static int depthCopy, copyWidth, copyHeight, copyFormat;
	private static final java.util.List<Deferred> DEFERRED = new java.util.ArrayList<>();
	private static boolean drawingDeferred, copied, laterFace, waterHeld, waterPass, firstWater;

	static {
		for (int f = 0; f < REGIONS; f++) {
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
		return store(system, SeamFaces.currentFace());
	}

	public static StreamedBoundStore store(VoxyRenderSystem system, int face) {
		if (storesOf != system) {
			free();
			storesOf = system;
		}
		if (STORES[face] == null) {
			STORES[face] = new StreamedBoundStore();
		}
		return STORES[face];
	}

	public static void free() {
		for (int f = 0; f < REGIONS; f++) {
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
			return replaceOnce(path, source, "    vec3 cameraSubPos;\n};", "    vec3 cameraSubPos;\n    vec4 cubismRegion;\n    vec4 cubismTies;\n};");
		}
		if (path.endsWith("lod/quad_util.glsl")) {
			String pos = "    vec4 pos = MVP * vec4(point, 1.0f);";
			// The fragment shader reads the light, blended toward the faces across the edges, from the id in the top bits.
			source = replaceOnce(path, source, "    vec4 tinting = getLighting(lighting);", "    vec4 tinting = vec4(1.0, 1.0, 1.0, getLighting(lighting).w);");
			source = replaceOnce(path, source, "    attributes.z = addin|(face<<8);", "    attributes.z = addin|(face<<8)|(lighting<<16);");
			source = replaceOnce(path, source, "vec4 getQuadCornerPos(", "vec3 cubismCorner;\n\nvec4 getQuadCornerPos(");
			return replaceOnce(path, source, pos, "    cubismCorner = point;\n" + pos);
		}
		if (path.endsWith("lod/gl46/quads3.vert")) {
			source = replaceOnce(path, source, "void main() {", "layout(location = 6) out vec3 cubismPoint;\n\nvoid main() {");
			return replaceOnce(path, source, "    #ifndef USE_NV_BARRY\n    uv = getCornerUV", "    cubismPoint = cubismCorner;\n\n    #ifndef USE_NV_BARRY\n    uv = getCornerUV");
		}
		if (path.endsWith("lod/gl46/quads.frag")) {
			source = replaceOnce(path, source, "#import <voxy:lod/lighting.glsl>", "#import <voxy:lod/lighting.glsl>\n" + LIGHT_BLEND);
			source = replaceOnce(path, source, "    return (colour * uint2vec4RGBA(interData.y)) +", "    vec4 cubismTint = uint2vec4RGBA(interData.y);\n    cubismTint.rgb *= cubismLight(interData.w >> 16).rgb;\n    return (colour * cubismTint) +");
			// The block of a fragment lies just behind its face, against the direction that the face points to.
			return replaceOnce(path, source, "    if (any(notEqual(clamp(tile,", """
				    {
				        uint cubismFace = getFace();
				        vec3 cubismNormal = vec3(0);
				        float cubismSign = (cubismFace & 1u) == 1u ? 1.0 : -1.0;
				        if ((cubismFace >> 1) == 0u) cubismNormal.y = cubismSign;
				        else if ((cubismFace >> 1) == 1u) cubismNormal.z = cubismSign;
				        else cubismNormal.x = cubismSign;
				        ivec3 cubismBlock = ivec3(floor(cubismPoint - cubismNormal * 0.01 - cubismRegion.xyz));
				        int cubismHalf = int(cubismRegion.w);
				        if (cubismBlock.y - abs(cubismBlock.x) < int(cubismTies.x)
				            || cubismBlock.y - abs(cubismBlock.z) < int(cubismTies.y)
				            || cubismBlock.x < -cubismHalf || cubismBlock.x >= cubismHalf) {
				            discard;
				            return;
				        }
				    }

				    if (any(notEqual(clamp(tile,""");
		}
		return source;
	}

	/**
	 * The light of a fragment: the lightmap of the face blended toward the faces across its edges, half at the diagonal and
	 * none 32 blocks from it, as in the terrain shader of Sodium. The position in the frame of the face is the position of
	 * the fragment minus the region.
	 */
	private static final String LIGHT_BLEND = """
		layout(location = 6) in vec3 cubismPoint;
		layout(binding = 10) uniform sampler2D cubismLight0;
		layout(binding = 11) uniform sampler2D cubismLight1;
		layout(binding = 12) uniform sampler2D cubismLight2;
		layout(binding = 13) uniform sampler2D cubismLight3;
		layout(binding = 14) uniform sampler2D cubismLight4;

		vec4 cubismLight(uint id) {
		    vec2 uv = getLightmapUv(id);
		    vec4 own = textureLod(cubismLight0, uv, 0);
		    vec3 local = cubismPoint - cubismRegion.xyz;
		    vec4 weights = 0.5 * (1.0 - clamp(vec4(local.y - local.x, local.y + local.x, local.y - local.z, local.y + local.z) / 32.0, 0.0, 1.0));
		    float total = weights.x + weights.y + weights.z + weights.w;
		    if (total <= 0.0) {
		        return own;
		    }
		    vec4 other = textureLod(cubismLight1, uv, 0) * weights.x + textureLod(cubismLight2, uv, 0) * weights.y
		        + textureLod(cubismLight3, uv, 0) * weights.z + textureLod(cubismLight4, uv, 0) * weights.w;
		    return mix(own, other / total, min(total, 1.0));
		}
		""";

	/** First texture unit that {@link #bindLightBlend} fills: the face, then the faces across +X, -X, +Z and -Z. Must match the bindings above. */
	private static final int LIGHT_UNIT = 10;

	/** Binds the lightmaps that the patched fragment shader blends, after Voxy bound the one of the face. */
	public static void bindLightBlend(int sampler) {
		var own = Minecraft.getInstance().gameRenderer.levelLightmap();
		var views = dev.cubism.client.render.FaceLightmaps.neighbors(SeamFaces.currentFace(), own);
		bindUnit(LIGHT_UNIT, own, sampler);
		for (int side = 0; side < 4; side++) {
			bindUnit(LIGHT_UNIT + 1 + side, views[side], sampler);
		}
	}

	private static void bindUnit(int unit, com.mojang.renderpearl.api.textures.GpuTextureView view, int sampler) {
		GL45C.glBindSampler(unit, sampler);
		GL45C.glBindTextureUnit(unit, ((com.mojang.renderpearl.backend.opengl.GlTexture) view.texture()).glId());
	}

	private static String replaceOnce(String path, String source, String find, String replacement) {
		String text = source.replace("\r\n", "\n");
		if (!text.contains(find)) {
			LOGGER.warn("Cubism could not patch the Voxy shader {}: the faces of the cube will overlap", path);
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
		if (viewport == null) {
			// The shadow pass of a shader pack has no viewport, and Voxy draws nothing then. In the list it would count as the first face.
			return true;
		}
		SeamFaces.afterOpaque = () -> drawDeferred(system);
		DEFERRED.add(new Deferred(SeamFaces.currentFace(), viewport, depth, color));
		return true;
	}

	/**
	 * From space the faces overlap in the view. Each face draws its levels of detail without the depth of the faces
	 * before it, so the face that draws last shows on top, and a far face drawn after the near one showed through it.
	 * The faces draw from the farthest to the nearest: the faces that turn away from the camera first, the camera
	 * face last. Each face draws without the depth of the others, so a face that drew after the camera face covered the
	 * ground of its levels of detail where they meet the loaded chunks.
	 */
	private static void sortFarToNear() {
		SeamLayout layout = SeamClient.layout;
		if (layout == null || DEFERRED.size() < 2) {
			return;
		}
		Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
		Vec3 c = layout.cubeOffset(layout.regionAt(Mth.floor(camera.x), Mth.floor(camera.z)), camera.x, camera.y, camera.z);
		DEFERRED.sort(java.util.Comparator.comparingDouble(draw -> {
			if (draw.face >= SeamLayout.FACES.length) {
				return Double.NEGATIVE_INFINITY;
			}
			Vec3 down = layout.faceDown(draw.face);
			return -(down.x * c.x + down.y * c.y + down.z * c.z);
		}));
	}

	private static void drawDeferred(VoxyRenderSystem system) {
		drawingDeferred = true;
		copied = false;
		sortFarToNear();
		try {
			laterFace = false;
			for (Deferred draw : DEFERRED) {
				SeamFaces.setWorking(draw.face);
				system.renderOpaque(draw.viewport, draw.depth, draw.color);
				laterFace = true;
			}
			if (waterHeld) {
				waterPass = true;
				firstWater = true;
				for (Deferred draw : DEFERRED) {
					SeamFaces.setWorking(draw.face);
					system.renderOpaque(draw.viewport, draw.depth, draw.color);
					firstWater = false;
				}
			}
		} finally {
			laterFace = false;
			waterHeld = false;
			waterPass = false;
			SeamFaces.setWorking(-1);
			DEFERRED.clear();
			drawingDeferred = false;
		}
	}

	/**
	 * Voxy draws the list of a viewport that it built in the frame before, then updates its geometry and builds the list
	 * again. The first face of the frame updates the geometry, so the list that a later face built in the frame before
	 * can point at geometry that changed, and drew jumbled levels of detail in the sky for a frame while chunks loaded.
	 * A later face does not update the geometry, and draws its list only after it builds it again.
	 */
	public static boolean laterFace() {
		return laterFace;
	}

	/**
	 * With a shader pack, the pack draws the water of the levels of detail over the colour of the land behind it, and
	 * finds the water later where its depth in the translucent depth of Voxy is nearer than the depth of the land. Voxy
	 * copies its opaque depth over the translucent depth before each translucent pass. When each face drew its water right
	 * after its land, a later face drew its land over the water of an earlier face, and the copy cleared the depth of
	 * that water, so the land across an edge showed as if under water. The faces draw the land of all faces first, and
	 * then the water of all faces in a second pass. Returns true in the first pass, where the water waits.
	 */
	public static boolean holdWater(Object pipeline) {
		if (!drawingDeferred || waterPass || !(pipeline instanceof me.cortex.voxy.client.core.IrisVoxyRenderPipeline)) {
			return false;
		}
		waterHeld = true;
		return true;
	}

	/** The second pass of {@link #holdWater}, which draws only the water. */
	public static boolean waterPass() {
		return waterPass;
	}

	/** The first face of the second pass, which copies the opaque depth of all faces over the translucent depth. */
	public static boolean firstWater() {
		return firstWater;
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

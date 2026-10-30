package dev.cubeworld.client.render;

import dev.cubeworld.client.SeamClient;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import dev.cubeworld.client.mixin.RenderSectionAccessor;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws the chunks of neighbor faces that the server sends across seams. Each neighbor face G is drawn with the
 * rigid motion that maps the storage frame of G onto the storage frame of the camera face F, so G appears folded
 * over the shared cube edge. The vanilla view area only covers the square around the camera, so these sections
 * live here. They use the vanilla compile queue and the vanilla terrain draw code.
 */
public final class SeamRenderer {
	private static final int COMPILES_PER_FRAME = 16;

	private static final LongOpenHashSet chunks = new LongOpenHashSet();
	private static final LongOpenHashSet pendingChunks = new LongOpenHashSet();
	private static final Long2ObjectMap<SeamSection> sections = new Long2ObjectOpenHashMap<>();
	private static final LongLinkedOpenHashSet dirtySections = new LongLinkedOpenHashSet();
	/**
	 * Meshes that change owner when the camera crosses a seam, so the terrain shows while the new owner compiles.
	 * {@code handover} keeps seam meshes until the vanilla view area has a section at their position.
	 * {@code adopted} keeps vanilla meshes until their chunk arrives here as a seam chunk.
	 */
	private static final Long2ObjectMap<SeamSection> handover = new Long2ObjectOpenHashMap<>();
	private static final Long2ObjectMap<SeamSection> adopted = new Long2ObjectOpenHashMap<>();
	/** A kept mesh that finds no new owner in this many frames is released. */
	private static final int KEEP_FRAMES = 200;
	private static int frame;
	private static int lastCameraFace = -1;
	/**
	 * Vanilla sections that took a seam mesh, with the frame when they took it. Vanilla adds a section to its
	 * visible list a few frames after its chunk arrives, so the seam pass draws it until then.
	 */
	private static final Map<SectionRenderDispatcher.RenderSection, Integer> bridging = new IdentityHashMap<>();
	private static @Nullable SectionRenderDispatcher dispatcher;
	/** The seam draws of the current frame, one per neighbor face that has sections. */
	public static final List<ChunkSectionsToRender> frameDraws = new ArrayList<>();

	/** The motion into the camera frame of each seam block entity state of the current frame. */
	private static final Map<BlockEntityRenderState, int[]> blockEntityMotions = new IdentityHashMap<>();
	/** The motion into the camera frame of each entity state of another face region in the current frame. */
	private static final Map<EntityRenderState, int[]> entityMotions = new IdentityHashMap<>();

	private SeamRenderer() {}

	public static void onChunkLoaded(ChunkPos pos) {
		chunks.add(pos.pack());
		pendingChunks.add(pos.pack());
		// The border faces of a neighbor chunk were built against a missing chunk.
		markChunkDirty(pos.x() - 1, pos.z());
		markChunkDirty(pos.x() + 1, pos.z());
		markChunkDirty(pos.x(), pos.z() - 1);
		markChunkDirty(pos.x(), pos.z() + 1);
	}

	/**
	 * Marks the sections across the seams of a chunk dirty, in every face that touches it. Those sections read
	 * the blocks of this chunk at their wedge boundary, and may have been built before it arrived.
	 */
	public static void markAcrossSeamsDirty(ChunkPos pos) {
		SeamLayout layout = SeamClient.layout;
		ClientLevel level = Minecraft.getInstance().level;
		if (layout == null || level == null || !layout.inRegion(pos.getMiddleBlockX(), pos.getMiddleBlockZ())) {
			return;
		}
		int offset = layout.offsetX(layout.faceIndexAt(pos.getMiddleBlockX()));
		int[] owner = new int[3];
		LongOpenHashSet targets = new LongOpenHashSet();
		for (int x = pos.getMinBlockX(); x <= pos.getMaxBlockX(); x++) {
			for (int z = pos.getMinBlockZ(); z <= pos.getMaxBlockZ(); z++) {
				// The first block under the wedge boundary of the column, which belongs to the neighbor face.
				int y = layout.centerY + Math.max(Math.abs(x - offset), Math.abs(z)) - 1;
				if (y >= level.getMinY() && y <= level.getMaxY() && layout.ownerStorage(x, y, z, owner)) {
					targets.add(SectionPos.asLong(SectionPos.blockToSectionCoord(owner[0]), SectionPos.blockToSectionCoord(owner[1]), SectionPos.blockToSectionCoord(owner[2])));
				}
			}
		}
		LongIterator iterator = targets.iterator();
		while (iterator.hasNext()) {
			long node = iterator.nextLong();
			Minecraft.getInstance().levelExtractor.setSectionDirtyWithNeighbors(SectionPos.x(node), SectionPos.y(node), SectionPos.z(node));
		}
	}

	private static boolean markingAcross;

	/**
	 * Marks the sections across the seam of a dirty section dirty too, because they mesh against its blocks and
	 * light. Only a section that its wedge boundary passes through has such sections. The part of the section
	 * outside its wedge, grown by one block, maps to a box in each owner face. That box can span three sections per
	 * axis, so every section in the range of the corners is marked, not only the corner sections. The guard stops
	 * the marks from bouncing back across the seam.
	 */
	public static void markAcrossSeamDirty(int sectionX, int sectionY, int sectionZ) {
		SeamLayout layout = SeamClient.layout;
		if (layout == null || markingAcross) {
			return;
		}
		int minX = SectionPos.sectionToBlockCoord(sectionX) - 1;
		int minY = SectionPos.sectionToBlockCoord(sectionY) - 1;
		int minZ = SectionPos.sectionToBlockCoord(sectionZ) - 1;
		int[] owner = new int[3];
		// Per owner face: min and max section coordinates on each axis.
		int[][] ranges = new int[SeamLayout.FACES.length][];
		int outsideCorners = 0;
		for (int corner = 0; corner < 8; corner++) {
			int x = minX + ((corner & 1) != 0 ? 17 : 0);
			int y = minY + ((corner & 2) != 0 ? 17 : 0);
			int z = minZ + ((corner & 4) != 0 ? 17 : 0);
			if (!layout.insideWedge(x, y, z) && layout.ownerStorage(x, y, z, owner)) {
				outsideCorners++;
				int face = layout.faceIndexAt(owner[0]);
				int[] range = ranges[face];
				if (range == null) {
					range = ranges[face] = new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
				}
				for (int axis = 0; axis < 3; axis++) {
					int section = SectionPos.blockToSectionCoord(owner[axis]);
					range[axis] = Math.min(range[axis], section);
					range[axis + 3] = Math.max(range[axis + 3], section);
				}
			}
		}
		if (outsideCorners == 0 || outsideCorners == 8 && !layout.insideWedge(minX + 9, minY + 9, minZ + 9)) {
			// No corner is across a seam, or the whole section is outside its wedge and draws nothing.
			return;
		}
		markingAcross = true;
		try {
			for (int[] range : ranges) {
				if (range != null) {
					Minecraft.getInstance().levelExtractor.setSectionRangeDirty(range[0], range[1], range[2], range[3], range[4], range[5]);
				}
			}
		} finally {
			markingAcross = false;
		}
	}

	public static void onChunkUnloaded(ChunkPos pos) {
		chunks.remove(pos.pack());
		pendingChunks.remove(pos.pack());
		for (long node : sectionNodes(pos.x(), pos.z())) {
			SeamSection section = sections.remove(node);
			if (section != null) {
				section.reset();
				dirtySections.remove(node);
			}
		}
	}

	/**
	 * The section positions of a chunk column. A crossing moves thousands of chunks at once, so a chunk looks up its
	 * sections by position, not by a scan of all sections.
	 */
	private static long[] sectionNodes(int chunkX, int chunkZ) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return new long[0];
		}
		long[] nodes = new long[level.getSectionsCount()];
		for (int i = 0; i < nodes.length; i++) {
			nodes[i] = SectionPos.asLong(chunkX, level.getMinSectionY() + i, chunkZ);
		}
		return nodes;
	}

	/** The chunk is now in the vanilla view area. Its seam meshes show there until vanilla compiles its own. */
	public static void onChunkMovedToVanilla(ChunkPos pos) {
		chunks.remove(pos.pack());
		pendingChunks.remove(pos.pack());
		ViewArea viewArea = Minecraft.getInstance().levelRenderer.viewArea();
		for (long node : sectionNodes(pos.x(), pos.z())) {
			SeamSection section = sections.remove(node);
			if (section == null) {
				continue;
			}
			dirtySections.remove(node);
			SectionRenderDispatcher.RenderSection target = viewArea == null ? null : viewArea.getRenderSectionAt(section.storageOrigin);
			if (!(section.getSectionMesh() instanceof CompiledSectionMesh)) {
				section.reset();
			} else if (target != null && target.getSectionNode() == node && target.getSectionMesh() == CompiledSectionMesh.UNCOMPILED) {
				moveMesh(section, target);
				bridging.put(target, frame);
				section.reset();
			} else {
				keep(handover, node, section);
			}
		}
	}

	/**
	 * The chunk left the vanilla view area for the seam store. With a large view distance, many vanilla sections keep
	 * their position across a crossing, so their meshes stay for the seam renderer here, not in {@link #onVanillaSectionMoving}.
	 */
	public static void onChunkMovedToSeam(ChunkPos pos) {
		ViewArea viewArea = Minecraft.getInstance().levelRenderer.viewArea();
		if (viewArea == null) {
			return;
		}
		for (int y = viewArea.minSectionY(); y < viewArea.minSectionY() + viewArea.sectionCount(); y++) {
			long node = SectionPos.asLong(pos.x(), y, pos.z());
			SectionRenderDispatcher.RenderSection section = viewArea.getRenderSectionAt(SectionPos.of(node).origin());
			if (section != null && section.getSectionNode() == node && section.getSectionMesh() instanceof CompiledSectionMesh && !sections.containsKey(node)) {
				SeamSection holder = new SeamSection(Minecraft.getInstance().levelRenderer.sectionRenderDispatcher(), node);
				moveMesh(section, holder);
				keep(adopted, node, holder);
			}
		}
	}

	/**
	 * When the two faces are next to each other in storage, the view squares before and after a crossing overlap.
	 * Vanilla sections of the old face in the overlap do not move, and vanilla stops drawing them at once, but their
	 * chunks reach the seam store only when the server moves the chunk center a few frames later. Their meshes go to
	 * the seam renderer now, so that the old face does not disappear for those frames.
	 */
	private static void adoptOtherFaceSections(@Nullable ViewArea viewArea, SeamLayout layout, int cameraFace) {
		if (viewArea == null) {
			return;
		}
		SectionPos center = viewArea.getCameraSectionPos();
		int radius = viewArea.getViewDistance();
		for (int x = center.x() - radius; x <= center.x() + radius; x++) {
			if (layout.faceIndexAt(SectionPos.sectionToBlockCoord(x)) == cameraFace) {
				continue;
			}
			for (int z = center.z() - radius; z <= center.z() + radius; z++) {
				onChunkMovedToSeam(new ChunkPos(x, z));
			}
		}
	}

	/**
	 * Called before a vanilla section moves to {@code newNode}. When the section leaves the face region of its new
	 * position, its chunk is about to become a seam chunk, so the mesh stays for the seam renderer.
	 */
	public static void onVanillaSectionMoving(SectionRenderDispatcher.RenderSection section, long newNode) {
		SeamLayout layout = SeamClient.layout;
		long oldNode = section.getSectionNode();
		if (layout == null || section instanceof SeamSection || !(section.getSectionMesh() instanceof CompiledSectionMesh)
			|| layout.faceIndexAt(SectionPos.sectionToBlockCoord(SectionPos.x(oldNode))) == layout.faceIndexAt(SectionPos.sectionToBlockCoord(SectionPos.x(newNode)))) {
			return;
		}
		SeamSection seam = sections.get(oldNode);
		if (seam != null) {
			if (seam.getSectionMesh() == CompiledSectionMesh.UNCOMPILED) {
				moveMesh(section, seam);
			}
			return;
		}
		SeamSection holder = new SeamSection(Minecraft.getInstance().levelRenderer.sectionRenderDispatcher(), oldNode);
		moveMesh(section, holder);
		keep(adopted, oldNode, holder);
	}

	public static void onVanillaSectionMoved(SectionRenderDispatcher.RenderSection section) {
		if (section instanceof SeamSection || handover.isEmpty()) {
			return;
		}
		SeamSection kept = handover.remove(section.getSectionNode());
		if (kept != null) {
			if (section.getSectionMesh() == CompiledSectionMesh.UNCOMPILED) {
				moveMesh(kept, section);
				bridging.put(section, frame);
			}
			kept.reset();
		}
	}

	/** Moves the mesh with its GPU buffers, which the dispatcher keys by mesh. The target shows it at once, with no fade-in. */
	private static void moveMesh(SectionRenderDispatcher.RenderSection from, SectionRenderDispatcher.RenderSection to) {
		((RenderSectionAccessor) to).cubeworld$setSectionMesh(from.sectionMesh.getAndSet(CompiledSectionMesh.UNCOMPILED));
		((RenderSectionAccessor) to).cubeworld$setUploadedTime(1L);
	}

	private static void keep(Long2ObjectMap<SeamSection> store, long node, SeamSection section) {
		section.keptSince = frame;
		SeamSection previous = store.put(node, section);
		if (previous != null && previous != section) {
			previous.reset();
		}
	}

	private static void releaseKept(boolean all) {
		for (Long2ObjectMap<SeamSection> store : List.of(handover, adopted)) {
			var iterator = store.values().iterator();
			while (iterator.hasNext()) {
				SeamSection section = iterator.next();
				if (all || frame - section.keptSince > KEEP_FRAMES) {
					section.reset();
					iterator.remove();
				}
			}
		}
	}

	public static void markDirty(int sectionX, int sectionY, int sectionZ) {
		long node = SectionPos.asLong(sectionX, sectionY, sectionZ);
		if (sections.containsKey(node)) {
			dirtySections.add(node);
		} else if (chunks.contains(ChunkPos.pack(sectionX, sectionZ))) {
			// A block placed in an empty section gives the section its first geometry.
			pendingChunks.add(ChunkPos.pack(sectionX, sectionZ));
		}
	}

	private static void markChunkDirty(int chunkX, int chunkZ) {
		if (chunks.contains(ChunkPos.pack(chunkX, chunkZ))) {
			for (long node : sectionNodes(chunkX, chunkZ)) {
				if (sections.containsKey(node)) {
					dirtySections.add(node);
				}
			}
		}
	}

	public static void reset() {
		for (SeamSection section : sections.values()) {
			section.reset();
		}
		sections.clear();
		dirtySections.clear();
		releaseKept(true);
		bridging.clear();
		pendingChunks.clear();
		pendingChunks.addAll(chunks);
		frameDraws.clear();
		dispatcher = null;
	}

	/** Forgets all chunks. Called when the client leaves the level. */
	public static void clear() {
		reset();
		chunks.clear();
		pendingChunks.clear();
	}

	/**
	 * Compiles waiting sections, then builds one draw per neighbor face with {@code prepare}: the vanilla method
	 * that turns {@code LevelRenderer.visibleSections} into a draw for a model-view matrix.
	 */
	public static void prepareFrame(
		LevelRenderer levelRenderer, Matrix4fc modelView, BlockPos cameraBlock, Vec3 cameraPos, Preparer prepare
	) {
		frameDraws.clear();
		SeamLayout layout = SeamClient.layout;
		ClientLevel level = Minecraft.getInstance().level;
		SectionRenderDispatcher current = levelRenderer.sectionRenderDispatcher();
		if (layout == null || level == null || current == null) {
			return;
		}
		if (current != dispatcher) {
			reset();
			dispatcher = current;
		}
		frame++;
		releaseKept(false);
		createPendingSections(level, current);
		compileDirtySections(level);
		int cameraFace = layout.faceIndexAt(cameraBlock.getX());
		if (cameraFace != lastCameraFace) {
			lastCameraFace = cameraFace;
			adoptOtherFaceSections(levelRenderer.viewArea(), layout, cameraFace);
		}
		if (sections.isEmpty() && adopted.isEmpty() && bridging.isEmpty()) {
			return;
		}

		ObjectArrayList<SectionRenderDispatcher.RenderSection> visible = levelRenderer.visibleSections();
		List<SectionRenderDispatcher.RenderSection> vanillaVisible = new ArrayList<>(visible);
		if (!bridging.isEmpty()) {
			for (SectionRenderDispatcher.RenderSection section : vanillaVisible) {
				bridging.remove(section);
			}
			// A crossing back to another face leaves sections that only draw right on the face they were bridged on.
			bridging.entrySet().removeIf(entry -> frame - entry.getValue() > KEEP_FRAMES
				|| layout.faceIndexAt(SectionPos.sectionToBlockCoord(SectionPos.x(entry.getKey().getSectionNode()))) != cameraFace);
		}
		try {
			// Sections of the camera face show here only for the few frames after a crossing, before the chunks of the
			// new face reach the vanilla view area. Kept meshes of the old face show until their chunks arrive.
			for (int face = 0; face < SeamLayout.FACES.length; face++) {
				// The opposite face shares no seam with the camera face. Its sections can be left over from a quick
				// second crossing, and its motion would draw them in the sky.
				if (layout.motion(face, cameraFace)[4] == -1) {
					continue;
				}
				visible.clear();
				FaceTransform transform = FaceTransform.of(layout, cameraFace, face, cameraBlock, cameraPos);
				if (face == cameraFace) {
					// Vanilla sections draw at their own origin, which is right for the camera face.
					visible.addAll(bridging.keySet());
				}
				for (Long2ObjectMap<SeamSection> store : List.of(sections, adopted)) {
					for (SeamSection section : store.values()) {
						if (layout.faceIndexAt(section.storageOrigin.getX()) == face) {
							section.virtualOrigin = section.storageOrigin.subtract(transform.cameraInFace).offset(cameraBlock);
							visible.add(section);
						}
					}
				}
				if (!visible.isEmpty()) {
					frameDraws.add(prepare.prepare(new Matrix4f(modelView).mul(transform.matrix)));
				}
			}
		} finally {
			visible.clear();
			visible.addAll(vanillaVisible);
		}
	}

	/**
	 * Adds the block entities of seam sections, such as chests and signs, which vanilla draws apart from the
	 * terrain. Vanilla culls a block entity by its distance to the dispatcher camera, so the dispatcher gets the
	 * camera in the frame of each face for its block entities.
	 */
	public static void extractBlockEntities(BlockEntityRenderDispatcher dispatcher, Vec3 cameraPos, float partialTicks, List<BlockEntityRenderState> out) {
		blockEntityMotions.clear();
		SeamLayout layout = SeamClient.layout;
		if (layout == null || sections.isEmpty()) {
			return;
		}
		int cameraFace = layout.faceIndexAt(Mth.floor(cameraPos.x));
		try {
			for (int face = 0; face < SeamLayout.FACES.length; face++) {
				if (face == cameraFace) {
					continue;
				}
				int[] toCamera = layout.motion(face, cameraFace);
				dispatcher.prepare(SeamInteraction.move(layout.motion(cameraFace, face), cameraPos));
				for (SeamSection section : sections.values()) {
					if (layout.faceIndexAt(section.storageOrigin.getX()) != face) {
						continue;
					}
					for (BlockEntity blockEntity : section.getSectionMesh().getRenderableBlockEntities()) {
						BlockEntityRenderState state = dispatcher.tryExtractRenderState(blockEntity, partialTicks, null, false);
						if (state != null) {
							out.add(state);
							blockEntityMotions.put(state, toCamera);
						}
					}
				}
			}
		} finally {
			dispatcher.prepare(cameraPos);
		}
	}

	/**
	 * Adds the entities of other face regions, such as item frames and mobs that the server sends across seams.
	 * Vanilla culls them, because the frustum and the distance check use the stored position. Here the distance
	 * check uses the camera moved into the frame of the entity, and the frustum is skipped.
	 */
	public static void extractEntities(ClientLevel level, EntityRenderDispatcher dispatcher, Vec3 cameraPos, float partialTicks, List<EntityRenderState> out) {
		entityMotions.clear();
		SeamLayout layout = SeamClient.layout;
		int cameraFace = layout == null ? -1 : SeamInteraction.regionFace(layout, cameraPos.x, cameraPos.z);
		if (cameraFace < 0) {
			return;
		}
		for (Entity entity : level.entitiesForRendering()) {
			int face = SeamInteraction.regionFace(layout, entity.getX(), entity.getZ());
			if (face < 0 || face == cameraFace) {
				continue;
			}
			Vec3 camera = SeamInteraction.move(layout.motion(cameraFace, face), cameraPos);
			if (entity.shouldRender(camera.x, camera.y, camera.z)) {
				EntityRenderState state = dispatcher.extractEntity(entity, partialTicks);
				out.add(state);
				entityMotions.put(state, layout.motion(face, cameraFace));
			}
		}
	}

	/** True for an entity that {@link #extractEntities} draws instead of vanilla. */
	public static boolean inOtherRegion(Entity entity, double cameraX, double cameraZ) {
		SeamLayout layout = SeamClient.layout;
		if (layout == null) {
			return false;
		}
		int cameraFace = SeamInteraction.regionFace(layout, cameraX, cameraZ);
		int face = SeamInteraction.regionFace(layout, entity.getX(), entity.getZ());
		return cameraFace >= 0 && face >= 0 && face != cameraFace;
	}

	public static int @Nullable [] entityMotion(EntityRenderState state) {
		return entityMotions.get(state);
	}

	/**
	 * Moves the pose from the frame of another face to the camera frame. With {@code faceShading}, the normals keep
	 * the frame of their own face, because the seam terrain keeps the shading that its own face gives it.
	 */
	public static void applyMotion(PoseStack poseStack, int[] m, Vec3 camera, boolean faceShading) {
		poseStack.translate(m[9] - camera.x, m[10] - camera.y, m[11] - camera.z);
		Quaternionf rotation = new Quaternionf().setFromNormalized(new Matrix3f(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]));
		poseStack.rotate(rotation);
		if (faceShading) {
			poseStack.last().normal().rotate(rotation.conjugate());
		}
	}

	/** The motion into the camera frame for a block entity state of a seam section, or null for a vanilla state. */
	public static int @Nullable [] blockEntityMotion(BlockEntityRenderState state) {
		return blockEntityMotions.get(state);
	}

	private static void createPendingSections(ClientLevel level, SectionRenderDispatcher current) {
		LongIterator iterator = pendingChunks.iterator();
		while (iterator.hasNext()) {
			long chunkKey = iterator.nextLong();
			iterator.remove();
			LevelChunk chunk = level.getChunkSource().getChunk(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey), false);
			if (chunk == null) {
				continue;
			}
			LevelChunkSection[] chunkSections = chunk.getSections();
			for (int index = 0; index < chunkSections.length; index++) {
				if (chunkSections[index].hasOnlyAir()) {
					continue;
				}
				long node = SectionPos.asLong(ChunkPos.getX(chunkKey), chunk.getSectionYFromSectionIndex(index), ChunkPos.getZ(chunkKey));
				if (!sections.containsKey(node)) {
					SeamSection kept = adopted.remove(node);
					sections.put(node, kept != null ? kept : new SeamSection(current, node));
				}
				dirtySections.add(node);
			}
		}
	}

	private static void compileDirtySections(ClientLevel level) {
		RenderRegionCache cache = new RenderRegionCache();
		for (int i = 0; i < COMPILES_PER_FRAME && !dirtySections.isEmpty(); i++) {
			long node = dirtySections.removeFirstLong();
			SeamSection section = sections.get(node);
			if (section != null) {
				section.compileAsync(cache.createRegion(level, node));
			}
		}
	}

	@FunctionalInterface
	public interface Preparer {
		ChunkSectionsToRender prepare(Matrix4fc modelView);
	}

	/** A section of a neighbor face. Vanilla reads its render origin as the section position in the shader. */
	private static final class SeamSection extends SectionRenderDispatcher.RenderSection {
		final BlockPos storageOrigin;
		volatile @Nullable BlockPos virtualOrigin;
		int keptSince;

		SeamSection(SectionRenderDispatcher dispatcher, long node) {
			dispatcher.super(0, node);
			this.storageOrigin = SectionPos.of(node).origin();
		}

		@Override
		public BlockPos getRenderOrigin() {
			BlockPos origin = this.virtualOrigin;
			return origin != null ? origin : super.getRenderOrigin();
		}
	}

	/**
	 * The motion {@code s_F = R s_G + t} from the storage frame of face G to the storage frame of face F, from
	 * {@link SeamLayout#motion}. The shader gets section positions relative to {@code cameraInFace}, the integer camera position in the frame
	 * of G, and {@code matrix} rotates them into the frame of F. Vanilla adds the fractional camera offset before
	 * the matrix, so the matrix also corrects that offset for the rotation.
	 */
	private record FaceTransform(BlockPos cameraInFace, Matrix4f matrix) {
		static FaceTransform of(SeamLayout layout, int faceF, int faceG, BlockPos cameraBlock, Vec3 cameraPos) {
			int[] r = layout.motion(faceG, faceF);
			int[] t = {r[9], r[10], r[11]};
			int[] relative = {cameraBlock.getX() - t[0], cameraBlock.getY() - t[1], cameraBlock.getZ() - t[2]};
			int[] camera = new int[3];
			for (int j = 0; j < 3; j++) {
				for (int i = 0; i < 3; i++) {
					camera[j] += r[i * 3 + j] * relative[i];
				}
			}

			Matrix4f rotation = new Matrix4f(
				r[0], r[3], r[6], 0,
				r[1], r[4], r[7], 0,
				r[2], r[5], r[8], 0,
				0, 0, 0, 1
			);
			float fx = (float) (cameraPos.x - cameraBlock.getX());
			float fy = (float) (cameraPos.y - cameraBlock.getY());
			float fz = (float) (cameraPos.z - cameraBlock.getZ());
			float rx = r[0] * fx + r[1] * fy + r[2] * fz;
			float ry = r[3] * fx + r[4] * fy + r[5] * fz;
			float rz = r[6] * fx + r[7] * fy + r[8] * fz;
			Matrix4f matrix = new Matrix4f().translation(rx - fx, ry - fy, rz - fz).mul(rotation);
			return new FaceTransform(new BlockPos(camera[0], camera[1], camera[2]), matrix);
		}
	}
}

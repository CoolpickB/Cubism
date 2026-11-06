package dev.cubism.client.render;

import dev.cubism.client.LoadTraceClient;
import dev.cubism.client.SeamClient;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.worldgen.SeamLayout;
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
import dev.cubism.client.mixin.LevelExtractorAccessor;
import dev.cubism.client.mixin.RenderSectionAccessor;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

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

	/**
	 * The section position each compiled mesh was made for. Weak keys, so a mesh leaves the map when it is released.
	 * Workers add entries while the render thread reads them.
	 */
	public static final Map<CompiledSectionMesh, Long> COMPILED_FOR = Collections.synchronizedMap(new WeakHashMap<>());

	public static void onChunkLoaded(ChunkPos pos) {
		if (SeamClient.SODIUM) {
			return;
		}
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
	private static final int[][] NO_RANGES = new int[0][];
	private static final Long2ObjectMap<int[][]> acrossRanges = new Long2ObjectOpenHashMap<>();

	/**
	 * Marks the sections across the seam of a dirty section dirty too, because they mesh against its blocks and
	 * light. Only a section that its wedge boundary passes through has such sections. The part of the section
	 * outside its wedge, grown by one block, maps to a box in each owner face. That box can span three sections per
	 * axis, so every section in its range is marked. The diagonal planes cut the part outside the wedge, so its
	 * extent comes from all blocks on the surface of the grown section, not from the corners alone: a part that holds
	 * only one corner still reaches along the edges from it, into other sections of the owner face. The guard stops
	 * the marks from bouncing back across the seam.
	 */
	public static void markAcrossSeamDirty(int sectionX, int sectionY, int sectionZ, boolean playerChanged) {
		SeamLayout layout = SeamClient.layout;
		if (layout == null || markingAcross) {
			return;
		}
		// Light updates mark the same sections many times while chunks load, and the ranges depend only on the
		// position of the section, so each section works them out once.
		int[][] ranges = acrossRanges.computeIfAbsent(SectionPos.asLong(sectionX, sectionY, sectionZ), node -> rangesAcross(layout, sectionX, sectionY, sectionZ));
		if (ranges.length == 0) {
			return;
		}
		markingAcross = true;
		try {
			LevelExtractorAccessor extractor = (LevelExtractorAccessor) Minecraft.getInstance().levelExtractor;
			for (int[] range : ranges) {
				if (range == null) {
					continue;
				}
				for (int x = range[0]; x <= range[3]; x++) {
					for (int y = range[1]; y <= range[4]; y++) {
						for (int z = range[2]; z <= range[5]; z++) {
							extractor.cubism$setSectionDirty(x, y, z, playerChanged);
						}
					}
				}
			}
		} finally {
			markingAcross = false;
		}
	}

	/** Per owner face, the min and max section coordinates on each axis, or no ranges for a section with none. */
	private static int[][] rangesAcross(SeamLayout layout, int sectionX, int sectionY, int sectionZ) {
		int minX = SectionPos.sectionToBlockCoord(sectionX) - 1;
		int minY = SectionPos.sectionToBlockCoord(sectionY) - 1;
		int minZ = SectionPos.sectionToBlockCoord(sectionZ) - 1;
		int[] owner = new int[3];
		int outsideCorners = 0;
		for (int corner = 0; corner < 8; corner++) {
			if (outside(layout, minX + ((corner & 1) != 0 ? 17 : 0), minY + ((corner & 2) != 0 ? 17 : 0), minZ + ((corner & 4) != 0 ? 17 : 0), owner)) {
				outsideCorners++;
			}
		}
		// The wedge and the box are convex, so with all corners inside the wedge the whole box is. With all corners
		// and the center outside, the whole section is outside its wedge and draws nothing.
		if (outsideCorners == 0 || outsideCorners == 8 && outside(layout, minX + 9, minY + 9, minZ + 9, owner)) {
			return NO_RANGES;
		}
		int[][] ranges = new int[SeamLayout.FACES.length][];
		for (int dx = 0; dx <= 17; dx++) {
			for (int dy = 0; dy <= 17; dy++) {
				boolean side = dx == 0 || dx == 17 || dy == 0 || dy == 17;
				for (int dz = 0; dz <= 17; dz += side ? 1 : 17) {
					if (!outside(layout, minX + dx, minY + dy, minZ + dz, owner)) {
						continue;
					}
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
		}
		return ranges;
	}

	/** True when a storage position is outside its wedge, with the position of the real block in {@code owner}. */
	private static boolean outside(SeamLayout layout, int x, int y, int z, int[] owner) {
		return layout.ownerStorage(x, y, z, owner) && (owner[0] != x || owner[1] != y || owner[2] != z);
	}

	public static void onChunkUnloaded(ChunkPos pos) {
		LoadTraceClient.count("seamChunk.unloaded");
		if (SeamClient.SODIUM) {
			return;
		}
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
		if (SeamClient.SODIUM) {
			return;
		}
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
				LoadTraceClient.count("toVanilla.noMesh");
				section.reset();
			} else if (target != null && target.getSectionNode() == node && target.getSectionMesh() == CompiledSectionMesh.UNCOMPILED) {
				LoadTraceClient.count("toVanilla.meshMoved");
				moveMesh(section, target);
				bridging.put(target, frame);
				section.reset();
			} else {
				LoadTraceClient.count("toVanilla.meshKept");
				keep(handover, node, section);
			}
		}
	}

	/**
	 * The chunk left the vanilla view area for the seam store. With a large view distance, many vanilla sections keep
	 * their position across a crossing, so their meshes stay for the seam renderer here, not in {@link #onVanillaSectionMoving}.
	 */
	public static void onChunkMovedToSeam(ChunkPos pos) {
		if (SeamClient.SODIUM) {
			return;
		}
		ViewArea viewArea = Minecraft.getInstance().levelRenderer.viewArea();
		if (viewArea == null) {
			return;
		}
		for (int y = viewArea.minSectionY(); y < viewArea.minSectionY() + viewArea.sectionCount(); y++) {
			long node = SectionPos.asLong(pos.x(), y, pos.z());
			SectionRenderDispatcher.RenderSection section = viewArea.getRenderSectionAt(SectionPos.of(node).origin());
			if (section != null && section.getSectionNode() == node && section.getSectionMesh() instanceof CompiledSectionMesh && !sections.containsKey(node)) {
				SeamSection holder = new SeamSection(Minecraft.getInstance().levelRenderer.sectionRenderDispatcher(), node);
				LoadTraceClient.count("toSeam.meshAdopted");
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
	 * position, or its face is no longer the camera face, its chunk is about to become a seam chunk, so the mesh stays
	 * for the seam renderer. The second case is a crossing where the view square moves along z in storage: from WEST
	 * to SOUTH, the WEST sections at the shared edge wrap to new z positions in the WEST region.
	 */
	public static void onVanillaSectionMoving(SectionRenderDispatcher.RenderSection section, long newNode) {
		SeamLayout layout = SeamClient.layout;
		if (layout == null || section instanceof SeamSection || !(section.getSectionMesh() instanceof CompiledSectionMesh)) {
			return;
		}
		long oldNode = section.getSectionNode();
		int oldFace = layout.faceIndexAt(SectionPos.sectionToBlockCoord(SectionPos.x(oldNode)));
		if (oldFace == layout.faceIndexAt(SectionPos.sectionToBlockCoord(SectionPos.x(newNode)))
			&& oldFace == layout.faceIndexAt(Minecraft.getInstance().gameRenderer.mainCamera().blockPosition().getX())) {
			return;
		}
		SeamSection seam = sections.get(oldNode);
		if (seam != null) {
			if (seam.getSectionMesh() == CompiledSectionMesh.UNCOMPILED) {
				moveMesh(section, seam);
			}
			return;
		}
		LoadTraceClient.count("vanillaMoving.meshAdopted");
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
				LoadTraceClient.count("vanillaMoved.handoverUsed");
				moveMesh(kept, section);
				bridging.put(section, frame);
			}
			kept.reset();
		}
	}

	/** Moves the mesh with its GPU buffers, which the dispatcher keys by mesh. The target shows it at once, with no fade-in. */
	private static void moveMesh(SectionRenderDispatcher.RenderSection from, SectionRenderDispatcher.RenderSection to) {
		((RenderSectionAccessor) to).cubism$setSectionMesh(from.sectionMesh.getAndSet(CompiledSectionMesh.UNCOMPILED));
		((RenderSectionAccessor) to).cubism$setUploadedTime(1L);
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
					LoadTraceClient.count(all ? "released.all" : "released.timeout");
					section.reset();
					iterator.remove();
				}
			}
		}
	}

	public static void markDirty(int sectionX, int sectionY, int sectionZ, boolean urgent) {
		if (SeamClient.SODIUM) {
			return;
		}
		long node = SectionPos.asLong(sectionX, sectionY, sectionZ);
		if (sections.containsKey(node)) {
			// While chunks load, light updates keep hundreds of sections in the queue. A changed block would wait
			// behind them, so its section goes first.
			if (urgent) {
				dirtySections.addAndMoveToFirst(node);
			} else {
				dirtySections.add(node);
			}
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
		acrossRanges.clear();
		chunks.clear();
		pendingChunks.clear();
	}

	/**
	 * Compiles waiting sections, then builds one draw per neighbor face with {@code prepare}: the vanilla method
	 * that turns {@code LevelRenderer.visibleSections} into a draw for a model-view matrix. Sections that show
	 * outside {@code frustum}, which is in the frame of the camera face, are not drawn.
	 */
	public static void prepareFrame(
		LevelRenderer levelRenderer, Matrix4fc modelView, BlockPos cameraBlock, Vec3 cameraPos, Frustum frustum, Preparer prepare
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
				int[] shown = layout.motion(face, cameraFace);
				if (shown[4] == -1) {
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
						if (layout.faceIndexAt(section.storageOrigin.getX()) != face) {
							continue;
						}
						AABB box = shownBox(shown, section.storageOrigin);
						if (frustum.isVisible(box) && (store == adopted || face == cameraFace || !groundMissing(level, layout, cameraFace, box))) {
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
	 * Vanilla draws its sections at their storage position, which is right only in the camera face. After a crossing,
	 * the chunks of the old face stay in the vanilla store until the server moves the chunk center. A block or light
	 * update in those frames compiles their sections again, and vanilla drew them floating in the new face.
	 */
	public static void hideOtherFacesFromVanilla(LevelRenderer levelRenderer, Vec3 cameraPos) {
		SeamLayout layout = SeamClient.layout;
		if (layout != null) {
			int cameraFace = layout.faceIndexAt(Mth.floor(cameraPos.x));
			levelRenderer.visibleSections().removeIf(
				section -> layout.faceIndexAt(SectionPos.sectionToBlockCoord(SectionPos.x(section.getSectionNode()))) != cameraFace
			);
		}
	}

	/**
	 * True when a section of a neighbor face shows in a chunk column of the camera face that the server sends but the
	 * client does not have yet. The neighbor terrain shows below the ground of the camera face near a seam, and while
	 * that ground loads, it showed through the hole, or floated with nothing around it.
	 */
	private static boolean groundMissing(ClientLevel level, SeamLayout layout, int cameraFace, AABB box) {
		// The view radius of the player on the server is its requested render distance, capped by the server. One
		// chunk less leaves out the edge of the square, where the chunks may never come.
		int radius = Math.clamp(Minecraft.getInstance().options.renderDistance().get(), 2, Math.max(2, SeamClient.serverViewRadius)) - 1;
		int minX = SectionPos.blockToSectionCoord(Mth.floor(box.minX + 0.5));
		int maxX = SectionPos.blockToSectionCoord(Mth.floor(box.maxX - 0.5));
		int minZ = SectionPos.blockToSectionCoord(Mth.floor(box.minZ + 0.5));
		int maxZ = SectionPos.blockToSectionCoord(Mth.floor(box.maxZ - 0.5));
		for (int x = minX; x <= maxX; x++) {
			if (layout.faceIndexAt(SectionPos.sectionToBlockCoord(x, 8)) != cameraFace) {
				continue;
			}
			for (int z = minZ; z <= maxZ; z++) {
				if (ChunkTrackingView.isInViewDistance(SeamClient.viewCenterX, SeamClient.viewCenterZ, radius, x, z)
					&& level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) == null) {
					return true;
				}
			}
		}
		return false;
	}

	/** The box of a section where it shows in the camera face, for the motion {@code m} into that face. */
	private static AABB shownBox(int[] m, BlockPos origin) {
		Vec3 min = Vec3.atLowerCornerOf(origin);
		return new AABB(SeamInteraction.move(m, min), SeamInteraction.move(m, min.add(16.0, 16.0, 16.0)));
	}

	/**
	 * Adds the block entities of seam sections, such as chests and signs, which vanilla draws apart from the
	 * terrain. Vanilla culls a block entity by its distance to the dispatcher camera, so the dispatcher gets the
	 * camera in the frame of each face for its block entities. The kept and bridged meshes of a crossing bring their
	 * block entities too, or banners and signs vanish while the terrain around them shows.
	 */
	public static void extractBlockEntities(BlockEntityRenderDispatcher dispatcher, Vec3 cameraPos, float partialTicks, List<BlockEntityRenderState> out) {
		if (SeamClient.SODIUM) {
			return;
		}
		blockEntityMotions.clear();
		SeamLayout layout = SeamClient.layout;
		if (layout == null || sections.isEmpty() && adopted.isEmpty() && bridging.isEmpty()) {
			return;
		}
		int cameraFace = layout.faceIndexAt(Mth.floor(cameraPos.x));
		for (SectionRenderDispatcher.RenderSection section : bridging.keySet()) {
			for (BlockEntity blockEntity : section.getSectionMesh().getRenderableBlockEntities()) {
				BlockEntityRenderState state = dispatcher.tryExtractRenderState(blockEntity, partialTicks, null, false);
				if (state != null) {
					out.add(state);
				}
			}
		}
		try {
			for (int face = 0; face < SeamLayout.FACES.length; face++) {
				if (face == cameraFace) {
					continue;
				}
				int[] toCamera = layout.motion(face, cameraFace);
				dispatcher.prepare(SeamInteraction.move(layout.motion(cameraFace, face), cameraPos));
				for (Long2ObjectMap<SeamSection> store : List.of(sections, adopted)) {
					for (SeamSection section : store.values()) {
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
				if (SeamClient.ENTITY_CULLING) {
					dev.cubism.client.compat.entityculling.EntityCullingLink.shown(entity);
				}
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
	public static Quaternionf motionRotation(int[] m) {
		return new Quaternionf().setFromNormalized(new Matrix3f(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]));
	}

	public static void applyMotion(PoseStack poseStack, int[] m, Vec3 camera, boolean faceShading) {
		poseStack.translate(m[9] - camera.x, m[10] - camera.y, m[11] - camera.z);
		Quaternionf rotation = motionRotation(m);
		poseStack.rotate(rotation);
		if (faceShading) {
			poseStack.last().normal().rotate(rotation.conjugate());
		}
	}

	/**
	 * Cancels the turn of the level lights for an entity or block entity, so it keeps the shade that the up of its own
	 * face gives it, as terrain does. The lights turn with the up that the camera shows, see {@link SeamClient#lightFrame},
	 * which the held item needs. Right after the camera crossed a seam, that up is still the up of the old face, and the
	 * entities of the new face were lit from the side until the turn eased out.
	 */
	public static void keepOwnLight(PoseStack poseStack) {
		if (SeamClient.layout != null) {
			poseStack.last().normal().rotateLocal(SeamClient.lightFrame);
		}
	}

	/** Remembers the motion into the camera frame for a block entity state that Sodium extracted from a seam section. */
	public static void setBlockEntityMotion(BlockEntityRenderState state, int[] motion) {
		blockEntityMotions.put(state, motion);
	}

	public static void clearBlockEntityMotions() {
		blockEntityMotions.clear();
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
					LoadTraceClient.count(kept != null ? "seamSection.adoptedUsed" : "seamSection.new");
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
				LoadTraceClient.count("compile.seam");
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

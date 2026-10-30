package dev.cubeworld.client.render;

import dev.cubeworld.client.SeamClient;
import dev.cubeworld.worldgen.SeamLayout;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

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
	private static @Nullable SectionRenderDispatcher dispatcher;
	/** The seam draws of the current frame, one per neighbor face that has sections. */
	public static final List<ChunkSectionsToRender> frameDraws = new ArrayList<>();

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

	public static void onChunkUnloaded(ChunkPos pos) {
		chunks.remove(pos.pack());
		pendingChunks.remove(pos.pack());
		var iterator = sections.long2ObjectEntrySet().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			if (SectionPos.x(entry.getLongKey()) == pos.x() && SectionPos.z(entry.getLongKey()) == pos.z()) {
				entry.getValue().reset();
				dirtySections.remove(entry.getLongKey());
				iterator.remove();
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
			for (var entry : sections.long2ObjectEntrySet()) {
				if (SectionPos.x(entry.getLongKey()) == chunkX && SectionPos.z(entry.getLongKey()) == chunkZ) {
					dirtySections.add(entry.getLongKey());
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
		createPendingSections(level, current);
		compileDirtySections(level);
		if (sections.isEmpty()) {
			return;
		}

		int cameraFace = layout.faceIndexAt(cameraBlock.getX());
		ObjectArrayList<SectionRenderDispatcher.RenderSection> visible = levelRenderer.visibleSections();
		List<SectionRenderDispatcher.RenderSection> vanillaVisible = new ArrayList<>(visible);
		try {
			for (int face = 0; face < SeamLayout.FACES.length; face++) {
				if (face == cameraFace) {
					continue;
				}
				visible.clear();
				FaceTransform transform = FaceTransform.of(layout, cameraFace, face, cameraBlock, cameraPos);
				for (SeamSection section : sections.values()) {
					if (layout.faceIndexAt(section.storageOrigin.getX()) == face) {
						section.virtualOrigin = section.storageOrigin.subtract(transform.cameraInFace).offset(cameraBlock);
						visible.add(section);
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
					sections.put(node, new SeamSection(current, node));
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
	 * The motion {@code s_F = R s_G + t} from the storage frame of face G to the storage frame of face F.
	 * The shader gets section positions relative to {@code cameraInFace}, the integer camera position in the frame
	 * of G, and {@code matrix} rotates them into the frame of F. Vanilla adds the fractional camera offset before
	 * the matrix, so the matrix also corrects that offset for the rotation.
	 */
	private record FaceTransform(BlockPos cameraInFace, Matrix4f matrix) {
		static FaceTransform of(SeamLayout layout, int faceF, int faceG, BlockPos cameraBlock, Vec3 cameraPos) {
			int[] a = layout.rotation(faceF);
			int[] b = layout.rotation(faceG);
			int[] r = new int[9];
			for (int i = 0; i < 3; i++) {
				for (int j = 0; j < 3; j++) {
					int sum = 0;
					for (int k = 0; k < 3; k++) {
						sum += a[k * 3 + i] * b[k * 3 + j];
					}
					r[i * 3 + j] = sum;
				}
			}
			int[] baseG = {layout.offsetX(faceG), layout.centerY, 0};
			int[] t = {layout.offsetX(faceF), layout.centerY, 0};
			for (int i = 0; i < 3; i++) {
				for (int j = 0; j < 3; j++) {
					t[i] -= r[i * 3 + j] * baseG[j];
				}
			}
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

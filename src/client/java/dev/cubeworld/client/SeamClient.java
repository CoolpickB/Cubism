package dev.cubeworld.client;

import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.core.SectionPos;
import org.jspecify.annotations.Nullable;

public final class SeamClient {
	/** The seam layout of the level the client plays in, or null when the level has no seams. */
	public static volatile @Nullable SeamLayout layout;

	private SeamClient() {}

	/**
	 * True when a chunk belongs in the seam store, not in the vanilla store: it is outside the square around the
	 * center, or in the face region of another face. A large view distance makes the square reach into a neighbor
	 * region, and vanilla would draw those chunks flat at their storage position.
	 */
	public static boolean isSeamChunk(int chunkX, int chunkZ, int centerX, int centerZ, int radius) {
		SeamLayout layout = SeamClient.layout;
		return layout != null && (Math.abs(chunkX - centerX) > radius || Math.abs(chunkZ - centerZ) > radius
			|| layout.faceIndexAt(SectionPos.sectionToBlockCoord(chunkX, 8)) != layout.faceIndexAt(SectionPos.sectionToBlockCoord(centerX, 8)));
	}
}

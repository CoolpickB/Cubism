package dev.cubism.compat.voxyworldgenv3;

import dev.cubism.worldgen.SeamLayout;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

/**
 * Where Voxygen scans in a cube world. The chunks of the cube are the six face regions, which form one rectangle along
 * +X in storage. Voxygen scans a circle around the player's chunk, and in storage that circle holds the player's own
 * face and, in space, nothing. It scans the middle of the row of faces instead, and counts only the chunks of the rectangle.
 */
public final class CubeScan {
	private CubeScan() {
	}

	/** The middle of the row of faces for a cube level, or the fallback for any other level. */
	public static ChunkPos center(ServerLevel level, ChunkPos fallback) {
		SeamLayout layout = SeamLayout.of(level);
		return layout == null ? fallback : new ChunkPos(Math.floorDiv(layout.offsetX(SeamLayout.FACES.length - 1) / 2, 16), 0);
	}

	/** True for a chunk of a cube level that is outside the six face regions. */
	public static boolean outside(@Nullable ServerLevel level, int chunkX, int chunkZ) {
		SeamLayout layout = level == null ? null : SeamLayout.of(level);
		if (layout == null) {
			return false;
		}
		int half = layout.spacing / 2;
		int side = layout.spacing / 16;
		int minX = Math.floorDiv(layout.offsetX(0) - half, 16);
		int maxX = Math.floorDiv(layout.offsetX(SeamLayout.FACES.length - 1) - half, 16) + side;
		int minZ = Math.floorDiv(-half, 16);
		return chunkX < minX || chunkX >= maxX || chunkZ < minZ || chunkZ >= minZ + side;
	}
}

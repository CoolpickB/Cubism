package dev.cubism.compat.voxyworldgenv3;

import dev.cubism.worldgen.SeamLayout;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

/**
 * Where Voxygen scans in a cube world. The chunks of the cube are the six face regions, which form one rectangle along
 * +X in storage. Voxygen scans a circle around the player's chunk, and in storage that circle holds the player's own
 * face and, in space, nothing. In space it scans around the face under the player instead, and it counts only the chunks of the rectangle.
 */
public final class CubeScan {
	private CubeScan() {
	}

	/**
	 * The chunk that Voxygen scans around for a player. On a face, and in any other level, it is the player's own chunk.
	 * In space it is the chunk of the face under the player, where the player's cube position is stored on its owner face.
	 */
	public static ChunkPos center(ServerPlayer player) {
		ChunkPos own = player.chunkPosition();
		SeamLayout layout = SeamLayout.of(player.level());
		if (layout == null || !layout.inSpace(player.getBlockX(), player.getBlockZ())) {
			return own;
		}
		int[] cube = new int[3];
		layout.toCube(SeamLayout.SPACE, player.getBlockX(), player.getBlockY(), player.getBlockZ(), cube);
		layout.toStorage(layout.ownerFace(cube[0], cube[1], cube[2]), cube[0], cube[1], cube[2], cube);
		return new ChunkPos(Math.floorDiv(cube[0], 16), Math.floorDiv(cube[2], 16));
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

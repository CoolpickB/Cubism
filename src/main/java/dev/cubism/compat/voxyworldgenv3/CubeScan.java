package dev.cubism.compat.voxyworldgenv3;

import dev.cubism.cube.SeamLayout;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Where Voxygen scans in a cube world. The chunks of the cube are the six face regions, which form one rectangle along
 * +X in storage. Voxygen scans a circle around the player's chunk, which in storage holds only the player's own face and
 * parts of its neighbors in the row. In a cube world the scan covers the whole rectangle instead, and skips the chunks
 * outside it. In space it is centered on the face under the player.
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

	/** True when the server runs a cube world. Voxygen then loads chunks through the server, so it reads pre-generated chunks from the save. */
	public static boolean cubeServer() {
		return SeamLayout.ofServerLevel(Level.OVERWORLD) != null;
	}

	/**
	 * The scan radius in chunks for a level: in a cube level, at least the farthest chunk of the face rectangle from any
	 * chunk in it, so that the scan reaches every face. Other levels keep the radius of the Voxygen settings.
	 */
	public static int radius(@Nullable ServerLevel level, int radius) {
		SeamLayout layout = level == null ? null : SeamLayout.of(level);
		if (layout == null) {
			return radius;
		}
		int side = layout.spacing / 16;
		int width = SeamLayout.FACES.length * side;
		return Math.max(radius, (int) Math.ceil(Math.sqrt((double) width * width + (double) side * side)) + 1);
	}
}

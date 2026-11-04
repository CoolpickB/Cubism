package dev.cubeworld.client.sodiumseam;

import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.minecraft.client.multiplayer.ClientLevel;

/** Calls into Sodium. Only reached when Sodium is installed. */
public final class SodiumLink {
	private SodiumLink() {}

	/**
	 * Tells the chunk tracker of Sodium that a seam chunk has its block data. Sodium learns this in the code that
	 * the seam chunk store replaces, and draws a chunk only after it has block data and light data.
	 */
	public static void blockData(ClientLevel level, int chunkX, int chunkZ) {
		ChunkTrackerHolder.get(level).onChunkStatusAdded(chunkX, chunkZ, 1);
	}

	/** True when Sodium draws the chunk: it and its eight neighbors have block data and light data. */
	public static boolean isReady(ClientLevel level, int chunkX, int chunkZ) {
		return ChunkTrackerHolder.get(level).getReadyChunks().contains(net.minecraft.world.level.ChunkPos.pack(chunkX, chunkZ));
	}
}

package dev.cubism.client;

import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/** Moves chunks in and out of the vanilla client chunk storage without unloading them. */
public interface SeamChunkStorageAccess {
	/**
	 * Takes the chunk out of a slot of the ring when it belongs in the seam store, see {@link SeamClient#isSeamChunk},
	 * and returns it. Returns null when the slot is empty or its chunk stays.
	 */
	@Nullable LevelChunk cubism$detachIfSeam(int index, int centerX, int centerZ, int radius);

	void cubism$attach(LevelChunk chunk);

	int cubism$slotCount();
}

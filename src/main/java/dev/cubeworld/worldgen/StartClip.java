package dev.cubeworld.worldgen;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * The part of the storage that a structure start may write. A start that came over an edge fold writes only the
 * strip that the fold fills, so that the blocks of the flat plane past the edge are not also written a second time
 * on the side of the face where they started.
 */
public interface StartClip {
	@Nullable BoundingBox cubeworld$clip();

	void cubeworld$setClip(@Nullable BoundingBox clip);
}

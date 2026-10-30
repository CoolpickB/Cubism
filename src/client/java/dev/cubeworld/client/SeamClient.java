package dev.cubeworld.client;

import dev.cubeworld.worldgen.SeamLayout;
import org.jspecify.annotations.Nullable;

public final class SeamClient {
	/** The seam layout of the level the client plays in, or null when the level has no seams. */
	public static volatile @Nullable SeamLayout layout;

	private SeamClient() {}
}

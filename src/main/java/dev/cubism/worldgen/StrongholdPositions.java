package dev.cubism.worldgen;

import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Where the strongholds of a cube world start. Vanilla puts them on rings around the world origin, and the origin of a
 * cube world is a point on one face. Here a stronghold is on a face of its own, and the faces come from the seed. The
 * count grows with the size of the cube, one at the smallest size and three at the largest. The start is near the
 * middle of the face, so that the whole stronghold fits in the wedge of the face at its depth.
 */
public final class StrongholdPositions {
	private static final int MIN_DIAMETER_CHUNKS = 24;
	private static final int MAX_DIAMETER_CHUNKS = 128;
	private static final int MAX_STRONGHOLDS = 3;
	/** The largest reach of a stronghold from its start, in blocks, and the least depth that vanilla gives it. */
	private static final int REACH = 64;
	private static final int LEAST_DEPTH = 11;

	private StrongholdPositions() {
	}

	/** The number of strongholds: 1 up to 49 chunks across, 2 up to 101, and 3 from there. */
	public static int count(int diameterChunks) {
		double step = (double) (diameterChunks - MIN_DIAMETER_CHUNKS) / (MAX_DIAMETER_CHUNKS - MIN_DIAMETER_CHUNKS);
		return Math.max(1, Math.min(MAX_STRONGHOLDS, (int) Math.round(1 + (MAX_STRONGHOLDS - 1) * step)));
	}

	public static List<ChunkPos> positions(SeamLayout layout, long seed) {
		Random random = new Random(seed * 0x9E3779B97F4A7C15L + 0x5712);
		List<Integer> faces = new ArrayList<>();
		for (int face = 0; face < SeamLayout.FACES.length; face++) {
			faces.add(face);
		}
		Collections.shuffle(faces, random);
		int spread = Math.max(0, Math.min(layout.radius / 3, layout.radius - LEAST_DEPTH - REACH));
		List<ChunkPos> positions = new ArrayList<>();
		for (int i = 0; i < count(layout.radius / 8); i++) {
			int centerX = layout.offsetX(faces.get(i));
			int x = Math.floorDiv(centerX + (int) Math.round((random.nextDouble() * 2 - 1) * spread), 16);
			int z = Math.floorDiv((int) Math.round((random.nextDouble() * 2 - 1) * spread), 16);
			positions.add(new ChunkPos(x, z));
		}
		return positions;
	}
}

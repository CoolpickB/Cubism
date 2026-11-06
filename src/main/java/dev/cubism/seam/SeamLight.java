package dev.cubism.seam;

import dev.cubism.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * Light that steps across a seam into a chunk that is not loaded is lost. A chunk often generates while the chunk on
 * the other side of its seam is not loaded, so light stopped at the seam until a block update next to it lit it
 * again. When a chunk loads, each pair of nodes across its seams is compared, and where the light differs by more
 * than one step, the darker node pulls light in from its neighbors again.
 */
public final class SeamLight {
	private static final LightLayer[] LAYERS = {LightLayer.SKY, LightLayer.BLOCK};

	private SeamLight() {}

	public static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
		SeamLayout layout = SeamLayout.of(level);
		if (layout == null) {
			return;
		}
		ChunkPos pos = chunk.getPos();
		int minX = pos.getMinBlockX();
		int minZ = pos.getMinBlockZ();
		// The lowest node in the wedge for each column of the chunk and the columns around it. Above it, the column
		// stays in the wedge, so a node borders on another wedge when it is the lowest one, or below the lowest one
		// of a column next to it.
		int[][] lowest = new int[18][18];
		for (int i = 0; i < 18; i++) {
			for (int k = 0; k < 18; k++) {
				lowest[i][k] = lowestInWedge(layout, minX - 1 + i, minZ - 1 + k);
			}
		}
		LevelLightEngine engine = level.getChunkSource().getLightEngine();
		BlockPos.MutableBlockPos node = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos across = new BlockPos.MutableBlockPos();
		int[] owner = new int[3];
		for (int i = 1; i <= 16; i++) {
			for (int k = 1; k <= 16; k++) {
				int bottom = lowest[i][k];
				if (bottom == Integer.MIN_VALUE) {
					continue;
				}
				int top = Math.max(bottom, Math.max(Math.max(lowest[i - 1][k], lowest[i + 1][k]), Math.max(lowest[i][k - 1], lowest[i][k + 1])) - 1);
				int x = minX - 1 + i;
				int z = minZ - 1 + k;
				for (int y = bottom; y <= top; y++) {
					for (Direction direction : Direction.values()) {
						int ni = i + direction.getStepX();
						int nk = k + direction.getStepZ();
						int ny = y + direction.getStepY();
						if (ny >= lowest[ni][nk] || !layout.ownerStorage(x + direction.getStepX(), ny, z + direction.getStepZ(), owner)) {
							continue;
						}
						ChunkAccess acrossChunk = level.getChunkSource().getChunkNow(
							SectionPos.blockToSectionCoord(owner[0]), SectionPos.blockToSectionCoord(owner[2])
						);
						if (acrossChunk == null) {
							continue;
						}
						node.set(x, y, z);
						across.set(owner[0], owner[1], owner[2]);
						for (LightLayer layer : LAYERS) {
							LayerLightEventListener light = engine.getLayerListener(layer);
							int here = light.getLightValue(node);
							int there = light.getLightValue(across);
							if (here - there >= 2) {
								check(engine, acrossChunk, across);
							} else if (there - here >= 2) {
								check(engine, chunk, node);
							}
						}
					}
				}
			}
		}
	}

	/**
	 * The lowest storage height of a column that is in the wedge of its face, or {@link Integer#MIN_VALUE} for a
	 * column in no region, which has no seams. The wedge of a face is where the height above the center is more
	 * than the distance from the axis of the face, so the lowest node is within a block or two of that distance.
	 */
	private static int lowestInWedge(SeamLayout layout, int x, int z) {
		if (!layout.inRegion(x, z)) {
			return Integer.MIN_VALUE;
		}
		int edge = layout.centerY + Math.max(Math.abs(x - layout.offsetX(layout.faceIndexAt(x))), Math.abs(z));
		for (int y = edge - 2; y <= edge + 2; y++) {
			if (layout.insideWedge(x, y, z)) {
				return y;
			}
		}
		return edge + 3;
	}

	/**
	 * An opaque node without light of its own stays dark, so a check there would change nothing. The block is read
	 * from the chunk: the level would wait for the chunk that loads, which is not done until this returns.
	 */
	private static void check(LevelLightEngine engine, ChunkAccess chunk, BlockPos pos) {
		BlockState state = chunk.getBlockState(pos);
		if (state.getLightDampening() < 15 || state.getLightEmission() > 0) {
			engine.checkBlock(pos.immutable());
		}
	}
}

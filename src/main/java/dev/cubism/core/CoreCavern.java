package dev.cubism.core;

import com.mojang.serialization.MapCodec;
import dev.cubism.worldgen.SeamLayout;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * The face of the inner bedrock cube. The caves of the terrain reach down to it (see {@code CubeCoreFloorFunction}),
 * and this feature only dresses the cube. Its face is made plain bedrock, so the vanilla bedrock noise does not roughen
 * it. Budding bedrock replaces bedrock on the edges and corners of the cube where a block touches air, and some of the
 * air next to it starts with heavy cores grown. Each cube position is written by the face that owns it, and the air
 * is read in the frame of that face, so air across a diagonal is not seen by the owner of an edge block.
 */
public record CoreCavern() implements Feature {
	public static final MapCodec<CoreCavern> CODEC = MapCodec.unit(CoreCavern::new);

	/** The vanilla bedrock band is 5 blocks thick from the bottom of the storage. The cube face is its top. */
	public static final int BEDROCK_TOP = 3;
	/** Budding bedrock grows only on the edges and corners of the cube, where it juts into the caves. */
	private static final double BUDDING_EDGE = 1.0 / 60.0;
	private static final double BUDDING_CORNER = 1.0 / 2.0;
	private static final double PREGROWN_CHANCE = 1.0 / 3.0;
	private static final int EDGE_REACH = 1;

	@Override
	public MapCodec<CoreCavern> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
		SeamLayout layout = SeamLayout.of(level.getLevel());
		if (layout == null || layout.nether || !layout.inRegion(origin.getX(), origin.getZ())) {
			return false;
		}
		int x0 = origin.getX() & ~15;
		int z0 = origin.getZ() & ~15;
		int face = layout.faceIndexAt(x0);
		int baseA = layout.innerBedrockRadius + BEDROCK_TOP;
		int y0 = layout.centerY + baseA - 1;
		long seed = level.getSeed();

		BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
		BlockState budding = CoreBlocks.BUDDING_BEDROCK.defaultBlockState();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		boolean placed = false;
		List<int[]> buddingPositions = new ArrayList<>();
		int[] cube = new int[3];

		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				int sx = x0 + dx;
				int sz = z0 + dz;
				int from = columnFrom(layout, level, face, sx, sz);
				for (int sy = from; sy <= y0 + 1; sy++) {
					layout.toCube(face, sx, sy, sz, cube);
					if (layout.ownerFace(cube[0], cube[1], cube[2]) != face) {
						continue;
					}
					int cy = cube[1] - layout.centerY;
					int ax = Math.abs(cube[0]);
					int ay = Math.abs(cy);
					int az = Math.abs(cube[2]);
					int a = Math.max(ax, Math.max(ay, az));
					BlockState state = bedrock;
					if (a - baseA <= 0) {
						int c = Math.min(ax, Math.min(ay, az));
						int b = ax + ay + az - a - c;
						double chance = a - c <= EDGE_REACH ? BUDDING_CORNER : a - b <= EDGE_REACH ? BUDDING_EDGE : 0.0;
						if (chance > 0.0 && touchesAir(layout, level, pos, sx, sy, sz) && unit(seed + 31L, cube[0], cy, cube[2]) < chance) {
							state = budding;
							buddingPositions.add(new int[] {sx, sy, sz});
						}
					}
					pos.set(sx, sy, sz);
					if (level.getBlockState(pos) != state) {
						level.setBlock(pos, state, 2);
						placed = true;
					}
				}
			}
		}

		// Like a geode, a budding block starts with some cores already grown. Only air of this chunk and face is written.
		BlockState core = Blocks.HEAVY_CORE.defaultBlockState();
		int[] other = new int[3];
		for (int[] b : buddingPositions) {
			for (int k = 0; k < 6; k++) {
				int tx = b[0] + (k == 0 ? 1 : k == 1 ? -1 : 0);
				int ty = b[1] + (k == 2 ? 1 : k == 3 ? -1 : 0);
				int tz = b[2] + (k == 4 ? 1 : k == 5 ? -1 : 0);
				if (tx < x0 || tx >= x0 + 16 || tz < z0 || tz >= z0 + 16) {
					continue;
				}
				layout.toCube(face, tx, ty, tz, other);
				if (layout.ownerFace(other[0], other[1], other[2]) != face || unit(seed + 37L, other[0], other[1], other[2]) >= PREGROWN_CHANCE) {
					continue;
				}
				pos.set(tx, ty, tz);
				if (level.getBlockState(pos).isAir()) {
					level.setBlock(pos, core, 2);
				}
			}
		}
		return placed;
	}

	/** Neighbors outside the wedge are skipped: reading one counts as a refused block and would undo the whole feature. */
	private static boolean touchesAir(SeamLayout layout, WorldGenLevel level, BlockPos.MutableBlockPos pos, int sx, int sy, int sz) {
		for (int k = 0; k < 6; k++) {
			pos.set(sx + (k == 0 ? 1 : k == 1 ? -1 : 0), sy + (k == 2 ? 1 : k == 3 ? -1 : 0), sz + (k == 4 ? 1 : k == 5 ? -1 : 0));
			if (layout.insideWedge(pos.getX(), pos.getY(), pos.getZ()) && level.getBlockState(pos).isAir()) {
				return true;
			}
		}
		return false;
	}

	/** The lowest storage Y of a column that belongs to the wedge of the face, or above the work area if it has none. */
	private static int columnFrom(SeamLayout layout, WorldGenLevel level, int face, int sx, int sz) {
		int lx = sx - layout.offsetX(face);
		return Math.max(level.getMinY(), layout.centerY + Math.max(Math.abs(lx), Math.abs(sz)));
	}

	private static double unit(long seed, int x, int y, int z) {
		long h = seed ^ (x * 341873128712L) ^ (y * 132897987541L) ^ (z * 42317861L);
		h = (h ^ (h >>> 33)) * 0xff51afd7ed558ccdL;
		h = (h ^ (h >>> 33)) * 0xc4ceb9fe1a85ec53L;
		h ^= h >>> 33;
		return (h >>> 11) * 0x1.0p-53;
	}
}

package dev.cubism.core;

import com.mojang.serialization.MapCodec;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * The cavern around the inner bedrock cube. It works on cube coordinates, in the same way on every face, and each
 * cube position is written by the face that owns it, so the cavern is whole across the diagonals.
 * <p>
 * Let {@code a >= b >= c} be the sorted distances of a position from the center along the three axes. Then
 * {@code a} is the height over the core and {@code e = a - b} the distance to the diagonal plane of an edge of the
 * cube. {@code k = a - c} is small only near a corner. The bedrock reaches a height {@code J} that is largest at the
 * corners and along the edges, so spires of bedrock stand in the cavern. The air goes on above them up to {@code G},
 * and a lining of purpur and obsidian closes it. Over the middle of a face the cavern fades out and nothing changes.
 */
public record CoreCavern() implements Feature {
	public static final MapCodec<CoreCavern> CODEC = MapCodec.unit(CoreCavern::new);

	/** The vanilla bedrock band is 5 blocks thick, from the bottom of the storage. Its top is this far over the inner radius. */
	private static final int BEDROCK_TOP = 3;
	private static final double EDGE_JUT = 4.0;
	private static final double CORNER_JUT = 9.0;
	private static final double EDGE_WIDTH = 12.0;
	private static final double CORNER_WIDTH = 24.0;
	/** The cavern reaches this far from an edge plane, over the faces. */
	private static final double CAVE_WIDTH = 40.0;
	private static final double CAVE_HEIGHT = 14.0;
	private static final int PURPUR_LAYERS = 2;
	private static final int SHELL_LAYERS = 3;
	/** Highest ceiling of the lining: the spires, the air and the noise on both. */
	private static final int ZONE = (int) (EDGE_JUT + CORNER_JUT + CAVE_HEIGHT * 1.25) + PURPUR_LAYERS + SHELL_LAYERS + 2;
	private static final double CRYING_CHANCE = 1.0 / 28.0;
	private static final double BUDDING_CORNER = 0.10;
	private static final double BUDDING_EDGE = 0.025;

	@Override
	public MapCodec<CoreCavern> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
		SeamLayout layout = SeamLayout.of(level.getLevel());
		if (layout == null || layout.nether) {
			return false;
		}
		long seed = level.getSeed();
		int baseA = layout.innerBedrockRadius + BEDROCK_TOP;
		int x0 = origin.getX() & ~15;
		int z0 = origin.getZ() & ~15;
		BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
		BlockState budding = CoreBlocks.BUDDING_BEDROCK.defaultBlockState();
		BlockState purpur = CoreBlocks.PURPUR.defaultBlockState();
		BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();
		BlockState crying = Blocks.CRYING_OBSIDIAN.defaultBlockState();
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int[] cube = new int[3];
		boolean placed = false;

		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				int sx = x0 + dx;
				int sz = z0 + dz;
				if (!layout.inRegion(sx, sz)) {
					continue;
				}
				int face = layout.faceIndexAt(sx);
				int lx = sx - layout.offsetX(face);
				int from = Math.max(level.getMinY(), layout.centerY + Math.max(Math.abs(lx), Math.abs(sz)));
				int to = layout.centerY + baseA + ZONE;
				// Crying obsidian shines into the cavern only when the purpur under it is gone, so a column of it is open.
				boolean cryingColumn = unit(seed, sx, 0, sz) < CRYING_CHANCE;
				for (int sy = from; sy <= to; sy++) {
					layout.toCube(face, sx, sy, sz, cube);
					if (layout.ownerFace(cube[0], cube[1], cube[2]) != face) {
						continue;
					}
					int cy = cube[1] - layout.centerY;
					int ax = Math.abs(cube[0]);
					int ay = Math.abs(cy);
					int az = Math.abs(cube[2]);
					int a = Math.max(ax, Math.max(ay, az));
					int c = Math.min(ax, Math.min(ay, az));
					int b = ax + ay + az - a - c;
					double e = a - b;
					double k = a - c;
					double noise = noise(seed, cube[0], cy, cube[2]);
					double jut = (EDGE_JUT * ramp(e, EDGE_WIDTH) + CORNER_JUT * ramp(k, CORNER_WIDTH)) * (1.0 + 0.25 * noise);
					double gap = CAVE_HEIGHT * ramp(e, CAVE_WIDTH) * (1.0 + 0.25 * noise);
					if (gap < 1.0) {
						continue;
					}
					double h = a - baseA;
					double ceiling = jut + gap;
					BlockState state;
					if (h <= jut) {
						double chance = h > jut - 1.0 ? (k < CORNER_WIDTH ? BUDDING_CORNER : e < EDGE_WIDTH ? BUDDING_EDGE : 0.0) : 0.0;
						state = chance > 0.0 && unit(seed, cube[0], cy, cube[2]) < chance ? budding : bedrock;
					} else if (h <= ceiling) {
						state = air;
					} else if (h <= ceiling + PURPUR_LAYERS) {
						state = cryingColumn ? air : purpur;
					} else if (h <= ceiling + PURPUR_LAYERS + 1) {
						state = cryingColumn ? crying : obsidian;
					} else if (h <= ceiling + PURPUR_LAYERS + SHELL_LAYERS) {
						state = obsidian;
					} else {
						continue;
					}
					level.setBlock(pos.set(sx, sy, sz), state, 2);
					placed = true;
				}
			}
		}
		return placed;
	}

	/** 1 on the plane, falling smoothly to 0 at {@code width} blocks from it. */
	private static double ramp(double distance, double width) {
		double t = Mth.clamp(1.0 - distance / width, 0.0, 1.0);
		return smooth(t);
	}

	private static double smooth(double t) {
		return t * t * (3.0 - 2.0 * t);
	}

	private static double unit(long seed, int x, int y, int z) {
		long h = seed ^ (x * 341873128712L) ^ (y * 132897987541L) ^ (z * 42317861L);
		h = (h ^ (h >>> 33)) * 0xff51afd7ed558ccdL;
		h = (h ^ (h >>> 33)) * 0xc4ceb9fe1a85ec53L;
		h ^= h >>> 33;
		return (h >>> 11) * 0x1.0p-53;
	}

	/** Smooth value noise in -1 to 1 with a feature size of about 8 blocks. */
	private static double noise(long seed, int x, int y, int z) {
		double fx = x / 8.0;
		double fy = y / 8.0;
		double fz = z / 8.0;
		int ix = Mth.floor(fx);
		int iy = Mth.floor(fy);
		int iz = Mth.floor(fz);
		double tx = smooth(fx - ix);
		double ty = smooth(fy - iy);
		double tz = smooth(fz - iz);
		double sum = 0.0;
		for (int i = 0; i < 8; i++) {
			int ox = i & 1;
			int oy = i >> 1 & 1;
			int oz = i >> 2 & 1;
			double w = (ox == 0 ? 1.0 - tx : tx) * (oy == 0 ? 1.0 - ty : ty) * (oz == 0 ? 1.0 - tz : tz);
			sum += w * unit(seed + 7919L, ix + ox, iy + oy, iz + oz);
		}
		return sum * 2.0 - 1.0;
	}
}

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
 * Caves in the rock over the inner bedrock cube. The bedrock stays a plain cube, so its edges and corners stand out
 * of the caves. The caves are 3D noise in cube coordinates, so they are the same on every face and whole across the
 * diagonals, and each cube position is written by the face that owns it.
 * <p>
 * With {@code h} the height over the cube face, a position is air where chamber noise passes a threshold that rises
 * with {@code h}, or where two tunnel noises are both near zero. So the caves lie close over the bedrock and fade out
 * about {@link #CAVE_HEIGHT} blocks up, with chambers, winding tunnels and dead ends. Rock within {@link #PURPUR_DEPTH}
 * blocks of the air is purpur, and rock within {@link #SHELL_DEPTH} blocks of it is obsidian.
 */
public record CoreCavern() implements Feature {
	public static final MapCodec<CoreCavern> CODEC = MapCodec.unit(CoreCavern::new);

	/** The vanilla bedrock band is 5 blocks thick from the bottom of the storage. The cube face is its top. */
	private static final int BEDROCK_TOP = 3;
	private static final int CAVE_HEIGHT = 26;
	private static final int PURPUR_DEPTH = 2;
	private static final int SHELL_DEPTH = 5;
	/** The lining is found by distance to the air, so the work area reaches this far past the chunk to see air in the next one. */
	private static final int MARGIN = SHELL_DEPTH + 1;
	private static final double CRYING_CHANCE = 1.0 / 40.0;
	/** Budding bedrock grows only on the edges and corners of the cube, where it juts into the caves. */
	private static final double BUDDING_EDGE = 1.0 / 20.0;
	private static final double BUDDING_CORNER = 1.0 / 5.0;
	private static final int EDGE_REACH = 3;
	private static final byte FAR = 127;

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
		int ny = CAVE_HEIGHT + SHELL_DEPTH + 3;
		int yTop = y0 + ny - 1;

		boolean any = false;
		for (int dx = 0; dx < 16 && !any; dx++) {
			for (int dz = 0; dz < 16 && !any; dz++) {
				any = columnFrom(layout, level, face, x0 + dx, z0 + dz) <= yTop;
			}
		}
		if (!any) {
			return false;
		}

		long seed = level.getSeed();
		int n = 16 + 2 * MARGIN;
		byte[] dist = new byte[n * n * ny];
		int[] cube = new int[3];
		for (int ix = 0; ix < n; ix++) {
			for (int iz = 0; iz < n; iz++) {
				for (int iy = 0; iy < ny; iy++) {
					layout.toCube(face, x0 - MARGIN + ix, y0 + iy, z0 - MARGIN + iz, cube);
					int cy = cube[1] - layout.centerY;
					int h = Math.max(Math.abs(cube[0]), Math.max(Math.abs(cy), Math.abs(cube[2]))) - baseA;
					dist[(ix * n + iz) * ny + iy] = h >= 1 && h <= CAVE_HEIGHT && isAir(seed, cube[0], cy, cube[2], h) ? 0 : FAR;
				}
			}
		}
		for (int pass = 1; pass <= SHELL_DEPTH; pass++) {
			for (int ix = 0; ix < n; ix++) {
				for (int iz = 0; iz < n; iz++) {
					for (int iy = 0; iy < ny; iy++) {
						int i = (ix * n + iz) * ny + iy;
						if (dist[i] == FAR && touches(dist, n, ny, ix, iz, iy, pass - 1)) {
							dist[i] = (byte) pass;
						}
					}
				}
			}
		}

		BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
		BlockState budding = CoreBlocks.BUDDING_BEDROCK.defaultBlockState();
		BlockState purpur = CoreBlocks.PURPUR.defaultBlockState();
		BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();
		BlockState crying = Blocks.CRYING_OBSIDIAN.defaultBlockState();
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		boolean placed = false;

		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				int sx = x0 + dx;
				int sz = z0 + dz;
				int from = columnFrom(layout, level, face, sx, sz);
				for (int sy = from; sy <= yTop; sy++) {
					layout.toCube(face, sx, sy, sz, cube);
					if (layout.ownerFace(cube[0], cube[1], cube[2]) != face) {
						continue;
					}
					int cy = cube[1] - layout.centerY;
					int ax = Math.abs(cube[0]);
					int ay = Math.abs(cy);
					int az = Math.abs(cube[2]);
					int a = Math.max(ax, Math.max(ay, az));
					int h = a - baseA;
					BlockState state;
					if (sy < y0) {
						state = bedrock;
					} else {
						int ix = MARGIN + dx;
						int iz = MARGIN + dz;
						int iy = sy - y0;
						int d = dist[(ix * n + iz) * ny + iy];
						if (h <= 0) {
							state = bedrock;
							int c = Math.min(ax, Math.min(ay, az));
							int b = ax + ay + az - a - c;
							double chance = a - c <= EDGE_REACH ? BUDDING_CORNER : a - b <= EDGE_REACH ? BUDDING_EDGE : 0.0;
							if (chance > 0.0 && touches(dist, n, ny, ix, iz, iy, 0) && unit(seed + 31L, cube[0], cy, cube[2]) < chance) {
								state = budding;
							}
						} else if (d == 0) {
							state = air;
						} else if (d <= PURPUR_DEPTH) {
							state = cleared(layout, face, seed, dist, n, ny, x0, y0, z0, ix, iz, iy, d) ? air : purpur;
						} else if (d <= SHELL_DEPTH) {
							state = d == PURPUR_DEPTH + 1 && isCrying(seed, cube[0], cy, cube[2]) ? crying : obsidian;
						} else {
							continue;
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
		return placed;
	}

	/** The lowest storage Y of a column that belongs to the wedge of the face, or above the work area if it has none. */
	private static int columnFrom(SeamLayout layout, WorldGenLevel level, int face, int sx, int sz) {
		int lx = sx - layout.offsetX(face);
		return Math.max(level.getMinY(), layout.centerY + Math.max(Math.abs(lx), Math.abs(sz)));
	}

	/**
	 * Crying obsidian shines into the cave only if the rock between it and the air is open. The purpur next to a crying
	 * block, on the way to the air, is cleared: one block of each of the two purpur layers, along the first way found.
	 */
	private static boolean cleared(SeamLayout layout, int face, long seed, byte[] dist, int n, int ny, int x0, int y0, int z0, int ix, int iz, int iy, int d) {
		int[] cube = new int[3];
		for (int[] o : OFFSETS) {
			int jx = ix + o[0];
			int jz = iz + o[1];
			int jy = iy + o[2];
			if (jx < 0 || jz < 0 || jy < 0 || jx >= n || jz >= n || jy >= ny) {
				continue;
			}
			int dj = dist[(jx * n + jz) * ny + jy];
			if (d == 2 && dj == 3 && cryingAt(layout, face, seed, cube, x0, y0, z0, jx, jz, jy) && firstStep(dist, n, ny, jx, jz, jy, 2) == (ix * n + iz) * ny + iy) {
				return true;
			}
			if (d == 1 && dj == 2) {
				int toward = firstStep(dist, n, ny, jx, jz, jy, 1);
				if (toward != (ix * n + iz) * ny + iy) {
					continue;
				}
				// The block j has a crying block behind it that picked j as its way.
				for (int[] p : OFFSETS) {
					int kx = jx + p[0];
					int kz = jz + p[1];
					int ky = jy + p[2];
					if (kx < 0 || kz < 0 || ky < 0 || kx >= n || kz >= n || ky >= ny) {
						continue;
					}
					if (dist[(kx * n + kz) * ny + ky] == 3 && cryingAt(layout, face, seed, cube, x0, y0, z0, kx, kz, ky)
						&& firstStep(dist, n, ny, kx, kz, ky, 2) == (jx * n + jz) * ny + jy) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static final int[][] OFFSETS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

	private static int firstStep(byte[] dist, int n, int ny, int ix, int iz, int iy, int want) {
		for (int[] o : OFFSETS) {
			int jx = ix + o[0];
			int jz = iz + o[1];
			int jy = iy + o[2];
			if (jx >= 0 && jz >= 0 && jy >= 0 && jx < n && jz < n && jy < ny && dist[(jx * n + jz) * ny + jy] == want) {
				return (jx * n + jz) * ny + jy;
			}
		}
		return -1;
	}

	private static boolean cryingAt(SeamLayout layout, int face, long seed, int[] cube, int x0, int y0, int z0, int ix, int iz, int iy) {
		layout.toCube(face, x0 - MARGIN + ix, y0 + iy, z0 - MARGIN + iz, cube);
		return isCrying(seed, cube[0], cube[1] - layout.centerY, cube[2]);
	}

	private static boolean isCrying(long seed, int cx, int cy, int cz) {
		return unit(seed + 17L, cx, cy, cz) < CRYING_CHANCE;
	}

	private static boolean touches(byte[] dist, int n, int ny, int ix, int iz, int iy, int want) {
		for (int[] o : OFFSETS) {
			int jx = ix + o[0];
			int jz = iz + o[1];
			int jy = iy + o[2];
			if (jx >= 0 && jz >= 0 && jy >= 0 && jx < n && jz < n && jy < ny && dist[(jx * n + jz) * ny + jy] == want) {
				return true;
			}
		}
		return false;
	}

	/** Chambers over a threshold that rises with the height, and tunnels where two noises are near zero together. */
	private static boolean isAir(long seed, int x, int y, int z, int h) {
		double chambers = 0.55 * noise(seed + 1L, x, y, z, 18.0) + 0.30 * noise(seed + 2L, x, y, z, 8.0) + 0.15 * noise(seed + 3L, x, y, z, 4.0);
		double ratio = (double) h / CAVE_HEIGHT;
		if (chambers > 0.08 + 0.87 * ratio * ratio) {
			return true;
		}
		return ratio <= 0.8 && Math.abs(noise(seed + 4L, x, y, z, 14.0)) < 0.10 && Math.abs(noise(seed + 5L, x, y, z, 14.0)) < 0.10;
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

	/** Smooth value noise in -1 to 1. */
	private static double noise(long seed, int x, int y, int z, double scale) {
		double fx = x / scale;
		double fy = y / scale;
		double fz = z / scale;
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
			sum += w * unit(seed, ix + ox, iy + oy, iz + oz);
		}
		return sum * 2.0 - 1.0;
	}
}

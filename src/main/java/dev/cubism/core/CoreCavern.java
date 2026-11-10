package dev.cubism.core;

import com.mojang.serialization.MapCodec;
import dev.cubism.worldgen.SeamLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * Field lines over the inner bedrock cube. The 8 corners are poles, and along each of the 12 edges a bundle of arcs
 * joins the two corner poles, like iron filings around a magnet. Each arc is a tube of air that is thin at the poles,
 * where it runs along the edge, and wider in the middle, where it lifts off and bulges away from the cube. The bundle
 * fans out between the two faces at the edge, each arc with its own height. Everything else stays solid, so the middle
 * of a face has no air over it. The shape is in cube coordinates, so it is the same on every face and whole across the
 * diagonals, and each cube position is written by the face that owns it.
 * <p>
 * Rock within {@link #PURPUR_DEPTH} blocks of the air is purpur, and rock within {@link #SHELL_DEPTH} blocks of it is
 * obsidian. The feature only writes into solid rock: caves, water and lava that the vanilla generator made stay, so
 * they run into and through the tubes and their lining.
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
	private static final double BUDDING_EDGE = 1.0 / 100.0;
	private static final double BUDDING_CORNER = 1.0 / 4.0;
	private static final double PREGROWN_CHANCE = 1.0 / 3.0;
	/** Per purpur block that touches the air. The tubes are narrow, so a few shulkers already make a stretch hard. */
	private static final double SHULKER_CHANCE = 1.0 / 150.0;
	private static final int SHULKERS_PER_CHUNK = 2;
	private static final int EDGE_REACH = 1;
	private static final byte FAR = 127;
	private static final int ARCS_PER_EDGE = 4;
	private static final int ARC_MAX_HEIGHT = 22;
	private static final int ARC_MIN_HEIGHT = 8;
	private static final double RADIUS_POLE = 1.6;
	private static final double RADIUS_MIDDLE = 3.0;
	/** The tube points are spaced so that neighbouring spheres overlap. */
	private static final double POINT_SPACING = 1.5;
	private static final int BUCKET = 4;
	private static volatile ArcCache arcCache;

	private record ArcCache(long seed, int baseA, double[][] points) {
	}

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
		int cells = n * n * ny;
		int[] gx = new int[cells];
		int[] gy = new int[cells];
		int[] gz = new int[cells];
		int[] cube = new int[3];
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (int ix = 0; ix < n; ix++) {
			for (int iz = 0; iz < n; iz++) {
				for (int iy = 0; iy < ny; iy++) {
					layout.toCube(face, x0 - MARGIN + ix, y0 + iy, z0 - MARGIN + iz, cube);
					int i = (ix * n + iz) * ny + iy;
					gx[i] = cube[0];
					gy[i] = cube[1] - layout.centerY;
					gz[i] = cube[2];
					minX = Math.min(minX, gx[i]);
					maxX = Math.max(maxX, gx[i]);
					minY = Math.min(minY, gy[i]);
					maxY = Math.max(maxY, gy[i]);
					minZ = Math.min(minZ, gz[i]);
					maxZ = Math.max(maxZ, gz[i]);
				}
			}
		}
		Map<Long, List<double[]>> buckets = new HashMap<>();
		for (double[] arc : arcs(seed, baseA)) {
			for (int j = 0; j < arc.length; j += 4) {
				double r = arc[j + 3];
				if (arc[j] + r < minX || arc[j] - r > maxX || arc[j + 1] + r < minY || arc[j + 1] - r > maxY || arc[j + 2] + r < minZ || arc[j + 2] - r > maxZ) {
					continue;
				}
				long key = bucketKey(Math.floorDiv((int) Math.floor(arc[j]), BUCKET), Math.floorDiv((int) Math.floor(arc[j + 1]), BUCKET), Math.floorDiv((int) Math.floor(arc[j + 2]), BUCKET));
				buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(new double[] {arc[j], arc[j + 1], arc[j + 2], arc[j + 3]});
			}
		}
		byte[] dist = new byte[cells];
		for (int i = 0; i < cells; i++) {
			int h = Math.max(Math.abs(gx[i]), Math.max(Math.abs(gy[i]), Math.abs(gz[i]))) - baseA;
			dist[i] = h >= 1 && h <= CAVE_HEIGHT && inTube(buckets, gx[i], gy[i], gz[i]) ? 0 : FAR;
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
		List<int[]> buddingPositions = new ArrayList<>();
		List<BlockPos> shulkers = new ArrayList<>();

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
								buddingPositions.add(new int[] {sx, sy, sz});
							}
						} else if (d == 0) {
							state = air;
						} else if (d <= PURPUR_DEPTH) {
							state = cleared(layout, face, seed, dist, n, ny, x0, y0, z0, ix, iz, iy, d) ? air : purpur;
							if (state == purpur && d == 1 && shulkers.size() < SHULKERS_PER_CHUNK && unit(seed + 41L, cube[0], cy, cube[2]) < SHULKER_CHANCE) {
								int[] o = OFFSETS[(int) (unit(seed + 43L, cube[0], cy, cube[2]) * OFFSETS.length)];
								if (ix + o[0] >= MARGIN && ix + o[0] < MARGIN + 16 && iz + o[1] >= MARGIN && iz + o[1] < MARGIN + 16
									&& iy + o[2] < ny && dist[((ix + o[0]) * n + iz + o[1]) * ny + iy + o[2]] == 0) {
									shulkers.add(new BlockPos(sx + o[0], sy + o[2], sz + o[1]));
								}
							}
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

		// Shulkers cling to the purpur, the raw form of the blocks they live on in the End. They are placed once and stay.
		for (BlockPos at : shulkers) {
			layout.toCube(face, at.getX(), at.getY(), at.getZ(), other);
			if (layout.ownerFace(other[0], other[1], other[2]) != face || !level.getBlockState(at).isAir()) {
				continue;
			}
			Shulker shulker = EntityTypes.SHULKER.create(level.getLevel(), EntitySpawnReason.STRUCTURE);
			if (shulker != null) {
				shulker.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
				shulker.setPersistenceRequired();
				level.addFreshEntity(shulker);
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

	private static long bucketKey(int bx, int by, int bz) {
		return ((long) (bx & 0x1FFFFF) << 42) | ((long) (by & 0x1FFFFF) << 21) | (bz & 0x1FFFFF);
	}

	private static boolean inTube(Map<Long, List<double[]>> buckets, int x, int y, int z) {
		int bx = Math.floorDiv(x, BUCKET);
		int by = Math.floorDiv(y, BUCKET);
		int bz = Math.floorDiv(z, BUCKET);
		for (int ox = -1; ox <= 1; ox++) {
			for (int oy = -1; oy <= 1; oy++) {
				for (int oz = -1; oz <= 1; oz++) {
					List<double[]> list = buckets.get(bucketKey(bx + ox, by + oy, bz + oz));
					if (list == null) {
						continue;
					}
					for (double[] p : list) {
						double dx = x - p[0];
						double dy = y - p[1];
						double dz = z - p[2];
						if (dx * dx + dy * dy + dz * dz <= p[3] * p[3]) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	/**
	 * The arcs of the world as flat arrays of (x, y, z, radius) points in cube coordinates, y from the center. An arc
	 * leaves a corner along an edge and lifts off with a height of {@code sin^1.5}, so it hugs the cube near the pole.
	 */
	private static double[][] arcs(long seed, int baseA) {
		ArcCache cache = arcCache;
		if (cache != null && cache.seed == seed && cache.baseA == baseA) {
			return cache.points;
		}
		double length = 2.0 * baseA;
		List<double[]> arcs = new ArrayList<>();
		int edge = 0;
		for (int k = 0; k < 3; k++) {
			int a1 = (k + 1) % 3;
			int a2 = (k + 2) % 3;
			for (int s1 = -1; s1 <= 1; s1 += 2) {
				for (int s2 = -1; s2 <= 1; s2 += 2) {
					edge++;
					for (int i = 0; i < ARCS_PER_EDGE; i++) {
						double phi = (i + 0.3 + 0.4 * unit(seed + 103L, edge, i, 1)) / ARCS_PER_EDGE * (Math.PI / 2.0);
						double height = ARC_MIN_HEIGHT + (ARC_MAX_HEIGHT - ARC_MIN_HEIGHT) * unit(seed + 101L, edge, i, 0);
						int steps = Math.max(12, (int) Math.ceil((length + 3.0 * height) / POINT_SPACING));
						double[] pts = new double[(steps + 1) * 4];
						for (int j = 0; j <= steps; j++) {
							double t = (double) j / steps;
							double lift = height * Math.pow(Math.sin(Math.PI * t), 1.5);
							double[] c = new double[3];
							c[k] = -baseA + length * t;
							c[a1] = s1 * (baseA + Math.cos(phi) * lift);
							c[a2] = s2 * (baseA + Math.sin(phi) * lift);
							pts[j * 4] = c[0];
							pts[j * 4 + 1] = c[1];
							pts[j * 4 + 2] = c[2];
							pts[j * 4 + 3] = RADIUS_POLE + (RADIUS_MIDDLE - RADIUS_POLE) * Math.sin(Math.PI * t);
						}
						arcs.add(pts);
					}
				}
			}
		}
		double[][] result = arcs.toArray(new double[0][]);
		arcCache = new ArcCache(seed, baseA, result);
		return result;
	}

	private static double unit(long seed, int x, int y, int z) {
		long h = seed ^ (x * 341873128712L) ^ (y * 132897987541L) ^ (z * 42317861L);
		h = (h ^ (h >>> 33)) * 0xff51afd7ed558ccdL;
		h = (h ^ (h >>> 33)) * 0xc4ceb9fe1a85ec53L;
		h ^= h >>> 33;
		return (h >>> 11) * 0x1.0p-53;
	}
}

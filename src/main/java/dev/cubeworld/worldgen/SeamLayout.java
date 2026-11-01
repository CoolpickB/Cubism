package dev.cubeworld.worldgen;

import dev.cubeworld.CubeWorldMod;
import dev.cubeworld.math.CubePlanetMath;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.jspecify.annotations.Nullable;

/**
 * Where each cube face is stored in the level. Every face is stored upright, as the pyramid wedge
 * {@code max(|vx|, |vz|) <= vy - centerY}, in its own region along +X. A storage position maps to the cube with
 * {@code cube = A_face * (storage - offset_face - C) + C}, where {@code C = (0, centerY, 0)} and
 * {@code A_face} is the rotation of {@link OrientedWorldGenLevel#toWorld}.
 */
public final class SeamLayout {
	/** Faces in storage order. UP and EAST are next to each other so that their shared edge is the one seam. */
	public static final Direction[] FACES = {Direction.UP, Direction.EAST, Direction.DOWN, Direction.WEST, Direction.SOUTH, Direction.NORTH};

	private static volatile @Nullable SeamLayout active;
	private static volatile @Nullable SeamLayout client;

	public final int radius;
	public final int centerY;
	public final SeamFold fold;
	public final int innerBedrockRadius;
	/** Distance between region centers. A wedge is 2(R + 96) + 1 blocks wide at the top, and region edges are chunk-aligned. */
	public final int spacing;
	private final int[][] rotations = new int[FACES.length][];

	public SeamLayout(int diameterChunks) {
		this.radius = CubePlanetMath.getRadiusBlocks(diameterChunks);
		this.centerY = CubePlanetMath.getCenterY(diameterChunks);
		this.innerBedrockRadius = CubePlanetMath.getInnerBedrockRadius(diameterChunks);
		this.spacing = (Math.floorDiv(this.radius + CubePlanetMath.ATMOSPHERE_HEIGHT, 16) + 1) * 32;
		for (int i = 0; i < FACES.length; i++) {
			this.rotations[i] = rotation(FACES[i]);
		}
		this.fold = new SeamFold(this);
	}

	public static @Nullable SeamLayout active() {
		return active;
	}

	public static void setActive(SeamLayout layout) {
		active = layout;
	}

	/** The layout of a level whose generator stores the cube in seam regions, or null for any other level. */
	public static @Nullable SeamLayout of(ServerLevel level) {
		return level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator
			&& generator.generatorSettings().is(CubeWorldMod.SEAM_SETTINGS) ? active : null;
	}

	/** The layout that the server sent to this client, or null. The client sets it on join and clears it on leave. */
	public static void setClient(@Nullable SeamLayout layout) {
		client = layout;
	}

	/** The layout of a server or client level, or null when the level has no seams. */
	public static @Nullable SeamLayout of(Level level) {
		if (level instanceof ServerLevel serverLevel) {
			return of(serverLevel);
		}
		return level.isClientSide() && level.dimension() == Level.OVERWORLD ? client : null;
	}

	/** Row-major 3x3 matrix of the face rotation, taken from {@link OrientedWorldGenLevel#toWorld}. */
	private static int[] rotation(Direction face) {
		return switch (face) {
			case UP -> new int[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
			case DOWN -> new int[] {-1, 0, 0, 0, -1, 0, 0, 0, 1};
			case SOUTH -> new int[] {1, 0, 0, 0, 0, -1, 0, 1, 0};
			case NORTH -> new int[] {1, 0, 0, 0, 0, 1, 0, -1, 0};
			case EAST -> new int[] {0, 1, 0, -1, 0, 0, 0, 0, 1};
			case WEST -> new int[] {0, -1, 0, 1, 0, 0, 0, 0, 1};
		};
	}

	public int[] rotation(int faceIndex) {
		return this.rotations[faceIndex];
	}

	public int offsetX(int faceIndex) {
		return faceIndex * this.spacing;
	}

	/** Face region that contains storage X. The result is clamped to a valid face. */
	public int faceIndexAt(int storageX) {
		int index = Math.floorDiv(storageX + this.spacing / 2, this.spacing);
		return Math.clamp(index, 0, FACES.length - 1);
	}

	/** The fold that carries a storage column past an edge of its face, or null for a column on the face or past a corner. */
	public SeamFold.@Nullable Fold foldOfColumn(int storageX, int storageZ) {
		if (!this.inRegion(storageX, storageZ)) {
			return null;
		}
		int face = this.faceIndexAt(storageX);
		int x = storageX - this.offsetX(face);
		boolean beyondX = Math.abs(x) > this.radius;
		boolean beyondZ = Math.abs(storageZ) > this.radius;
		if (beyondX == beyondZ) {
			return null;
		}
		int edge = beyondX ? (x > 0 ? 0 : 1) : (storageZ > 0 ? 2 : 3);
		return this.fold.get(face, edge);
	}

	public boolean inRegion(int storageX, int storageZ) {
		int index = Math.floorDiv(storageX + this.spacing / 2, this.spacing);
		return index >= 0 && index < FACES.length && Math.abs(storageZ) < this.spacing / 2;
	}

	/** Writes the cube position of a storage position of the given face into {@code out}. */
	public void toCube(int faceIndex, int sx, int sy, int sz, int[] out) {
		int[] m = this.rotations[faceIndex];
		int x = sx - this.offsetX(faceIndex);
		int y = sy - this.centerY;
		out[0] = m[0] * x + m[1] * y + m[2] * sz;
		out[1] = m[3] * x + m[4] * y + m[5] * sz + this.centerY;
		out[2] = m[6] * x + m[7] * y + m[8] * sz;
	}

	/** Writes the storage position of a cube position, in the region of the given face, into {@code out}. */
	public void toStorage(int faceIndex, int cx, int cy, int cz, int[] out) {
		int[] m = this.rotations[faceIndex];
		int y = cy - this.centerY;
		out[0] = m[0] * cx + m[3] * y + m[6] * cz + this.offsetX(faceIndex);
		out[1] = m[1] * cx + m[4] * y + m[7] * cz + this.centerY;
		out[2] = m[2] * cx + m[5] * y + m[8] * cz;
	}

	/**
	 * Face that owns a cube position. Positions on a diagonal between faces go to the first face in the order
	 * Y, X, Z, so that every position has exactly one owner.
	 */
	public int ownerFace(int cx, int cy, int cz) {
		int x = cx;
		int y = cy - this.centerY;
		int z = cz;
		return dominantFace(x, y, z);
	}

	/** Face index of the axis with the largest magnitude, with ties broken in the order Y, X, Z. */
	private static int dominantFace(double x, double y, double z) {
		double ax = Math.abs(x);
		double ay = Math.abs(y);
		double az = Math.abs(z);
		Direction face;
		if (ay >= ax && ay >= az) {
			face = y >= 0 ? Direction.UP : Direction.DOWN;
		} else if (ax >= az) {
			face = x > 0 ? Direction.EAST : Direction.WEST;
		} else {
			face = z > 0 ? Direction.SOUTH : Direction.NORTH;
		}
		return indexOf(face);
	}

	/**
	 * The rigid motion {@code s_to = R s_from + t} from the storage frame of one face to the storage frame of
	 * another, for continuous positions. Returns {@code {R (row-major, 9 values), t (3 values)}}. The block map of
	 * {@link #toCube} and {@link #toStorage} sends block s to block {@code R s + t0}. A block fills
	 * {@code [s, s + 1)}, so on each axis that R negates, a continuous position needs one more block than t0.
	 */
	public int[] motion(int fromFace, int toFace) {
		int[] a = this.rotations[toFace];
		int[] b = this.rotations[fromFace];
		int[] m = new int[12];
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				int sum = 0;
				for (int k = 0; k < 3; k++) {
					sum += a[k * 3 + i] * b[k * 3 + j];
				}
				m[i * 3 + j] = sum;
			}
		}
		int[] from = {this.offsetX(fromFace), this.centerY, 0};
		int[] to = {this.offsetX(toFace), this.centerY, 0};
		for (int i = 0; i < 3; i++) {
			int t = to[i];
			int rowSum = 0;
			for (int j = 0; j < 3; j++) {
				t -= m[i * 3 + j] * from[j];
				rowSum += m[i * 3 + j];
			}
			m[9 + i] = rowSum < 0 ? t + 1 : t;
		}
		return m;
	}

	/**
	 * The face that a continuous storage position crosses into, or -1 when the position is still in its own wedge
	 * or less than {@code margin} blocks past it. The margin keeps an entity on the diagonal from crossing back and
	 * forth: after a crossing, it is that far inside the new wedge.
	 */
	public int crossingFace(double x, double y, double z, double margin) {
		int bx = Mth.floor(x);
		int bz = Mth.floor(z);
		if (!this.inRegion(bx, bz)) {
			return -1;
		}
		int face = this.faceIndexAt(bx);
		// Relative to the center of the cube, which is the center of a block in the integer wedge test.
		double cx = x - this.offsetX(face) - 0.5;
		double cy = y - this.centerY - 0.5;
		double cz = z - 0.5;
		if (Math.max(Math.abs(cx), Math.abs(cz)) - cy <= margin) {
			return -1;
		}
		int[] m = this.rotations[face];
		int owner = dominantFace(
			m[0] * cx + m[1] * cy + m[2] * cz,
			m[3] * cx + m[4] * cy + m[5] * cz,
			m[6] * cx + m[7] * cy + m[8] * cz
		);
		return owner == face ? -1 : owner;
	}

	/**
	 * The direction of gravity in the air at a continuous storage position, or null where it is straight down. It
	 * points to the nearest point of the core box, the cube that the rounded edges are rounded about. Above the flat
	 * part of a face that is straight down. Over an edge it turns about the edge, and over a corner it points to the
	 * corner, so the field is continuous across the diagonals and a jump off an edge arcs onto the next face.
	 *
	 * <p>Inside the core box the pull turns toward the nearest face of the box, by the inverse square of the distance to
	 * each face. On a seam between two wedges it is half way between the two faces, at the top of the core it is
	 * straight down, and deep in a wedge away from the seams it is close to straight down. A fixed band around the
	 * seams would not do: at the top of the core the field must be straight down, also next to a seam, so the turn
	 * must narrow toward the edges of the core box, as the turn over an edge does above them.
	 */
	public @Nullable Vec3 airGravity(double x, double y, double z) {
		int bx = Mth.floor(x);
		if (!this.inRegion(bx, Mth.floor(z))) {
			return null;
		}
		double core = this.radius - CubePlanetMath.edgeRounding();
		double cx = x - this.offsetX(this.faceIndexAt(bx)) - 0.5;
		double cy = y - this.centerY - 0.5;
		double cz = z - 0.5;
		if (Math.abs(cx) <= core && Math.abs(cz) <= core) {
			return Math.abs(cy) < core ? coreGravity(cx, cy, cz, core) : null;
		}
		double dx = cx - Math.clamp(cx, -core, core);
		double dy = cy - Math.clamp(cy, -core, core);
		double dz = cz - Math.clamp(cz, -core, core);
		double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
		return length < 1.0E-6 ? null : new Vec3(-dx / length, -dy / length, -dz / length);
	}

	private static @Nullable Vec3 coreGravity(double x, double y, double z, double core) {
		double gx = corePull(x, core);
		double gy = corePull(y, core);
		double gz = corePull(z, core);
		// Close to straight down is straight down, which saves the callers the turn on most of a wedge.
		if (gy > 0.0 && gx * gx + gz * gz < 1.0E-6 * gy * gy) {
			return null;
		}
		double length = Math.sqrt(gx * gx + gy * gy + gz * gz);
		return length < 1.0E-12 ? null : new Vec3(-gx / length, -gy / length, -gz / length);
	}

	/** The pull along one axis toward the two faces of the core box on it, which cancel at the center. */
	private static double corePull(double p, double core) {
		double toPositive = core - p;
		double toNegative = core + p;
		return 1.0 / (toPositive * toPositive) - 1.0 / (toNegative * toNegative);
	}

	public static int indexOf(Direction face) {
		for (int i = 0; i < FACES.length; i++) {
			if (FACES[i] == face) {
				return i;
			}
		}
		throw new IllegalArgumentException(face.toString());
	}

	/** True when the storage position is part of the wedge of the face whose region contains it. */
	public boolean insideWedge(int sx, int sy, int sz) {
		if (!this.inRegion(sx, sz)) {
			return false;
		}
		int face = this.faceIndexAt(sx);
		int[] cube = new int[3];
		this.toCube(face, sx, sy, sz, cube);
		return this.ownerFace(cube[0], cube[1], cube[2]) == face;
	}

	/**
	 * Writes into {@code out} where the block that a storage position stands for is stored: the position in the
	 * region of the face that owns its cube position. Outside its wedge, a region holds no real blocks, so readers
	 * near a seam look there instead. Returns false when the position is in no region.
	 */
	public boolean ownerStorage(int sx, int sy, int sz, int[] out) {
		if (!this.inRegion(sx, sz)) {
			return false;
		}
		this.toCube(this.faceIndexAt(sx), sx, sy, sz, out);
		this.toStorage(this.ownerFace(out[0], out[1], out[2]), out[0], out[1], out[2], out);
		return true;
	}

	/** True when the storage position maps to a cube position below the rounded cube surface. */
	public boolean belowCubeSurface(int sx, int sy, int sz) {
		int[] cube = new int[3];
		this.toCube(this.faceIndexAt(sx), sx, sy, sz, cube);
		return CubePlanetMath.roundedCubeDistance(cube[0], cube[1] - this.centerY, cube[2], this.radius) < 0.0;
	}

	/**
	 * The storage Y under which a storage column is sea, for a vanilla sea level. The sea surface is the contour of
	 * the rounded cube at the height of the sea, so it bends over each edge with the terrain, and two faces meet at
	 * the diagonal at the same level. Past the curve of the sea, the column gets the vanilla level for no fluid.
	 */
	public int seaLevelAt(int sx, int sz, int seaLevel) {
		int rounding = CubePlanetMath.edgeRounding();
		double core = this.radius - rounding;
		double ex = Math.max(Math.abs(sx - this.offsetX(this.faceIndexAt(sx))) - core, 0.0);
		double ez = Math.max(Math.abs(sz) - core, 0.0);
		double contour = rounding + seaLevel - CubePlanetMath.TOP_SURFACE_Y;
		double rise = contour * contour - ex * ex - ez * ez;
		return rise < 0.0 ? DimensionType.MIN_Y * 2 : (int) Math.ceil(this.centerY + core + Math.sqrt(rise));
	}

	/** True when a storage column lies over the rounded part of an edge, where {@link #seaLevelAt} drops with the column. */
	public boolean inRoundedEdgeBand(int sx, int sz) {
		double core = this.radius - CubePlanetMath.edgeRounding();
		return Math.abs(sx - this.offsetX(this.faceIndexAt(sx))) > core || Math.abs(sz) > core;
	}

	/**
	 * Adds the chunks of the neighbor faces that a viewer at the storage position can see across a seam. A chunk
	 * counts when part of its wedge is within {@code viewDistance} chunks of the viewer in cube space. The test runs
	 * in the frame of the neighbor face, where the viewer stands outside the wedge, next to the shared edge.
	 */
	public void collectNeighborChunks(int sx, int sy, int sz, int viewDistance, LongSet out) {
		int face = this.faceIndexAt(sx);
		int[] cube = new int[3];
		this.toCube(face, sx, sy, sz, cube);
		int[] viewer = new int[3];
		int reach = viewDistance * 16;
		int top = this.centerY + this.radius + CubePlanetMath.ATMOSPHERE_HEIGHT;
		int half = this.spacing / 2;
		for (int g = 0; g < FACES.length; g++) {
			if (g == face || FACES[g] == FACES[face].getOpposite()) {
				continue;
			}
			this.toStorage(g, cube[0], cube[1], cube[2], viewer);
			int offset = this.offsetX(g);
			int minChunkX = Math.floorDiv(Math.max(viewer[0] - reach, offset - half), 16);
			int maxChunkX = Math.floorDiv(Math.min(viewer[0] + reach, offset + half - 1), 16);
			int minChunkZ = Math.floorDiv(Math.max(viewer[2] - reach, -half), 16);
			int maxChunkZ = Math.floorDiv(Math.min(viewer[2] + reach, half - 1), 16);
			for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
				for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
					int midX = chunkX * 16 + 8;
					int midZ = chunkZ * 16 + 8;
					long dx = Math.max(0, Math.abs(viewer[0] - midX) - 8);
					long dz = Math.max(0, Math.abs(viewer[2] - midZ) - 8);
					// The wedge floor under the chunk, lowered by the half width of the chunk.
					int floor = this.centerY + Math.max(Math.abs(midX - offset), Math.abs(midZ)) - 8;
					long dy = viewer[1] - Math.clamp(viewer[1], floor, top);
					if (dx * dx + dy * dy + dz * dz <= (long) reach * reach) {
						out.add(ChunkPos.pack(chunkX, chunkZ));
					}
				}
			}
		}
	}
}

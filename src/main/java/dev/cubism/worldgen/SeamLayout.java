package dev.cubism.worldgen;

import dev.cubism.CubismMod;
import dev.cubism.math.CubePlanetMath;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.jspecify.annotations.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.chunk.ChunkGenerator;

/**
 * Where each cube face is stored in the level. Every face is stored upright, as the pyramid wedge
 * {@code max(|vx|, |vz|) <= vy - centerY}, in its own region along +X. A storage position maps to the cube with
 * {@code cube = A_face * (storage - offset_face - C) + C}, where {@code C = (0, centerY, 0)} and
 * {@code A_face} is the rotation that turns the UP face onto the face.
 */
public final class SeamLayout {
	/** Faces in storage order. UP and EAST are next to each other so that their shared edge is the one seam. */
	public static final Direction[] FACES = {Direction.UP, Direction.EAST, Direction.DOWN, Direction.WEST, Direction.SOUTH, Direction.NORTH};

	private static volatile @Nullable SeamLayout clientOverworld;
	private static volatile @Nullable SeamLayout clientNether;
	/** The cube levels of the running server. Filled as each level is made, emptied when the server stops. */
	private static final Map<ResourceKey<Level>, SeamLayout> SERVER_LEVELS = new ConcurrentHashMap<>();
	private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();

	/** The layout that {@link #withActive} set for this thread. A null layout says that the code works for a generator without a cube. */
	private record Scope(@Nullable SeamLayout layout) {}

	/** Below the floor of the Nether, the vanilla heights of its gradients go on one block per block of depth. */
	private static final int NETHER_DEPTH_SPAN = 128;
	/** Height of the Nether cave, from its floor to the top of its bedrock roof, as in vanilla. */
	private static final int NETHER_CAVE_HEIGHT = 128;
	/** How much smaller than the overworld face the Nether face is at its lava sea, see {@link #nether}. */
	private static final int NETHER_SCALE = 2;
	/** The vanilla lava sea level of the Nether. */
	private static final int NETHER_LAVA_LEVEL = 32;

	public final int radius;
	public final int centerY;
	/** The storage Y of the top of a face: the overworld surface, or the floor of the Nether. */
	public final int surfaceY;
	/** Height of the open space above the top of a face that the region of the face holds. */
	public final int openHeight;
	/** True for the Nether, whose 3D terrain noise is read in the frame of each face, see {@link SeamDensity}. */
	public final boolean nether;
	public final SeamFold fold;
	public final int innerBedrockRadius;
	/** Distance between region centers. A wedge is 2(R + open height) + 1 blocks wide at the top, and region edges are chunk-aligned. */
	public final int spacing;
	private final int[][] rotations = new int[FACES.length][];

	public SeamLayout(int diameterChunks) {
		this(
			CubePlanetMath.getRadiusBlocks(diameterChunks), CubePlanetMath.TOP_SURFACE_Y, CubePlanetMath.ATMOSPHERE_HEIGHT,
			CubePlanetMath.getInnerBedrockRadius(diameterChunks), false
		);
	}

	private SeamLayout(int radius, int surfaceY, int openHeight, int innerBedrockRadius, boolean nether) {
		this.nether = nether;
		this.radius = radius;
		this.surfaceY = surfaceY;
		this.centerY = surfaceY - radius;
		this.openHeight = openHeight;
		this.innerBedrockRadius = innerBedrockRadius;
		this.spacing = (Math.floorDiv(this.radius + openHeight, 16) + 1) * 32;
		for (int i = 0; i < FACES.length; i++) {
			this.rotations[i] = rotation(FACES[i]);
		}
		this.fold = new SeamFold(this);
	}

	/**
	 * The Nether of a cube world: a netherrack core whose faces carry the vanilla Nether cave, from the floor at storage
	 * Y 0 to the roof at 128. Portals map a position on a face to half its distance from the face center, so the core
	 * is sized for the Nether face at the lava sea to be half as wide as the overworld face.
	 */
	public static SeamLayout nether(SeamLayout overworld) {
		int radius = overworld.radius / NETHER_SCALE - NETHER_LAVA_LEVEL;
		return new SeamLayout(radius, 0, NETHER_CAVE_HEIGHT, radius - NETHER_DEPTH_SPAN, true);
	}

	/** Blocks that a portal exit stays inside the edges of its face, so that a new portal and its platform fit on the face. */
	private static final int PORTAL_EDGE_MARGIN = 8;

	/**
	 * Where a portal of the overworld or the Nether leads in this level, for an entity at a storage position of
	 * {@code from}: the same face, with the position on the face scaled by {@link #NETHER_SCALE} about the face center.
	 * The Nether face is wider than half the overworld face above the lava sea, so its outer part maps past the edge of
	 * the overworld face. The exit is then moved in to the edge, as vanilla moves an exit in to the world border.
	 */
	public BlockPos portalExit(SeamLayout from, double x, double y, double z) {
		int face = from.faceIndexAt(Mth.floor(x));
		double scale = this.nether ? 1.0 / NETHER_SCALE : NETHER_SCALE;
		// The face width at the lava sea, which is also where most portals in the Nether stand.
		double limit = (this.nether ? this.radius + NETHER_LAVA_LEVEL : this.radius) - PORTAL_EDGE_MARGIN;
		double u = Math.clamp((x - from.offsetX(face) - 0.5) * scale, -limit, limit);
		double v = Math.clamp((z - 0.5) * scale, -limit, limit);
		return BlockPos.containing(this.offsetX(face) + 0.5 + u, y, 0.5 + v);
	}

	/**
	 * The layout that {@link #withActive} set for this thread, or null. Code that runs for a generator but does not
	 * get it, such as the chunk choice of a structure placement, reads its cube here.
	 */
	public static @Nullable SeamLayout active() {
		Scope scope = SCOPE.get();
		return scope != null ? scope.layout() : null;
	}

	/**
	 * Runs {@code action} with {@code layout} as the active layout of this thread only, see {@link #active}. The
	 * Customize screen uses it to generate structures for the settings that it shows, while no level exists.
	 */
	public static <T> T withActive(@Nullable SeamLayout layout, Supplier<T> action) {
		Scope before = SCOPE.get();
		SCOPE.set(new Scope(layout));
		try {
			return action.get();
		} finally {
			SCOPE.set(before);
		}
	}

	/**
	 * The cube of a generator. A seam generator that made no random state yet, such as the one of the Customize screen,
	 * has the layout of {@link #withActive}.
	 */
	public static @Nullable SeamLayout of(ChunkGenerator generator) {
		if (!(generator instanceof NoiseBasedChunkGenerator noise) || !isSeam(noise)) {
			return null;
		}
		SeamLayout own = ((CubeSettings.Holder) (Object) noise).cubism$layout();
		return own != null ? own : active();
	}

	/** True for a generator whose settings store a cube in seam regions. */
	public static boolean isSeam(NoiseBasedChunkGenerator generator) {
		return generator.generatorSettings().is(CubismMod.SEAM_SETTINGS) || isNether(generator);
	}

	public static boolean isNether(NoiseBasedChunkGenerator generator) {
		return generator.generatorSettings().is(CubismMod.NETHER_SEAM_SETTINGS);
	}

	/** The layout of a level whose generator stores the cube in seam regions, or null for any other level. */
	public static @Nullable SeamLayout of(ServerLevel level) {
		return of(level.getChunkSource().getGenerator());
	}

	/** The layout of a level of the running server by its key, for code that has no level, such as map data. */
	public static @Nullable SeamLayout ofServerLevel(ResourceKey<Level> dimension) {
		return SERVER_LEVELS.get(dimension);
	}

	public static void registerServerLevel(ServerLevel level) {
		SeamLayout layout = of(level);
		if (layout != null) {
			SERVER_LEVELS.put(level.dimension(), layout);
		}
	}

	public static void clearServerLevels() {
		SERVER_LEVELS.clear();
	}

	/** The layouts that the server sent to this client, or null. The client sets them on join and clears them on leave. */
	public static void setClient(@Nullable SeamLayout overworld, @Nullable SeamLayout nether) {
		clientOverworld = overworld;
		clientNether = nether;
	}

	/** The layout of a server or client level, or null when the level has no seams. */
	public static @Nullable SeamLayout of(Level level) {
		if (level instanceof ServerLevel serverLevel) {
			return of(serverLevel);
		}
		if (!level.isClientSide()) {
			return null;
		}
		return level.dimension() == Level.OVERWORLD ? clientOverworld : level.dimension() == Level.NETHER ? clientNether : null;
	}

	/** Row-major 3x3 matrix of the rotation that turns the UP face onto {@code face}. */
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
	 * The cube position, in the convention of the F3 screen, of a continuous storage position in the region of the
	 * given face: centered on the cube, the same axes on every face. The center of the cube is the center of a block.
	 */
	public Vec3 storageToCube(int faceIndex, double x, double y, double z) {
		int[] m = this.rotations[faceIndex];
		double lx = x - this.offsetX(faceIndex) - 0.5;
		double ly = y - this.centerY - 0.5;
		double lz = z - 0.5;
		return new Vec3(
			m[0] * lx + m[1] * ly + m[2] * lz + 0.5,
			m[3] * lx + m[4] * ly + m[5] * lz + 0.5,
			m[6] * lx + m[7] * ly + m[8] * lz + 0.5
		);
	}

	/** The cube position of a continuous storage position, or null when it lies in no region. */
	public @Nullable Vec3 storageToCube(Vec3 storage) {
		if (!this.inRegion(Mth.floor(storage.x), Mth.floor(storage.z))) {
			return null;
		}
		return this.storageToCube(this.faceIndexAt(Mth.floor(storage.x)), storage.x, storage.y, storage.z);
	}

	/** The storage position, in the region of the face that owns it, of a cube position in the convention of {@link #storageToCube}. */
	public Vec3 cubeToStorage(double cx, double cy, double cz) {
		double x = cx - 0.5;
		double y = cy - 0.5;
		double z = cz - 0.5;
		int face = dominantFace(x, y, z);
		int[] m = this.rotations[face];
		return new Vec3(
			m[0] * x + m[3] * y + m[6] * z + this.offsetX(face) + 0.5,
			m[1] * x + m[4] * y + m[7] * z + this.centerY + 0.5,
			m[2] * x + m[5] * y + m[8] * z + 0.5
		);
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
	 * The direction of gravity in the air at a continuous storage position, or null where it is straight down. It is
	 * the inward normal of the level through the position, one of a nested family of rounded cubes, so the field is
	 * continuous everywhere, also across the diagonals, and a jump off an edge arcs onto the next face. On a seam it
	 * is half way between the two faces, over a corner it points to the corner.
	 *
	 * <p>The surface is the level with the edge rounding. Above it each level lies a fixed distance out, so the
	 * rounding grows with the height and far out the pull is close to round. Below it each level is the surface
	 * scaled toward the center, so the rounding shrinks with the depth in proportion and is gone only at the center.
	 * The turn over an edge is then as wide as the rounding at the surface and narrows smoothly with depth, at any
	 * size of cube. A fixed distance in for the levels below the surface too would leave no rounding at all one
	 * rounding deep, where the pull flipped from one face to the next at once.
	 *
	 * <p>In the Nether, the surface of the field is the roof. With the floor as the surface, the whole cave lay above
	 * it, and the turn was as wide as the height over the floor: tens of blocks for an edge rounding of 2.
	 */
	public @Nullable Vec3 airGravity(double x, double y, double z) {
		int bx = Mth.floor(x);
		if (!this.inRegion(bx, Mth.floor(z))) {
			return null;
		}
		double cx = x - this.offsetX(this.faceIndexAt(bx)) - 0.5;
		double cy = y - this.centerY - 0.5;
		double cz = z - 0.5;
		double core = levelCore(
			Math.abs(cx), Math.abs(cy), Math.abs(cz), this.nether ? this.radius + this.openHeight : this.radius, CubePlanetMath.edgeRounding()
		);
		double dx = cx - Math.clamp(cx, -core, core);
		double dy = cy - Math.clamp(cy, -core, core);
		double dz = cz - Math.clamp(cz, -core, core);
		// Close to straight down is straight down, which saves the callers the turn on most of a wedge.
		if (dy > 0.0 && dx * dx + dz * dz < 1.0E-6 * dy * dy) {
			return null;
		}
		double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
		return length < 1.0E-9 ? null : new Vec3(-dx / length, -dy / length, -dz / length);
	}

	/**
	 * The half size of the box that the level through a point of the positive octant is rounded about. Without
	 * rounding the levels are plain cubes, and a box a hair smaller gives the direction of the largest axis.
	 */
	private static double levelCore(double ax, double ay, double az, double radius, double rounding) {
		if (rounding <= 0.0) {
			return Math.max(ax, Math.max(ay, az)) - 1.0E-6;
		}
		double surfaceCore = radius - rounding;
		if (boxDistance(ax, ay, az, surfaceCore) >= rounding) {
			return surfaceCore;
		}
		// The level of scale s has the core (1 - k) s and the rounding k s. The distance to its core less its rounding
		// falls as s grows, so halving finds the level through the point.
		double k = rounding / radius;
		double low = 0.0;
		double high = radius;
		for (int i = 0; i < 48; i++) {
			double mid = (low + high) * 0.5;
			if (boxDistance(ax, ay, az, (1.0 - k) * mid) > k * mid) {
				low = mid;
			} else {
				high = mid;
			}
		}
		return (1.0 - k) * (low + high) * 0.5;
	}

	private static double boxDistance(double ax, double ay, double az, double half) {
		double dx = Math.max(ax - half, 0.0);
		double dy = Math.max(ay - half, 0.0);
		double dz = Math.max(az - half, 0.0);
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	public static int indexOf(Direction face) {
		for (int i = 0; i < FACES.length; i++) {
			if (FACES[i] == face) {
				return i;
			}
		}
		throw new IllegalArgumentException(face.toString());
	}

	/** A structure whose middle lies deeper below the top of a face than this folds about a hinge at its depth. */
	private static final int UNDERGROUND = 32;

	/** Blocks before an edge at which the faces behind its far side start to load for a viewer. */
	private static final int CROSSING_LEAD = 96;

	/** The depth of the hinge for a structure whose middle is at {@code y}, or 0 for a structure on the surface (see {@link SeamFold.Fold#atDepth}). */
	public int hingeDepth(int y) {
		int depth = this.surfaceY - y;
		return depth > UNDERGROUND ? depth : 0;
	}

	/**
	 * Whether a piece of a structure, laid out on the flat plane of a face, cannot be folded without a cut: it lies in
	 * the corner past two edges, which has no ground on any face, or it is {@code tall} and lies on both sides of an
	 * edge row. The fold opens a gap above an edge, so a tall piece there would show a cut. An underground piece folds
	 * about the diagonal at its depth, which cuts a piece of any height along the diagonal, so only the corner counts
	 * for it, and the corner starts at the hinge.
	 */
	public boolean splitsAtEdge(BoundingBox box, boolean tall) {
		int offset = this.offsetX(this.faceIndexAt((box.minX() + box.maxX()) / 2));
		int depth = this.hingeDepth((box.minY() + box.maxY()) / 2);
		int r = this.radius - depth;
		int minX = box.minX() - offset;
		int maxX = box.maxX() - offset;
		boolean pastX = maxX > r || minX < -r;
		boolean pastZ = box.maxZ() > r || box.minZ() < -r;
		if (pastX && pastZ) {
			return true;
		}
		if (depth > 0) {
			return false;
		}
		boolean straddlesX = maxX > r && minX <= r || minX < -r && maxX >= -r;
		boolean straddlesZ = box.maxZ() > r && box.minZ() <= r || box.minZ() < -r && box.maxZ() >= -r;
		return (straddlesX || straddlesZ) && tall;
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
		double contour = rounding + seaLevel - this.surfaceY;
		double rise = contour * contour - ex * ex - ez * ez;
		return rise < 0.0 ? DimensionType.MIN_Y * 2 : (int) Math.ceil(this.centerY + core + Math.sqrt(rise));
	}

	/** True when a storage column lies over the rounded part of an edge, where {@link #seaLevelAt} drops with the column. */
	public boolean inRoundedEdgeBand(int sx, int sz) {
		return this.nearEdge(sx, sz, CubePlanetMath.edgeRounding());
	}

	/** True when a storage column lies within {@code reach} blocks of an edge of its face, measured along the face. */
	public boolean nearEdge(int sx, int sz, int reach) {
		double core = this.radius - reach;
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
		this.collectAround(face, cube, viewDistance, out);
		// A face is the opposite of the viewer face only until the viewer crosses an edge. The faces behind the neighbor
		// that the viewer is about to cross to join early, so that a crossing does not send a whole face at once.
		for (int g = 0; g < FACES.length; g++) {
			if (g != face && FACES[g] != FACES[face].getOpposite() && this.edgeDistance(g, cube) < CROSSING_LEAD) {
				this.collectAround(g, cube, viewDistance, out);
			}
		}
	}

	/** Blocks from a cube position to the edge that its face shares with the given neighbor face. */
	private int edgeDistance(int neighbor, int[] cube) {
		Direction side = FACES[neighbor];
		return this.radius - (side.getStepX() * cube[0] + side.getStepY() * (cube[1] - this.centerY) + side.getStepZ() * cube[2]);
	}

	/** The neighbor chunks of a viewer, as if the viewer stood on the given face at the cube position. */
	private void collectAround(int face, int[] cube, int viewDistance, LongSet out) {
		int[] viewer = new int[3];
		int reach = viewDistance * 16;
		int top = this.surfaceY + this.openHeight;
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

package dev.cubism.worldgen;

import dev.cubism.CubismMod;
import dev.cubism.math.CubePlanetMath;
import it.unimi.dsi.fastutil.longs.LongSet;
import dev.cubism.seam.CubeTime;
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
	/**
	 * The region of outer space, after the faces. It stores plain cube positions, in the frame of the UP face moved along
	 * X to {@link #SPACE_X}, so an entity there never crosses a diagonal. It holds what no face can store: the air above
	 * the top of the storage and the air past the strip of a face, near an edge. Only the overworld has it.
	 */
	public static final int SPACE = FACES.length;
	/** The storage X of the cube center in the space region, far from the face strips, which start near 0. */
	public static final int SPACE_X = -10_000_000;
	/** Half the width of the space region along X and Z: millions of blocks around the planet. */
	public static final int SPACE_HALF = 8_000_000;
	/** Blocks above the top of the storage where an entity moves into space, and where it comes back. */
	private static final double SPACE_LEAVE_HEIGHT = 24.0;
	private static final double SPACE_RETURN_HEIGHT = 8.0;
	/** Blocks inside the strip edge of a face where an entity moves into space, and where it comes back. */
	private static final double STRIP_LEAVE_MARGIN = 8.0;
	private static final double STRIP_RETURN_MARGIN = 16.0;
	/** Blocks between the box corners of the storage top and the exosphere, so the sphere holds the whole box. */
	private static final double EXOSPHERE_CORNER_GAP = 20.0;
	/** Blocks past the exosphere over which the pull fades out, with a smoothstep, so weightlessness comes on slowly. */
	private static final double EXOSPHERE_FADE = 120.0;

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
	private final int[][] rotations = new int[FACES.length + 1][];

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
		this.rotations[SPACE] = rotation(Direction.UP);
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

	/** The radius of the overworld cube that this cube belongs to. */
	public int overworldRadius() {
		return this.nether ? NETHER_SCALE * (this.radius + NETHER_LAVA_LEVEL) : this.radius;
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
		return faceIndex == SPACE ? SPACE_X : faceIndex * this.spacing;
	}

	/** True when this cube has a space region: the overworld. The Nether has a roof. */
	public boolean hasSpace() {
		return !this.nether;
	}

	public boolean inSpace(int storageX, int storageZ) {
		return this.hasSpace() && Math.abs(storageX - SPACE_X) < SPACE_HALF && Math.abs(storageZ) < SPACE_HALF;
	}

	/** The region of a storage X: {@link #SPACE}, else the face strip, clamped to a valid face as {@link #faceIndexAt}. */
	public int regionOfX(int storageX) {
		return this.hasSpace() && Math.abs(storageX - SPACE_X) < SPACE_HALF ? SPACE : this.faceIndexAt(storageX);
	}

	/** The region of a storage column: a face index, {@link #SPACE}, or -1 for none. */
	public int regionAt(int storageX, int storageZ) {
		return this.inRegion(storageX, storageZ) ? this.faceIndexAt(storageX) : this.inSpace(storageX, storageZ) ? SPACE : -1;
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
		int region = this.regionAt(Mth.floor(storage.x), Mth.floor(storage.z));
		return region < 0 ? null : this.storageToCube(region, storage.x, storage.y, storage.z);
	}

	/**
	 * The storage position of a cube position in the convention of {@link #storageToCube}: in the region of the face
	 * that owns it, or in space where an entity there would move to space (see {@link #spaceHandoff}).
	 */
	public Vec3 cubeToStorage(double cx, double cy, double cz) {
		double x = cx - 0.5;
		double y = cy - 0.5;
		double z = cz - 0.5;
		int face = dominantFace(x, y, z);
		if (this.hasSpace() && (Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))) > this.stratosphereTop() + SPACE_LEAVE_HEIGHT
			|| !this.storable(face, new Vec3(x, y, z), STRIP_LEAVE_MARGIN))) {
			face = SPACE;
		}
		int[] m = this.rotations[face];
		return new Vec3(
			m[0] * x + m[3] * y + m[6] * z + this.offsetX(face) + 0.5,
			m[1] * x + m[4] * y + m[7] * z + this.centerY + 0.5,
			m[2] * x + m[5] * y + m[8] * z + 0.5
		);
	}

	/**
	 * A storage position for reads of biome and sky: a position of space moves into the region of the face that owns
	 * it, at most as high as the top of the stratosphere. Space chunks are the void biome, which has a dark sky, but
	 * an entity that falls through space still has the sky and the biome of the face under it. Other positions stay.
	 */
	public Vec3 ownerStorage(Vec3 storage) {
		if (this.regionAt(Mth.floor(storage.x), Mth.floor(storage.z)) != SPACE) {
			return storage;
		}
		Vec3 p = this.cubeOffset(SPACE, storage.x, storage.y, storage.z);
		double top = this.stratosphereTop();
		double x = Math.clamp(p.x, -top, top);
		double y = Math.clamp(p.y, -top, top);
		double z = Math.clamp(p.z, -top, top);
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

	/** The smallest radius of the turn over an edge, so that a sharp edge still turns the pull and the view smoothly. */
	public static final int MIN_TURN_RADIUS = 12;

	/** The radius of the turn of the pull over an edge, at every height: the edge rounding, but at least {@link #MIN_TURN_RADIUS}. */
	public int turnRadius() {
		return Math.max(MIN_TURN_RADIUS, this.edgeRounding());
	}

	/** How much wider the turn of the pull gets for each block of height over the ground, for creative and spectator flight. */
	private static final double FLIGHT_RADIUS_RATE = 0.5;

	/**
	 * The radius of the turn of the pull for creative and spectator flight at a level (the box norm of the position,
	 * relative to the cube center). It grows with the height over the ground, as the levels of a rounded cube do, so
	 * high up the pull turns slowly over an edge and meets the round pull of space without a step. Walking and elytra
	 * keep {@link #turnRadius} at every height.
	 */
	public double flightRadius(double level) {
		return this.turnRadius() + FLIGHT_RADIUS_RATE * Math.max(0.0, level - this.radius);
	}

	/** {@link #airGravity(double, double, double, double)} with the turn of the pull, {@link #turnRadius}. */
	public @Nullable Vec3 airGravity(double x, double y, double z) {
		return this.airGravity(x, y, z, this.turnRadius());
	}

	/**
	 * The direction of gravity in the air at a continuous storage position, or null where it is straight down. It is
	 * the inward normal of the level through the position, one of a nested family of boxes with rounded edges, so the
	 * field is continuous everywhere, also across the diagonals, and a jump off an edge arcs onto the next face. On a
	 * seam it is half way between the two faces, over a corner it points to the corner.
	 *
	 * <p>Every level has the same rounding, the given radius, so the turn over an edge is equally wide at every height:
	 * in the sky, at the surface and underground. A level smaller than the radius is round, so near the center of the
	 * cube the turn narrows down to the center, where the pull points to the center from every side.
	 *
	 * <p>The rounding is a curve of the norm of power 2.5, between a circle (2) and the cubic norm (3). A circle starts the
	 * turn at its full rate, about 7 degrees per block, so the view snapped from level into a turn. The cubic norm starts
	 * at a rate of 0 but then felt too sudden on the diagonal (9 degrees per block at a radius of 8). This curve starts at
	 * a rate of 0 and is fastest on the diagonal, about 56 / radius degrees per block (4.7 at the smallest radius, 12).
	 */
	public @Nullable Vec3 airGravity(double x, double y, double z, double radius) {
		int bx = Mth.floor(x);
		if (!this.inRegion(bx, Mth.floor(z))) {
			return null;
		}
		double cx = x - this.offsetX(this.faceIndexAt(bx)) - 0.5;
		double cy = y - this.centerY - 0.5;
		double cz = z - 0.5;
		double core = levelCore(Math.abs(cx), Math.abs(cy), Math.abs(cz), radius);
		double dx = cx - Math.clamp(cx, -core, core);
		double dy = cy - Math.clamp(cy, -core, core);
		double dz = cz - Math.clamp(cz, -core, core);
		// Close to straight down is straight down, which saves the callers the turn on most of a wedge.
		if (dy > 0.0 && dx * dx + dz * dz < 1.0E-6 * dy * dy) {
			return null;
		}
		return levelNormal(dx, dy, dz);
	}

	/** The inward normal of the level through an offset {@code d} from its box: the gradient of the norm of the offset. */
	private static @Nullable Vec3 levelNormal(double dx, double dy, double dz) {
		double nx = dx * Math.sqrt(Math.abs(dx));
		double ny = dy * Math.sqrt(Math.abs(dy));
		double nz = dz * Math.sqrt(Math.abs(dz));
		double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
		return length < 1.0E-12 ? null : new Vec3(-nx / length, -ny / length, -nz / length);
	}

	/** The pull of the box levels at a position relative to the cube center (see {@link #cubeOffset}), in the cube frame. */
	private @Nullable Vec3 boxPull(double cx, double cy, double cz, double radius) {
		double core = levelCore(Math.abs(cx), Math.abs(cy), Math.abs(cz), radius);
		return levelNormal(cx - Math.clamp(cx, -core, core), cy - Math.clamp(cy, -core, core), cz - Math.clamp(cz, -core, core));
	}

	/**
	 * The position of a continuous storage position of a region relative to the cube center, in the cube frame. The
	 * center of the cube is the center of a block, as in {@link #crossingFace}.
	 */
	public Vec3 cubeOffset(int region, double x, double y, double z) {
		int[] m = this.rotations[region];
		double lx = x - this.offsetX(region) - 0.5;
		double ly = y - this.centerY - 0.5;
		double lz = z - 0.5;
		return new Vec3(m[0] * lx + m[1] * ly + m[2] * lz, m[3] * lx + m[4] * ly + m[5] * lz, m[6] * lx + m[7] * ly + m[8] * lz);
	}

	/** The box level of the top of the storage, relative to the cube center: the top of the stratosphere. */
	public double stratosphereTop() {
		return CubePlanetMath.BUILD_TOP_Y - this.centerY - 0.5;
	}

	/** The radius of the exosphere: the sphere around the box of the storage top, {@link #EXOSPHERE_CORNER_GAP} past its corners. */
	public double exosphereRadius() {
		return Math.sqrt(3.0) * this.stratosphereTop() + EXOSPHERE_CORNER_GAP;
	}

	/**
	 * The pull in space at a position relative to the cube center, in the cube frame, which is also the frame of the
	 * space region. Its length is the share of the full pull, from 1 down to 0, or null for no pull at all. Up to the
	 * top of the stratosphere it is the pull of the box levels, as over the faces. Above it the direction turns from
	 * the box normal to the center of the cube on the way out to the exosphere, so the levels round into a sphere, and
	 * past the exosphere the pull fades out.
	 */
	public @Nullable Vec3 spacePull(double cx, double cy, double cz) {
		return this.spacePull(cx, cy, cz, this.turnRadius());
	}

	/** {@link #spacePull(double, double, double)} with the radius of the turn of the box part, see {@link #flightRadius}. */
	public @Nullable Vec3 spacePull(double cx, double cy, double cz, double radius) {
		double level = Math.max(Math.abs(cx), Math.max(Math.abs(cy), Math.abs(cz)));
		double top = this.stratosphereTop();
		Vec3 box = this.boxPull(cx, cy, cz, radius);
		if (level <= top) {
			return box;
		}
		double distance = Math.sqrt(cx * cx + cy * cy + cz * cz);
		Vec3 radial = new Vec3(-cx / distance, -cy / distance, -cz / distance);
		double exosphere = this.exosphereRadius();
		if (distance >= exosphere) {
			double x = Mth.clamp((distance - exosphere) / EXOSPHERE_FADE, 0.0, 1.0);
			double share = 1.0 - x * x * (3.0 - 2.0 * x);
			return share <= 0.0 ? null : radial.scale(share);
		}
		// The share of the way from the box of the top to the sphere, along the ray from the center.
		double t = (level - top) / (exosphere * level / distance - top);
		return box == null ? radial : box.scale(1.0 - t).add(radial.scale(t)).normalize();
	}

	/**
	 * The direction of the pull at a continuous storage position of a face or of space, in the frame of its region,
	 * or null where it is straight down. On a face it is {@link #airGravity}. In space it is {@link #spacePull}, and
	 * past the exosphere, where the pull is gone, the direction to the cube center.
	 */
	public @Nullable Vec3 pullAt(double x, double y, double z, double radius) {
		if (!this.inSpace(Mth.floor(x), Mth.floor(z))) {
			int region = this.hasSpace() ? this.regionAt(Mth.floor(x), Mth.floor(z)) : -1;
			if (region >= 0) {
				// Over the top of the stratosphere the field is the one of space, also on the face that still stores the
				// entity: the hand-off to the space region, 24 blocks higher, then turns the view by nothing.
				Vec3 up = this.cubeOffset(region, x, y, z);
				if (Math.max(Math.abs(up.x), Math.max(Math.abs(up.y), Math.abs(up.z))) > this.stratosphereTop()) {
					Vec3 pull = this.spacePull(up.x, up.y, up.z, radius);
					Vec3 cube = pull == null || pull.lengthSqr() < 1.0E-6 ? up.reverse() : pull;
					if (cube.lengthSqr() < 1.0E-12) {
						return null;
					}
					cube = cube.normalize();
					int[] m = this.rotations[region];
					return new Vec3(
						m[0] * cube.x + m[3] * cube.y + m[6] * cube.z,
						m[1] * cube.x + m[4] * cube.y + m[7] * cube.z,
						m[2] * cube.x + m[5] * cube.y + m[8] * cube.z
					);
				}
			}
			return this.airGravity(x, y, z, radius);
		}
		Vec3 p = this.cubeOffset(SPACE, x, y, z);
		Vec3 pull = this.spacePull(p.x, p.y, p.z, radius);
		Vec3 direction = pull == null || pull.lengthSqr() < 1.0E-6 ? p.reverse() : pull;
		return direction.lengthSqr() < 1.0E-12 ? null : direction.normalize();
	}

	/** Blocks that the body of the horizon test is smaller than the ground, so hills and trees over the horizon stay in view. */
	private static final double HORIZON_SLACK = 64.0;

	/**
	 * False when the planet hides a whole face from a camera at {@code c}, relative to the cube center in the cube frame.
	 * The ground is a rounded cube, which is convex: a point of the ground can be seen when its normal {@code n} has
	 * {@code n.c} over the support {@code h(n)} of the body. The normals of the ground of a face are the ones that
	 * lean to the face axis at least as much as to any other axis, so it tests the corners and the edge centers of that
	 * cone of normals, and the direction of the camera inside it.
	 */
	public boolean faceCanBeSeen(int face, Vec3 c) {
		double reach = Math.max(Math.abs(c.x), Math.max(Math.abs(c.y), Math.abs(c.z)));
		if (reach <= this.radius) {
			return true;
		}
		Vec3 down = this.faceDown(face);
		double[] axis = {-down.x, -down.y, -down.z};
		double[] camera = {c.x, c.y, c.z};
		int a = Math.abs(axis[0]) > 0.5 ? 0 : Math.abs(axis[1]) > 0.5 ? 1 : 2;
		int b = (a + 1) % 3;
		int d = (a + 2) % 3;
		double sign = axis[a] > 0.0 ? 1.0 : -1.0;
		double core = this.radius - CubeTime.EDGE_BLEND - HORIZON_SLACK;
		double edge = CubeTime.EDGE_BLEND;
		for (int i = 0; i < 10; i++) {
			double[] n = new double[3];
			n[a] = sign;
			if (i < 9) {
				n[b] = i % 3 - 1;
				n[d] = i / 3 - 1;
			} else {
				// The direction of the camera, put inside the cone.
				double along = Math.max(sign * camera[a], 1.0E-9);
				n[b] = Math.clamp(camera[b] / along, -1.0, 1.0);
				n[d] = Math.clamp(camera[d] / along, -1.0, 1.0);
			}
			double length = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
			double support = core * (Math.abs(n[0]) + Math.abs(n[1]) + Math.abs(n[2])) / length + edge;
			if ((n[0] * camera[0] + n[1] * camera[1] + n[2] * camera[2]) / length > support) {
				return true;
			}
		}
		return false;
	}

	/** The pull of a face, straight down in its frame, in the cube frame: where a Steadfast lock pulls in space. */
	public Vec3 faceDown(int face) {
		int[] m = this.rotations[face];
		return new Vec3(-m[1], -m[4], -m[7]);
	}

	/**
	 * Where an entity whose center is at a storage position of a region moves for space, or -1 when it stays. Above
	 * the top of the storage, or near the edge of its strip, an entity on a face moves into space. In space it comes
	 * back once it is low enough and the face can store it well inside the strip: the face that owns its position, or
	 * {@code lockedFace} for a Steadfast lock. The gaps between the two limits keep it from moving back and forth.
	 * {@code slack} widens the limits to leave, for the server, which only follows a player that its client moved.
	 */
	public int spaceHandoff(int region, double x, double y, double z, int lockedFace, double slack) {
		if (region < 0 || !this.hasSpace()) {
			return -1;
		}
		Vec3 p = this.cubeOffset(region, x, y, z);
		double level = Math.max(Math.abs(p.x), Math.max(Math.abs(p.y), Math.abs(p.z)));
		if (region != SPACE) {
			return level > this.stratosphereTop() + SPACE_LEAVE_HEIGHT + slack || !this.storable(region, p, STRIP_LEAVE_MARGIN - slack) ? SPACE : -1;
		}
		if (level >= this.stratosphereTop() + SPACE_RETURN_HEIGHT) {
			return -1;
		}
		int face = lockedFace >= 0 ? lockedFace : dominantFace(p.x, p.y, p.z);
		return this.storable(face, p, STRIP_RETURN_MARGIN) ? face : -1;
	}

	/** The region that a crossing to {@code face} moves an entity at a storage position to: the face, or space when the face cannot store it. */
	public int crossingTarget(int region, int face, double x, double y, double z) {
		return this.hasSpace() && !this.storable(face, this.cubeOffset(region, x, y, z), STRIP_LEAVE_MARGIN) ? SPACE : face;
	}

	/** True when a face stores a position relative to the cube center more than {@code margin} blocks inside its strip. */
	private boolean storable(int face, Vec3 p, double margin) {
		int[] m = this.rotations[face];
		double u = m[0] * p.x + m[3] * p.y + m[6] * p.z;
		double w = m[2] * p.x + m[5] * p.y + m[8] * p.z;
		return Math.max(Math.abs(u), Math.abs(w)) < this.spacing / 2.0 - margin;
	}

	/**
	 * The half size of the box that the level through a point of the positive octant is rounded about: the box at the
	 * norm distance of the radius from the point. Without a radius the levels are plain cubes, and a box a hair smaller
	 * gives the direction of the largest axis.
	 */
	private static double levelCore(double ax, double ay, double az, double radius) {
		double max = Math.max(ax, Math.max(ay, az));
		if (radius <= 0.0) {
			return max - 1.0E-6;
		}
		double mid = Math.max(Math.min(ax, ay), Math.min(Math.max(ax, ay), az));
		// Over the flat part of a level the box is the radius below the point.
		if (mid <= max - radius) {
			return max - radius;
		}
		if (power(ax) + power(ay) + power(az) <= power(radius)) {
			return 0.0;
		}
		// The distance to the box falls as the box grows: it is at least the radius at the low end, 0 at the high end.
		double low = Math.max(0.0, max - radius);
		double high = max;
		for (int i = 0; i < 40; i++) {
			double half = (low + high) * 0.5;
			if (boxPower(ax, ay, az, half) > power(radius)) {
				low = half;
			} else {
				high = half;
			}
		}
		return (low + high) * 0.5;
	}

	/** The norm distance from a point to the box, to the power 2.5. */
	private static double boxPower(double ax, double ay, double az, double half) {
		return power(Math.max(ax - half, 0.0)) + power(Math.max(ay - half, 0.0)) + power(Math.max(az - half, 0.0));
	}

	/** A value of at least 0 to the power 2.5, without the cost of {@link Math#pow}: the turn runs for every block shade. */
	private static double power(double value) {
		return value * value * Math.sqrt(value);
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

	/**
	 * The depth of the hinge for a structure whose middle is at {@code y}, or 0 for a structure on the surface (see
	 * {@link SeamFold.Fold#atDepth}). In the Nether every structure is inside the cave, so it folds about the diagonal
	 * at its height, which is a negative depth. A fold about the edge of the floor would open a gap as wide as the height.
	 */
	public int hingeDepth(int y) {
		int depth = this.surfaceY - y;
		if (this.nether) {
			return depth;
		}
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
		if (depth != 0) {
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

	/**
	 * The radius of the rounded edges and corners of this cube, which gravity and the camera also follow. The Nether
	 * has the smallest rounding at every setting: its cave noise is flat on each face, and a rounded floor sinks under
	 * storage Y 0. A rounded Nether needs storage below its floor, as the overworld has under its surface.
	 */
	public int edgeRounding() {
		return this.nether ? CubePlanetMath.MIN_EDGE_ROUNDING : CubePlanetMath.edgeRounding();
	}

	/** True when the storage position maps to a cube position below the rounded cube surface. */
	public boolean belowCubeSurface(int sx, int sy, int sz) {
		int[] cube = new int[3];
		this.toCube(this.faceIndexAt(sx), sx, sy, sz, cube);
		return CubePlanetMath.roundedCubeDistance(cube[0], cube[1] - this.centerY, cube[2], this.radius, this.edgeRounding()) < 0.0;
	}

	/**
	 * The storage Y under which a storage column is sea, for a vanilla sea level. The sea surface is the contour of
	 * the rounded cube at the height of the sea, so it bends over each edge with the terrain, and two faces meet at
	 * the diagonal at the same level. Past the curve of the sea, the column gets the vanilla level for no fluid.
	 */
	public int seaLevelAt(int sx, int sz, int seaLevel) {
		int rounding = this.edgeRounding();
		double core = this.radius - rounding;
		double ex = Math.max(Math.abs(sx - this.offsetX(this.faceIndexAt(sx))) - core, 0.0);
		double ez = Math.max(Math.abs(sz) - core, 0.0);
		double contour = rounding + seaLevel - this.surfaceY;
		double rise = contour * contour - ex * ex - ez * ez;
		return rise < 0.0 ? DimensionType.MIN_Y * 2 : (int) Math.ceil(this.centerY + core + Math.sqrt(rise));
	}

	/** True when a storage column lies over the rounded part of an edge, where {@link #seaLevelAt} drops with the column. */
	public boolean inRoundedEdgeBand(int sx, int sz) {
		return this.nearEdge(sx, sz, this.edgeRounding());
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
		int region = this.regionAt(sx, sz);
		int face = region < 0 ? this.faceIndexAt(sx) : region;
		int[] cube = new int[3];
		this.toCube(face, sx, sy, sz, cube);
		this.collectAround(face, cube, viewDistance, out);
		if (face == SPACE) {
			return;
		}
		if (this.hasSpace()) {
			this.collectSpace(face, cube, Math.min(viewDistance, SPACE_VIEW_CHUNKS), out);
		}
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

	/**
	 * Chunks of space that a viewer on a face sees within this many chunks. Space shows only near its lower edge, and
	 * a face viewer loads its chunks only there, as each chunk costs the same as a chunk of ground to send. They let a
	 * viewer see the entities in space, and they are there when the viewer moves into space.
	 */
	private static final int SPACE_VIEW_CHUNKS = 6;

	/** Adds the chunks of space around the cube position of a viewer on a face, when space is within the distance. */
	private void collectSpace(int face, int[] cube, int viewDistance, LongSet out) {
		int reach = viewDistance * 16;
		Vec3 p = new Vec3(cube[0], cube[1] - this.centerY, cube[2]);
		double level = Math.max(Math.abs(p.x), Math.max(Math.abs(p.y), Math.abs(p.z)));
		if (level + reach < this.stratosphereTop() && this.storable(face, p, reach + STRIP_LEAVE_MARGIN)) {
			return;
		}
		int x = cube[0] + SPACE_X;
		int z = cube[2];
		for (int chunkX = Math.floorDiv(x - reach, 16); chunkX <= Math.floorDiv(x + reach, 16); chunkX++) {
			for (int chunkZ = Math.floorDiv(z - reach, 16); chunkZ <= Math.floorDiv(z + reach, 16); chunkZ++) {
				long dx = Math.max(0, Math.abs(x - (chunkX * 16 + 8)) - 8);
				long dz = Math.max(0, Math.abs(z - (chunkZ * 16 + 8)) - 8);
				if (dx * dx + dz * dz <= (long) reach * reach) {
					out.add(ChunkPos.pack(chunkX, chunkZ));
				}
			}
		}
	}

	/** The neighbor chunks of a viewer, as if the viewer stood on the given face at the cube position. In space every face counts. */
	private void collectAround(int face, int[] cube, int viewDistance, LongSet out) {
		int[] viewer = new int[3];
		int reach = viewDistance * 16;
		int top = this.surfaceY + this.openHeight;
		int half = this.spacing / 2;
		for (int g = 0; g < FACES.length; g++) {
			if (face != SPACE && (g == face || FACES[g] == FACES[face].getOpposite())) {
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

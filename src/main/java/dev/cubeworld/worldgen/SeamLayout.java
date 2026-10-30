package dev.cubeworld.worldgen;

import dev.cubeworld.CubeWorldMod;
import dev.cubeworld.math.CubePlanetMath;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
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

	public final int radius;
	public final int centerY;
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
		int ax = Math.abs(x);
		int ay = Math.abs(y);
		int az = Math.abs(z);
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

	/** True when the storage position maps to a cube position below the cube surface (Chebyshev distance under R). */
	public boolean belowCubeSurface(int sx, int sy, int sz) {
		int[] cube = new int[3];
		this.toCube(this.faceIndexAt(sx), sx, sy, sz, cube);
		int distance = Math.max(Math.abs(cube[0]), Math.max(Math.abs(cube[1] - this.centerY), Math.abs(cube[2])));
		return distance < this.radius;
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

package dev.cubism.client.render;

import dev.cubism.client.mixin.CloudRendererAccessor;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Vanilla cloud layers, one per face, that fold over the edges of the cube. Each layer stops at the wedge of its face.
 * The camera face and its four neighbors draw, each turned into the frame of the camera face like the chunks of the
 * seam.
 *
 * <p>As in vanilla, one pattern of cells pans across the sky and never changes shape. It runs like a belt around the X
 * axis of the cube: cut the cloud shell with a plane of constant X, and on the faces UP, SOUTH, DOWN and NORTH the cut
 * is a square loop that the clouds slide along. The pattern is the belt unrolled: its rows go along X, and its columns
 * go along the loop, which wraps. The belt lines up across its four edges. EAST continues the pattern of UP over their
 * edge, and WEST continues DOWN, so the two move in opposite directions. Each lines up with the belt on that one edge
 * only. On every face the cell grid slides with the pattern, as vanilla slides its grid.
 *
 * <p>All state is used on the render thread only.
 */
public final class CubeClouds {
	public record Placement(Matrix4f rotation, Vector3f cameraInFace) {}

	private static final int CELL = 12;
	/** Cells per side of the texture of a layer. A face at the cloud height is about 54 cells wide. */
	private static final int SIZE = 64;
	/** Vanilla clouds move this many blocks per tick. */
	private static final double SPEED = 0.03;
	/** The part of the pattern that is cloud, as in the vanilla cloud texture. */
	private static final double COVERAGE = 0.27;
	private static final long PATTERN_SEED = 0x636C6F7564L;
	private static final int WHITE = 0xFFFFFFFF;
	/**
	 * The faces turn into each other about the center of the local block (0, 0, 0), not about its corner, as the seam
	 * motion does. Cube positions here are measured from that center.
	 */
	private static final double CENTER = 0.5;
	/** Vanilla puts the clouds this far along Z from the camera. */
	private static final double VANILLA_Z_OFFSET = 3.96;

	private static final CloudRenderer[] LAYERS = new CloudRenderer[SeamLayout.FACES.length];
	private static final @Nullable Placement[] PLACEMENTS = new Placement[SeamLayout.FACES.length];
	/** What the texture of each layer shows, to rebuild it only when that changes. */
	private static final long[] TEXTURE_KEYS = new long[SeamLayout.FACES.length];
	private static final List<CloudRenderer> SHOWN = new ArrayList<>();

	/** The cloud cells of the unrolled belt: rows along X from {@link #firstRow}, columns along the loop. */
	private static boolean @Nullable [][] pattern;
	private static int firstRow;
	private static float fadeEnd;

	static {
		Arrays.fill(TEXTURE_KEYS, Long.MIN_VALUE);
	}

	private CubeClouds() {}

	/** True while the cube draws its own layers in place of the one layer of vanilla. */
	public static boolean active() {
		return !SHOWN.isEmpty();
	}

	public static @Nullable Placement placement(Object renderer) {
		for (int face = 0; face < LAYERS.length; face++) {
			if (LAYERS[face] == renderer) {
				return PLACEMENTS[face];
			}
		}
		return null;
	}

	/**
	 * The distance where the clouds fade out. Vanilla fades them over the cloud distance option, which is wider than the
	 * cube, so without this the clouds of the far faces stay half visible.
	 */
	public static float fadeEnd() {
		return fadeEnd;
	}

	/** In the OIT passes, only the first layer copies the main depth. */
	public static boolean copiesDepth(Object renderer) {
		return SHOWN.isEmpty() || SHOWN.getFirst() == renderer;
	}

	/**
	 * Prepares the layers for the frame. Returns false outside the cube, where the vanilla layer draws. The arguments
	 * are those of {@link CloudRenderer#prepare}.
	 */
	public static boolean prepare(
		@Nullable SeamLayout layout, int color, CloudStatus status, float bottomY, int range, Vec3 cameraPos, long gameTime, float partialTicks
	) {
		SHOWN.clear();
		if (layout == null) {
			return false;
		}
		// The loop at the cloud height must be a whole number of cells long, so that the pattern wraps on itself.
		// That needs a height in steps of 1.5 blocks over the center.
		double height = Math.round((bottomY - layout.centerY - CENTER) / 1.5) * 1.5;
		int loopCells = (int) Math.round(8.0 * height / CELL);
		// EAST and WEST continue the rows past the belt, as far as three times the height.
		int rows = 2 * Mth.ceil(3.0 * height / CELL) + 2;
		boolean[][] cells = pattern;
		if (cells == null || cells.length != rows || cells[0].length != loopCells) {
			cells = buildPattern(rows, loopCells);
			pattern = cells;
			firstRow = -rows / 2;
			Arrays.fill(TEXTURE_KEYS, Long.MIN_VALUE);
		}
		fadeEnd = (float) (2.0 * height);
		long loopTicks = Math.round(8.0 * height / SPEED);
		double drift = (Math.floorMod(gameTime, loopTicks) + partialTicks) * SPEED;
		float layerY = (float) (layout.centerY + CENTER + height);

		int cameraFace = layout.regionOfX(Mth.floor(cameraPos.x));
		for (int face = 0; face < LAYERS.length; face++) {
			int[] toCamera = layout.motion(face, cameraFace);
			PLACEMENTS[face] = null;
			// The opposite face is under the camera face, out of sight.
			if (toCamera[4] == -1) {
				continue;
			}
			CloudRenderer layer = LAYERS[face];
			if (layer == null) {
				layer = new CloudRenderer();
				LAYERS[face] = layer;
			}
			Vec3 camera = SeamInteraction.move(layout.motion(cameraFace, face), cameraPos);
			double localX = camera.x - layout.offsetX(face);
			double localZ = camera.z;
			int[] m = layout.rotation(face);
			// Each local axis runs along one pattern axis. The grid shifts so that its cell borders fall on the cell
			// borders of the pattern, which slide with the drift along the loop.
			double[] origin = patternAt(m, height, 0.0, 0.0);
			double[] alongX = patternAt(m, height, 1.0, 0.0);
			double[] alongZ = patternAt(m, height, 0.0, 1.0);
			double shiftX = gridShift(origin, alongX, drift);
			double shiftZ = gridShift(origin, alongZ, drift);
			int firstX = Mth.floor((-height - CELL - shiftX) / CELL);
			int firstZ = Mth.floor((-height - CELL - shiftZ) / CELL);
			long key = ((long) firstX << 32) ^ (firstZ & 0xFFFFFFFFL);
			CloudRendererAccessor access = (CloudRendererAccessor) layer;
			if (TEXTURE_KEYS[face] != key || access.cubism$texture() == null) {
				TEXTURE_KEYS[face] = key;
				access.cubism$setTexture(buildTexture(m, height, drift, shiftX, shiftZ, firstX, firstZ, cells));
				layer.markForRebuild();
			}

			Matrix4f rotation = new Matrix4f(
				toCamera[0], toCamera[3], toCamera[6], 0,
				toCamera[1], toCamera[4], toCamera[7], 0,
				toCamera[2], toCamera[5], toCamera[8], 0,
				0, 0, 0, 1
			);
			PLACEMENTS[face] = new Placement(rotation, new Vector3f((float) (localX - CENTER), (float) (camera.y - layout.centerY - CENTER), (float) (localZ - CENTER)));
			// Vanilla finds the cloud cell of a point from the camera position it gets, so this position moves the grid
			// by the shift. Vanilla adds its own drift from the game time, so the time is zero, and it adds an offset
			// on Z.
			Vec3 cellOrigin = new Vec3(localX - shiftX - partialTicks * SPEED, camera.y, localZ - shiftZ - VANILLA_Z_OFFSET);
			layer.prepare(FaceLightmaps.cloudColor(face, color, partialTicks), status, layerY, range, cellOrigin, 0L, partialTicks);
			SHOWN.add(layer);
		}
		return true;
	}

	/**
	 * The pattern position of a point of the cloud layer of a face, from its local X and Z: the row coordinate, then
	 * the distance along the loop. On the belt the row coordinate is cube X, and the loop distance is zero at the center
	 * of UP and grows by twice the height per face, in the direction of X cross the face normal. A cap continues the
	 * rows of its belt face past their shared edge, with the same loop distance.
	 */
	private static double[] patternAt(int[] m, double height, double localX, double localZ) {
		double x = localX - CENTER;
		double z = localZ - CENTER;
		double cubeX = m[0] * x + m[1] * height + m[2] * z;
		double cubeY = m[3] * x + m[4] * height + m[5] * z;
		double cubeZ = m[6] * x + m[7] * height + m[8] * z;
		if (m[1] == 1) {
			return new double[] {2.0 * height - cubeY, cubeZ};
		}
		if (m[1] == -1) {
			return new double[] {-2.0 * height - cubeY, 4.0 * height - cubeZ};
		}
		double loopCenter = m[4] == 1 ? 0.0 : m[7] == 1 ? 2.0 * height : m[4] == -1 ? 4.0 * height : 6.0 * height;
		return new double[] {cubeX, loopCenter - m[7] * cubeY + m[4] * cubeZ};
	}

	/** The shift of the grid along one local axis, which runs along either the rows or the loop of the pattern. */
	private static double gridShift(double[] origin, double[] step, double drift) {
		double loop = step[1] - origin[1];
		return loop != 0.0 ? loop * (drift - origin[1]) : -(step[0] - origin[0]) * origin[0];
	}

	/**
	 * Cell (a, b) of a face spans local X from 12a + shiftX and local Z from 12b + shiftZ. The shifts put the cell
	 * borders on the pattern borders, so the pattern cell of each grid cell stays the same as the grid slides.
	 */
	private static CloudRenderer.TextureData buildTexture(
		int[] m, double height, double drift, double shiftX, double shiftZ, int firstX, int firstZ, boolean[][] cells
	) {
		boolean[] cloud = new boolean[SIZE * SIZE];
		for (int b = firstZ; b < firstZ + SIZE; b++) {
			for (int a = firstX; a < firstX + SIZE; a++) {
				double[] center = patternAt(m, height, a * CELL + CELL / 2.0 + shiftX, b * CELL + CELL / 2.0 + shiftZ);
				cloud[index(a, b)] = cell(cells, Mth.floor(center[0] / CELL), Mth.floor((center[1] - drift) / CELL));
			}
		}
		return texture(cloud, firstX, firstZ);
	}

	private static boolean cell(boolean[][] cells, int row, int column) {
		int i = row - firstRow;
		return i >= 0 && i < cells.length && cells[i][Math.floorMod(column, cells[i].length)];
	}

	private static CloudRenderer.TextureData texture(boolean[] cloud, int firstX, int firstZ) {
		long[] packed = new long[SIZE * SIZE];
		for (int z = firstZ; z < firstZ + SIZE; z++) {
			for (int x = firstX; x < firstX + SIZE; x++) {
				if (cloud[index(x, z)]) {
					packed[index(x, z)] = CloudRendererAccessor.cubism$packCell(
						WHITE, !cloud[index(x, z - 1)], !cloud[index(x + 1, z)], !cloud[index(x, z + 1)], !cloud[index(x - 1, z)]
					);
				}
			}
		}
		return new CloudRenderer.TextureData(packed, SIZE, SIZE);
	}

	private static int index(int x, int z) {
		return Math.floorMod(x, SIZE) + Math.floorMod(z, SIZE) * SIZE;
	}

	/**
	 * Clumps of short bars, longer along the loop than across it, like the clouds of the vanilla texture, which are
	 * longer along their motion.
	 */
	private static boolean[][] buildPattern(int rows, int columns) {
		RandomSource random = RandomSource.create(PATTERN_SEED);
		boolean[][] cells = new boolean[rows][columns];
		int filled = 0;
		int target = (int) (COVERAGE * rows * columns);
		while (filled < target) {
			int centerRow = random.nextInt(rows);
			int centerColumn = random.nextInt(columns);
			double spreadRow = 0.7 + random.nextDouble() * 1.5;
			double spreadColumn = spreadRow * (1.8 + random.nextDouble() * 0.8);
			int bars = 4 + random.nextInt(13);
			for (int bar = 0; bar < bars; bar++) {
				int row0 = (int) Math.round(centerRow + random.nextGaussian() * spreadRow);
				int column0 = (int) Math.round(centerColumn + random.nextGaussian() * spreadColumn);
				int thickness = 2 + random.nextInt(2);
				int length = 3 + random.nextInt(5);
				for (int row = row0; row < row0 + thickness; row++) {
					if (row < 0 || row >= rows) {
						continue;
					}
					for (int column = column0 - length / 2; column < column0 - length / 2 + length; column++) {
						int wrapped = Math.floorMod(column, columns);
						if (!cells[row][wrapped]) {
							cells[row][wrapped] = true;
							filled++;
						}
					}
				}
			}
		}
		return cells;
	}

	/** Runs a draw for each layer of the frame. */
	public static void forEachShown(Consumer<CloudRenderer> draw) {
		SHOWN.forEach(draw);
	}

	public static void endFrame() {
		for (CloudRenderer layer : LAYERS) {
			if (layer != null) {
				layer.endFrame();
			}
		}
	}

	public static void close() {
		for (int face = 0; face < LAYERS.length; face++) {
			if (LAYERS[face] != null) {
				LAYERS[face].close();
				LAYERS[face] = null;
			}
		}
		Arrays.fill(TEXTURE_KEYS, Long.MIN_VALUE);
		SHOWN.clear();
	}
}

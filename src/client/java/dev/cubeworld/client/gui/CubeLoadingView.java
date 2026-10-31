package dev.cubeworld.client.gui;

import dev.cubeworld.client.mixin.LevelLoadingScreenAccessor;
import dev.cubeworld.worldgen.CubePregen;
import dev.cubeworld.worldgen.SeamLayout;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.joml.Matrix3x2f;

import java.util.Arrays;

/**
 * Draws the cube in isometric view, with the spawn face on top, in the colors and cell size of the vanilla loading
 * map. The three back faces are drawn first, seen through the cube. A column of a front face covers them when its
 * generation starts.
 */
public final class CubeLoadingView {
	/** Size of a chunk column in GUI pixels, as on the vanilla map. Smaller only if the cube does not fit. */
	private static final float CELL_SIZE = 2.0F;
	private static final float COS_30 = 0.8660254F;
	private static final int FRONT_FACES = 3;

	private static CubePregen.Progress cachedFor;
	private static int[][] cells;

	private CubeLoadingView() {
	}

	/** Draws the cube centered on the point, at most {@code maxHalfHeight} pixels above and below it. Returns the half height. */
	public static int draw(GuiGraphicsExtractor graphics, CubePregen.Progress progress, int xCenter, int yCenter, int maxHalfHeight) {
		int d = 2 * progress.layout.radius / 16;
		float h = d / 2.0F;
		if (cachedFor != progress) {
			cells = mapCells(progress, d);
			cachedFor = progress;
		}
		Object2IntMap<ChunkStatus> colors = LevelLoadingScreenAccessor.cubeworld$colors();
		// The cube is 2d cells high: the top face d / 2 above the side faces, and the side faces d high.
		float scale = Math.min(CELL_SIZE, Math.max(1, maxHalfHeight) / (float) d);
		// Cell (s, t) of each face to the screen, with the cube center at the origin. See mapCells for the faces. Every
		// determinant is positive, because the GUI culls faces that are turned away.
		Matrix3x2f[] faces = {
			new Matrix3x2f(COS_30, 0.5F, -COS_30, 0.5F, 0.0F, -2.0F * h),
			new Matrix3x2f(COS_30, -0.5F, 0.0F, 1.0F, 0.0F, 0.0F),
			new Matrix3x2f(COS_30, 0.5F, 0.0F, 1.0F, -2.0F * h * COS_30, -h),
			new Matrix3x2f(COS_30, 0.5F, -COS_30, 0.5F, 0.0F, 0.0F),
			new Matrix3x2f(COS_30, -0.5F, 0.0F, 1.0F, -2.0F * h * COS_30, -h),
			new Matrix3x2f(COS_30, 0.5F, 0.0F, 1.0F, 0.0F, -2.0F * h)
		};
		for (int v = faces.length - 1; v >= 0; v--) {
			boolean front = v < FRONT_FACES;
			graphics.pose().pushMatrix();
			graphics.pose().translate(xCenter, yCenter).scale(scale).mul(faces[v]);
			int[] table = cells[v];
			for (int s = 0; s < d; s++) {
				for (int t = 0; t < d; t++) {
					int at = (s * d + t) * 3;
					ChunkStatus status = progress.get(table[at], table[at + 1], table[at + 2]);
					if (status != null || !front) {
						graphics.fill(s, t, s + 1, t + 1, ARGB.opaque(colors.getInt(status)));
					}
				}
			}
			graphics.pose().popMatrix();
		}
		return Math.round(d * scale);
	}

	/**
	 * For each face and cell, the face region and column offsets of the column under the cell. The view axes are
	 * x = a, y = n (the spawn face) and z = b, with b = a x n so the view is not mirrored. Faces 0 to 2 are the top,
	 * x = +h and z = +h, seen from the corner (+, +, +). Faces 3 to 5 are the bottom, x = -h and z = -h, seen from
	 * inside the cube.
	 */
	private static int[][] mapCells(CubePregen.Progress progress, int d) {
		SeamLayout layout = progress.layout;
		Vec3i n = progress.spawnFace.getUnitVec3i();
		Vec3i a = Arrays.stream(Direction.values()).map(Direction::getUnitVec3i)
			.filter(axis -> axis.getX() * n.getX() + axis.getY() * n.getY() + axis.getZ() * n.getZ() == 0).findFirst().orElseThrow();
		Vec3i b = a.cross(n);
		Vec3i[] normals = {n, a, b, n.multiply(-1), a.multiply(-1), b.multiply(-1)};
		int half = d / 2;
		int big = 16 * half;
		int[][] tables = new int[normals.length][d * d * 3];
		int[] out = new int[3];
		for (int v = 0; v < normals.length; v++) {
			for (int s = 0; s < d; s++) {
				for (int t = 0; t < d; t++) {
					// Cell centers in sixteenths of a cell, that is in blocks.
					int across = 16 * (s - half) + 8;
					int reverse = -across;
					int down = 16 * (half - t) - 8;
					int sideways = -down;
					Vec3i p = switch (v) {
						case 0 -> n.multiply(big).offset(a.multiply(across)).offset(b.multiply(sideways));
						case 1 -> a.multiply(big).offset(n.multiply(down)).offset(b.multiply(reverse));
						case 2 -> b.multiply(big).offset(a.multiply(across)).offset(n.multiply(down));
						case 3 -> n.multiply(-big).offset(a.multiply(across)).offset(b.multiply(sideways));
						case 4 -> a.multiply(-big).offset(b.multiply(reverse)).offset(n.multiply(down));
						default -> b.multiply(-big).offset(a.multiply(across)).offset(n.multiply(down));
					};
					column(layout, normals[v], p, out);
					System.arraycopy(out, 0, tables[v], (s * d + t) * 3, 3);
				}
			}
		}
		return tables;
	}

	/** Face region and column offsets, as {@link CubePregen.Progress#get} takes them, of a cube surface point. */
	private static void column(SeamLayout layout, Vec3i normal, Vec3i cube, int[] out) {
		int face = Arrays.asList(SeamLayout.FACES).indexOf(Direction.getNearest(normal.getX(), normal.getY(), normal.getZ(), null));
		int[] storage = new int[3];
		layout.toStorage(face, cube.getX(), cube.getY() + layout.centerY, cube.getZ(), storage);
		out[0] = face;
		out[1] = Math.floorDiv(storage[0], 16) - Math.floorDiv(layout.offsetX(face) - layout.spacing / 2, 16);
		out[2] = Math.floorDiv(storage[2], 16) - Math.floorDiv(-layout.spacing / 2, 16);
	}
}

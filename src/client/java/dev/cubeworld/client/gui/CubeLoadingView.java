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
 * Draws a corner of the cube in isometric view: the spawn face on top and two of its neighbors. Each square is a
 * chunk column of the face surface, in the colors of the vanilla loading map. A square also shows the column straight
 * across the cube, on the hidden face, at half weight, so that one view shows all six faces.
 */
public final class CubeLoadingView {
	public static final int MAX_HALF_HEIGHT = 90;
	private static final float COS_30 = 0.8660254F;
	/** Brightness of the top face and the two side faces, so that the corner reads as a solid. */
	private static final float[] SHADE = {1.0F, 0.8F, 0.62F};

	private static CubePregen.Progress cachedFor;
	private static int[][] cells;

	private CubeLoadingView() {
	}

	public static void draw(GuiGraphicsExtractor graphics, CubePregen.Progress progress, int xCenter, int yCenter, int halfHeight) {
		SeamLayout layout = progress.layout;
		int d = 2 * layout.radius / 16;
		float h = d / 2.0F;
		if (cachedFor != progress) {
			cells = mapCells(progress, d);
			cachedFor = progress;
		}
		Object2IntMap<ChunkStatus> colors = LevelLoadingScreenAccessor.cubeworld$colors();
		int empty = colors.getInt(ChunkStatus.EMPTY);
		float scale = halfHeight / (2.0F * h);
		// Cell (s, t) of each face to the screen, with the cube center at the origin. See mapCells for the faces.
		Matrix3x2f[] faces = {
			new Matrix3x2f(COS_30, 0.5F, -COS_30, 0.5F, 0.0F, -2.0F * h),
			new Matrix3x2f(-COS_30, 0.5F, 0.0F, 1.0F, 2.0F * h * COS_30, -h),
			new Matrix3x2f(COS_30, 0.5F, 0.0F, 1.0F, -2.0F * h * COS_30, -h)
		};
		for (int v = 0; v < 3; v++) {
			graphics.pose().pushMatrix();
			graphics.pose().translate(xCenter, yCenter).scale(scale).mul(faces[v]);
			int[] table = cells[v];
			for (int s = 0; s < d; s++) {
				for (int t = 0; t < d; t++) {
					int at = (s * d + t) * 6;
					int near = color(colors, empty, progress.get(table[at], table[at + 1], table[at + 2]));
					int far = color(colors, empty, progress.get(table[at + 3], table[at + 4], table[at + 5]));
					graphics.fill(s, t, s + 1, t + 1, ARGB.opaque(ARGB.scaleRGB(ARGB.average(near, far), SHADE[v])));
				}
			}
			graphics.pose().popMatrix();
		}
	}

	private static int color(Object2IntMap<ChunkStatus> colors, int empty, ChunkStatus status) {
		return status == null ? empty : colors.getInt(status);
	}

	/**
	 * For each visible face and cell, the face region and column offsets of the column under the cell and of the column
	 * straight across the cube. The view axes are x = a, y = n (the spawn face) and z = b, with b = a x n so the view
	 * is not mirrored. Face 0 is the top, face 1 is x = +h and face 2 is z = +h, each seen from the corner (+, +, +).
	 */
	private static int[][] mapCells(CubePregen.Progress progress, int d) {
		SeamLayout layout = progress.layout;
		Vec3i n = progress.spawnFace.getUnitVec3i();
		Vec3i a = Arrays.stream(Direction.values()).map(Direction::getUnitVec3i)
			.filter(axis -> axis.getX() * n.getX() + axis.getY() * n.getY() + axis.getZ() * n.getZ() == 0).findFirst().orElseThrow();
		Vec3i b = a.cross(n);
		Vec3i[] normals = {n, a, b};
		int half = d / 2;
		int[][] tables = new int[3][d * d * 6];
		int[] out = new int[3];
		for (int v = 0; v < 3; v++) {
			for (int s = 0; s < d; s++) {
				for (int t = 0; t < d; t++) {
					// Cell centers in sixteenths of a cell, that is in blocks.
					int across = 16 * (s - half) + 8;
					int down = 16 * (half - t) - 8;
					int sideways = 16 * (t - half) + 8;
					Vec3i p = switch (v) {
						case 0 -> n.multiply(16 * half).offset(a.multiply(across)).offset(b.multiply(sideways));
						case 1 -> a.multiply(16 * half).offset(n.multiply(down)).offset(b.multiply(across));
						default -> b.multiply(16 * half).offset(a.multiply(across)).offset(n.multiply(down));
					};
					Vec3i normal = normals[v];
					int at = (s * d + t) * 6;
					column(layout, normal, p, out);
					System.arraycopy(out, 0, tables[v], at, 3);
					column(layout, new Vec3i(-normal.getX(), -normal.getY(), -normal.getZ()), p.offset(normal.multiply(-32 * half)), out);
					System.arraycopy(out, 0, tables[v], at + 3, 3);
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

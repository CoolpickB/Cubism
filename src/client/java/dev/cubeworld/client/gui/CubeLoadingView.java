package dev.cubeworld.client.gui;

import dev.cubeworld.client.mixin.LevelLoadingScreenAccessor;
import dev.cubeworld.debug.LoadTrace;
import dev.cubeworld.worldgen.CubePregen;
import dev.cubeworld.worldgen.CubeSettings;
import dev.cubeworld.worldgen.SeamLayout;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Arrays;

/**
 * Draws the pre-generation of the cube in the colors and cell size of the vanilla loading map. The whole cube shows as
 * a flat cross of its six faces, which folds into the cube when the generation is done. The spawn face shows as one
 * square with the strips of its neighbor faces around it.
 *
 * <p>Coordinates are in cells, with x to the right, y down the screen and z toward the viewer. The spawn face is the
 * square z = h, and the folded cube is behind it.
 */
public final class CubeLoadingView {
	/** Size of a chunk column in GUI pixels, as on the vanilla map. Smaller only if the view does not fit. */
	private static final float CELL_SIZE = 2.0F;
	private static final long FOLD_MS = 700L;
	/** The folded cube stays on the screen this long before the game starts. */
	private static final long HOLD_MS = 500L;
	/** Faces of the cross: the spawn face, then the faces at its right, left, top and bottom, then the opposite face below the bottom one. */
	private static final int FACES = 6;
	/** Low corner of each face in the cross, in units of h. */
	private static final int[][] NET_CORNER = {{-1, -1}, {1, -1}, {-3, -1}, {-1, -3}, {-1, 1}, {-1, 3}};
	/** Seen from a corner, with the spawn face on top. */
	private static final Quaternionf CUBE_VIEW = new Quaternionf().rotateX((float) Math.acos(1.0 / Math.sqrt(3.0))).rotateZ(Mth.HALF_PI / 2.0F);

	private static CubePregen.Progress cachedFor;
	private static int[][] cells;
	private static long foldStart;
	/** Fold of the last frame, for the load trace. */
	public static float lastFold;

	private CubeLoadingView() {
	}

	/** Draws the view centered on the point, at most {@code maxHalfHeight} pixels above and below it. Returns the half height. */
	public static int draw(GuiGraphicsExtractor graphics, CubePregen.Progress progress, int xCenter, int yCenter, int maxHalfHeight) {
		int d = progress.layout.radius / 8;
		float h = d / 2.0F;
		if (cachedFor != progress) {
			cells = mapCells(progress, d);
			cachedFor = progress;
			foldStart = 0L;
		}
		boolean wholeCube = progress.settings.pregen() == CubeSettings.Pregen.WHOLE_CUBE;
		float fold = wholeCube ? fold(progress) : 0.0F;
		lastFold = fold;
		float halfHeight = wholeCube ? 2.0F * d : h + CubePregen.SPAWN_FACE_MARGIN;
		float scale = Math.min(CELL_SIZE, Math.max(1, maxHalfHeight) / halfHeight);
		Quaternionf view = new Quaternionf().slerp(CUBE_VIEW, fold);
		// The cross is centered one face below the spawn face. The folded cube is centered on its own center.
		Vector3f center = new Vector3f(0.0F, wholeCube ? h : 0.0F, 0.0F).lerp(new Vector3f(0.0F, 0.0F, -h), fold);

		Matrix4f[] transforms = new Matrix4f[FACES];
		float[] depths = new float[FACES];
		Integer[] order = new Integer[wholeCube ? FACES : FACES - 1];
		for (int f = 0; f < order.length; f++) {
			transforms[f] = new Matrix4f().translate(xCenter, yCenter, 0.0F).scale(scale).rotate(view).translate(center.negate(new Vector3f()))
				.mul(fold(f, fold * Mth.HALF_PI, h)).translate(NET_CORNER[f][0] * h, NET_CORNER[f][1] * h, h);
			depths[f] = transforms[f].transformPosition(new Vector3f(h, h, 0.0F)).z;
			order[f] = f;
		}
		// Far faces first, so that near faces cover them.
		Arrays.sort(order, (a, b) -> Float.compare(depths[a], depths[b]));

		Object2IntMap<ChunkStatus> colors = LevelLoadingScreenAccessor.cubeworld$colors();
		for (int f : order) {
			Matrix4f m = transforms[f];
			// The GUI culls quads that face away. A face seen from behind is drawn mirrored, which puts each cell in the same place.
			boolean back = m.m00() * m.m11() - m.m10() * m.m01() < 0.0F;
			if (back) {
				m.translate(d, 0.0F, 0.0F).scale(-1.0F, 1.0F, 1.0F);
			}
			graphics.pose().pushMatrix();
			graphics.pose().mul(new Matrix3x2f(m.m00(), m.m01(), m.m10(), m.m11(), m.m30(), m.m31()));
			int[] table = cells[f];
			for (int s = 0; s < d; s++) {
				for (int t = 0; t < d; t++) {
					int at = (s * d + t) * 3;
					if (table[at] >= 0) {
						int x = back ? d - 1 - s : s;
						graphics.fill(x, t, x + 1, t + 1, ARGB.opaque(colors.getInt(progress.get(table[at], table[at + 1], table[at + 2]))));
					}
				}
			}
			graphics.pose().popMatrix();
		}
		return Math.round(halfHeight * scale);
	}

	/** True while the cube folds or holds, so that the loading screen stays open for it. */
	public static boolean isFolding(CubePregen.Progress progress) {
		return progress == cachedFor && foldStart != 0L && Util.getMillis() - foldStart < FOLD_MS + HOLD_MS;
	}

	/** Cells drawn in each status, as {@link LoadTrace#histogram} takes them. */
	public static int[] shownCounts(CubePregen.Progress progress) {
		int[] counts = new int[ChunkStatus.getStatusList().size() + 1];
		if (cachedFor != progress) {
			return counts;
		}
		int faces = progress.settings.pregen() == CubeSettings.Pregen.WHOLE_CUBE ? FACES : FACES - 1;
		for (int f = 0; f < faces; f++) {
			int[] table = cells[f];
			for (int at = 0; at < table.length; at += 3) {
				if (table[at] >= 0) {
					ChunkStatus status = progress.get(table[at], table[at + 1], table[at + 2]);
					counts[status == null ? 0 : status.getIndex() + 1]++;
				}
			}
		}
		return counts;
	}

	/** How far the cube is folded, from 0 for the flat cross to 1. The fold starts when the pre-generation is done. */
	private static float fold(CubePregen.Progress progress) {
		if (!progress.pregenFinished) {
			foldStart = 0L;
			return 0.0F;
		}
		if (foldStart == 0L) {
			foldStart = Util.getMillis();
			LoadTrace.event("client: visualizer fold starts");
		}
		// Faster and faster, so that the faces snap shut at the end.
		float t = Mth.clamp((Util.getMillis() - foldStart) / (float) FOLD_MS, 0.0F, 1.0F);
		return t * t;
	}

	/** Turns face {@code f} of the cross about its edges by {@code angle}. At a right angle, the face is in its place on the cube. */
	private static Matrix4f fold(int f, float angle, float h) {
		return switch (f) {
			case 1 -> new Matrix4f().translate(h, 0.0F, h).rotateY(angle).translate(-h, 0.0F, -h);
			case 2 -> new Matrix4f().translate(-h, 0.0F, h).rotateY(-angle).translate(h, 0.0F, -h);
			case 3 -> new Matrix4f().translate(0.0F, -h, h).rotateX(angle).translate(0.0F, h, -h);
			case 4 -> new Matrix4f().translate(0.0F, h, h).rotateX(-angle).translate(0.0F, -h, -h);
			case 5 -> fold(4, angle, h).translate(0.0F, 3.0F * h, h).rotateX(-angle).translate(0.0F, -3.0F * h, -h);
			default -> new Matrix4f();
		};
	}

	/**
	 * For each face and cell, the face region and column offsets of the column under the cell, or a face of -1 for a
	 * cell that the view leaves out. The cube axes are x = a, y = -b and z = n (the spawn face), with b = a x n.
	 */
	private static int[][] mapCells(CubePregen.Progress progress, int d) {
		SeamLayout layout = progress.layout;
		Vec3i n = progress.settings.spawnFace().getUnitVec3i();
		Vec3i[] axes = CubePregen.viewAxes(progress.settings.spawnFace());
		Vec3i a = axes[0];
		Vec3i b = axes[1];
		float h = d / 2.0F;
		boolean wholeCube = progress.settings.pregen() == CubeSettings.Pregen.WHOLE_CUBE;
		int[][] tables = new int[FACES][d * d * 3];
		int[] out = new int[3];
		for (int f = 0; f < FACES; f++) {
			Matrix4f folded = fold(f, Mth.HALF_PI, h).translate(NET_CORNER[f][0] * h, NET_CORNER[f][1] * h, h);
			Vector3f normal = folded.transformDirection(new Vector3f(0.0F, 0.0F, 1.0F));
			for (int s = 0; s < d; s++) {
				for (int t = 0; t < d; t++) {
					int at = (s * d + t) * 3;
					Vector3f p = folded.transformPosition(new Vector3f(s + 0.5F, t + 0.5F, 0.0F));
					if (!wholeCube && p.z < h - CubePregen.SPAWN_FACE_MARGIN) {
						tables[f][at] = -1;
						continue;
					}
					column(layout, cube(p.mul(16.0F), a, b, n), cube(normal, a, b, n), out);
					System.arraycopy(out, 0, tables[f], at, 3);
				}
			}
		}
		return tables;
	}

	/** A point of the view in cube coordinates, rounded to whole blocks. */
	private static Vec3i cube(Vector3f p, Vec3i a, Vec3i b, Vec3i n) {
		int x = Math.round(p.x);
		int y = Math.round(p.y);
		int z = Math.round(p.z);
		return a.multiply(x).offset(b.multiply(-y)).offset(n.multiply(z));
	}

	/** Face region and column offsets, as {@link CubePregen.Progress#get} takes them, of a cube surface point. */
	private static void column(SeamLayout layout, Vec3i cube, Vec3i normal, int[] out) {
		int face = Arrays.asList(SeamLayout.FACES).indexOf(Direction.getNearest(normal.getX(), normal.getY(), normal.getZ(), null));
		int[] storage = new int[3];
		layout.toStorage(face, cube.getX(), cube.getY() + layout.centerY, cube.getZ(), storage);
		out[0] = face;
		out[1] = Math.floorDiv(storage[0], 16) - Math.floorDiv(layout.offsetX(face) - layout.spacing / 2, 16);
		out[2] = Math.floorDiv(storage[2], 16) - Math.floorDiv(-layout.spacing / 2, 16);
	}
}

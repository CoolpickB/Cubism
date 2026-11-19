package dev.cubism.client.gui;

import dev.cubism.client.mixin.gui.LevelLoadingScreenAccessor;
import dev.cubism.debug.LoadTrace;
import dev.cubism.worldgen.CubePregen;
import dev.cubism.worldgen.CubeSettings;
import dev.cubism.cube.SeamLayout;
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
 * a cube seen from a corner. The face that generates is the face at the right, and when it is done the cube turns to
 * bring the next face there. At the end the cube turns back to the spawn face on top. The spawn face alone shows as
 * one square with the strips of its neighbor faces around it.
 *
 * <p>Coordinates are in cells, with x to the right, y down the screen and z toward the viewer. In the cube, the spawn
 * face is the square z = h, and the cube is behind it, centered on the origin.
 */
public final class CubeLoadingView {
	/** Size of a chunk column in GUI pixels, as on the vanilla map. Smaller only if the view does not fit. */
	private static final float CELL_SIZE = 2.0F;
	/** How long the cube takes to turn the next face to the right. */
	private static final long TURN_MS = 350L;
	/** The cube stays on the screen this long after its last turn, before the game starts. */
	private static final long HOLD_MS = 500L;
	/** The cube turns on to the next face when this share of a face is full, so that a few slow cells do not hold the turn. */
	private static final float TURN_SHARE = 0.95F;
	/** Faces of the cross: the spawn face, then the faces at its right, left, top and bottom, then the opposite face below the bottom one. */
	private static final int FACES = 6;
	/** Low corner of each face in the cross, in units of h. */
	private static final int[][] NET_CORNER = {{-1, -1}, {1, -1}, {-3, -1}, {-1, -3}, {-1, 1}, {-1, 3}};
	/** Seen from a corner, with the spawn face on top. */
	private static final Quaternionf CUBE_VIEW = new Quaternionf().rotateX((float) Math.acos(1.0 / Math.sqrt(3.0))).rotateZ(Mth.HALF_PI / 2.0F);

	/** Outward normal of the face at the lower right of the corner view, on the screen. */
	private static final Vector3f RIGHT_SLOT = new Vector3f((float) Math.sqrt(0.5), (float) Math.sqrt(1.0 / 6.0), (float) Math.sqrt(1.0 / 3.0));

	private static CubePregen.Progress cachedFor;
	private static int[][] cells;
	/** Faces of the cube view in the order that they generate: the spawn face, its neighbors by region, the opposite face. */
	private static int[] faceOrder;
	/** The face at the right, as an index into {@link #faceOrder}, {@link #FACES} for the turn back at the end, -1 before the first. */
	private static int turnedTo;
	private static Quaternionf turnFrom;
	private static Quaternionf turnTo;
	private static long turnStart;
	/** When the turn back at the end started, or 0 before it. */
	private static long finishStart;
	/** How far the turn back at the end is, from 0 to 1, for the load trace. */
	public static float lastFold;

	private CubeLoadingView() {
	}

	/** Draws the view centered on the point, at most {@code maxHalfHeight} pixels above and below it. Returns the half height. */
	public static int draw(GuiGraphicsExtractor graphics, CubePregen.Progress progress, int xCenter, int yCenter, int maxHalfHeight) {
		int d = progress.layout.radius / 8;
		float h = d / 2.0F;
		if (cachedFor != progress) {
			cells = mapCells(progress, d);
			faceOrder = faceOrder(cells, d);
			cachedFor = progress;
			turnedTo = -1;
			finishStart = 0L;
		}
		boolean wholeCube = progress.settings.pregen() == CubeSettings.Pregen.WHOLE_CUBE;
		// A turning cube reaches out to its half diagonal.
		float halfHeight = wholeCube ? (float) Math.sqrt(3.0) * h : h + CubePregen.SPAWN_FACE_MARGIN;
		float scale = Math.min(CELL_SIZE, Math.max(1, maxHalfHeight) / halfHeight);
		Matrix4f base = new Matrix4f().translate(xCenter, yCenter, 0.0F).scale(scale);
		if (wholeCube) {
			base.rotate(turn(progress));
		}
		Object2IntMap<ChunkStatus> colors = LevelLoadingScreenAccessor.cubism$colors();
		for (int f = 0; f < (wholeCube ? FACES : FACES - 1); f++) {
			Matrix4f m = base.mul(wholeCube ? placed(f, h) : new Matrix4f().translate(NET_CORNER[f][0] * h, NET_CORNER[f][1] * h, h), new Matrix4f());
			// The GUI culls quads that face away, and the back faces of a cube are hidden anyway.
			if (m.m00() * m.m11() - m.m10() * m.m01() <= 0.0F) {
				continue;
			}
			graphics.pose().pushMatrix();
			graphics.pose().mul(new Matrix3x2f(m.m00(), m.m01(), m.m10(), m.m11(), m.m30(), m.m31()));
			int[] table = cells[f];
			for (int s = 0; s < d; s++) {
				for (int t = 0; t < d; t++) {
					int at = (s * d + t) * 3;
					if (table[at] >= 0) {
						graphics.fill(s, t, s + 1, t + 1, ARGB.opaque(colors.getInt(progress.get(table[at], table[at + 1], table[at + 2]))));
					}
				}
			}
			graphics.pose().popMatrix();
		}
		return Math.round(halfHeight * scale);
	}

	/**
	 * The turn of the cube for this frame. The face that generates is at the right. When it is done, the cube turns the
	 * next face there: clockwise on the screen for a face in sight, the short way for a face at the back. When all are
	 * done, it turns back to the corner view with the spawn face on top.
	 */
	private static Quaternionf turn(CubePregen.Progress progress) {
		int target = progress.pregenFinished ? FACES : currentFace(progress);
		if (target != turnedTo) {
			long now = Util.getMillis();
			if (turnedTo >= 0 && turnedTo < FACES) {
				logLeftovers(progress, faceOrder[turnedTo], "leaves it");
			}
			if (target == FACES) {
				for (int f = 0; f < FACES; f++) {
					logLeftovers(progress, faceOrder[f], "finished");
				}
			}
			Quaternionf current = turnedTo < 0 ? new Quaternionf(CUBE_VIEW) : turnAt(now);
			Quaternionf next;
			if (target == FACES) {
				next = new Quaternionf(CUBE_VIEW);
				finishStart = now;
				LoadTrace.event("client: visualizer turns back");
			} else {
				Vector3f normal = current.transform(placed(faceOrder[target], 1.0F).transformDirection(new Vector3f(0.0F, 0.0F, 1.0F)));
				Quaternionf step;
				if (normal.z > 0.1F) {
					// A turn about the line of view. With y down the screen, clockwise is a positive angle about z.
					float angle = (float) (Math.atan2(RIGHT_SLOT.y, RIGHT_SLOT.x) - Math.atan2(normal.y, normal.x));
					step = new Quaternionf().rotateZ(Mth.positiveModulo(angle, Mth.TWO_PI));
				} else {
					step = new Quaternionf().rotationTo(normal, RIGHT_SLOT);
				}
				next = step.mul(current);
			}
			// The first face is in place at once.
			turnFrom = turnedTo < 0 ? next : current;
			turnTo = next;
			turnStart = now;
			turnedTo = target;
		}
		return turnAt(Util.getMillis());
	}

	private static Quaternionf turnAt(long millis) {
		float t = Mth.clamp((millis - turnStart) / (float) TURN_MS, 0.0F, 1.0F);
		lastFold = finishStart == 0L ? 0.0F : t;
		// Fast at first, then easing into place.
		float eased = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
		return new Quaternionf(turnFrom).slerp(turnTo, eased);
	}

	/** The first face in {@link #faceOrder} that is less than {@link #TURN_SHARE} full, or the last face. */
	private static int currentFace(CubePregen.Progress progress) {
		for (int i = 0; i < FACES; i++) {
			int[] table = cells[faceOrder[i]];
			int drawn = 0;
			int full = 0;
			for (int at = 0; at < table.length; at += 3) {
				if (table[at] >= 0) {
					drawn++;
					if (progress.get(table[at], table[at + 1], table[at + 2]) == ChunkStatus.FULL) {
						full++;
					}
				}
			}
			if (full < drawn * TURN_SHARE) {
				return i;
			}
		}
		return FACES - 1;
	}

	/** Logs the cells of a face that are not full, so that a cell that stays grey or red shows its face, columns and status. */
	private static void logLeftovers(CubePregen.Progress progress, int face, String when) {
		int[] table = cells[face];
		int left = 0;
		StringBuilder first = new StringBuilder();
		for (int at = 0; at < table.length; at += 3) {
			if (table[at] < 0) {
				continue;
			}
			ChunkStatus status = progress.get(table[at], table[at + 1], table[at + 2]);
			if (status != ChunkStatus.FULL) {
				if (left++ < 8) {
					first.append(" (face ").append(table[at]).append(" col ").append(table[at + 1]).append(',').append(table[at + 2])
						.append(' ').append(status == null ? "none" : status.getName().replace("minecraft:", "")).append(')');
				}
			}
		}
		if (left > 0) {
			LoadTrace.event("client: visualizer " + when + ", view face " + face + " has " + left + " cells not full:" + first);
		}
	}

	/** {@link CubePregen} generates the spawn face first, then the neighbor faces in the order of their regions, then the opposite face. */
	private static int[] faceOrder(int[][] tables, int d) {
		int middle = (d / 2 * d + d / 2) * 3;
		Integer[] neighbors = {1, 2, 3, 4};
		Arrays.sort(neighbors, (a, b) -> Integer.compare(tables[a][middle], tables[b][middle]));
		return new int[] {0, neighbors[0], neighbors[1], neighbors[2], neighbors[3], 5};
	}

	/** Face {@code f} of the cross, folded into its place on the cube. */
	private static Matrix4f placed(int f, float h) {
		return fold(f, Mth.HALF_PI, h).translate(NET_CORNER[f][0] * h, NET_CORNER[f][1] * h, h);
	}

	/** True while the cube turns back at the end or holds, so that the loading screen stays open for it. */
	public static boolean isFolding(CubePregen.Progress progress) {
		return progress == cachedFor && finishStart != 0L && Util.getMillis() - finishStart < TURN_MS + HOLD_MS;
	}

	/**
	 * The share of the drawn cells that are full. The pre-generation of the spawn face also covers columns that the
	 * view has no cell for, so its count of columns runs ahead of the picture, and the bar follows the picture.
	 */
	public static float fullShare(CubePregen.Progress progress) {
		if (cachedFor != progress) {
			return 0.0F;
		}
		int drawn = 0;
		int full = 0;
		for (int f = 0; f < FACES - 1; f++) {
			int[] table = cells[f];
			for (int at = 0; at < table.length; at += 3) {
				if (table[at] >= 0) {
					drawn++;
					if (progress.get(table[at], table[at + 1], table[at + 2]) == ChunkStatus.FULL) {
						full++;
					}
				}
			}
		}
		return drawn == 0 ? 0.0F : (float) full / drawn;
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

package dev.cubeworld.worldgen;

import dev.cubeworld.math.CubePlanetMath;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Directions and distances over the surface of the cube. Storage coordinates only make sense inside one face region, so
 * a target on another face is unfolded into the flat plane of the face that the viewer stands on, over one edge for a
 * neighbor face and over two edges for the opposite face. A straight line in that plane is the way over the edges.
 */
public final class SeamPaths {
	private SeamPaths() {
	}

	/**
	 * Where a target appears for a viewer, as a storage position in the region of the viewer's face. For a target on the
	 * viewer's face that is the target itself. For another face it lies past the edge in the direction that leads there
	 * (usually outside the wedge, which is fine for an angle or a distance). The height is the height of the target.
	 * Returns the target unchanged when either position lies in no region.
	 */
	public static Vec3 aim(SeamLayout layout, Vec3 from, Vec3 target) {
		Vec3 flat = unfold(layout, from, target, false);
		return flat == null ? target : flat;
	}

	/**
	 * As {@link #aim}, for the plane of a map. A map shows its own face straight from above, so the rounded ground just
	 * before the diagonal shows short of the edge line. Plain unfolding puts the same ground just past the diagonal
	 * as far past the line, and a marker would jump by twice that. Here a target of the next face moves back by twice
	 * its depth under the plane of that face, down to the edge rounding, so both sides meet at the diagonal. The
	 * {@code y} of {@code target} must be the real height, as the depth comes from it.
	 */
	public static Vec3 aimOnMap(SeamLayout layout, Vec3 from, Vec3 target) {
		Vec3 flat = unfold(layout, from, target, true);
		return flat == null ? target : flat;
	}

	/**
	 * The heading {@code yaw} of something at {@code target}, as seen on a map of the face of {@code from}. Unfolding
	 * turns a face by a multiple of 90 degrees, so the heading turns with it.
	 */
	public static float aimYaw(SeamLayout layout, Vec3 from, Vec3 target, float yaw) {
		double radians = Math.toRadians(yaw);
		Vec3 ahead = target.add(-Math.sin(radians), 0.0, Math.cos(radians));
		Vec3 flat = aim(layout, from, target);
		Vec3 flatAhead = aim(layout, from, ahead);
		return (float) Math.toDegrees(Math.atan2(-(flatAhead.x - flat.x), flatAhead.z - flat.z));
	}

	/** The distance over the surface, which is the length of the straight line in the unfolded plane, or -1 outside the regions. */
	public static double distance(SeamLayout layout, Vec3 from, Vec3 target) {
		Vec3 flat = unfold(layout, from, target, false);
		return flat == null ? -1.0 : Math.hypot(flat.x - from.x, flat.z - from.z);
	}

	private static @Nullable Vec3 unfold(SeamLayout layout, Vec3 from, Vec3 target, boolean onMap) {
		int fromBlockX = Mth.floor(from.x);
		int targetBlockX = Mth.floor(target.x);
		if (!layout.inRegion(fromBlockX, Mth.floor(from.z)) || !layout.inRegion(targetBlockX, Mth.floor(target.z))) {
			return null;
		}
		int a = layout.faceIndexAt(fromBlockX);
		int b = layout.faceIndexAt(targetBlockX);
		if (a == b) {
			return target;
		}
		Vec3 viewer = layout.storageToCube(a, from.x, from.y, from.z);
		Vec3 goal = layout.storageToCube(b, target.x, target.y, target.z);
		double r = layout.radius;
		double[] nA = normal(a);
		double[] nB = normal(b);
		double[] p = onPlane(centered(viewer), nA, r);
		double[] q = onPlane(centered(goal), nB, r);
		double[] best;
		if (dot(nA, nB) == 0.0) {
			// Neighbor faces: over the one shared edge.
			best = unfold(nA, nB, q, r);
			if (onMap) {
				// Heights over the planes of A and B are equal at the diagonal. The shift fades out over one rounding from there.
				double rounding = CubePlanetMath.edgeRounding();
				double overA = dot(centered(goal), nA) - r;
				double overB = dot(centered(goal), nB) - r;
				double shift = 2.0 * Math.max(overB, -rounding) * Math.clamp(1.0 - (overB - overA) / rounding, 0.0, 1.0);
				for (int i = 0; i < 3; i++) {
					best[i] += shift * nB[i];
				}
			}
		} else {
			// Opposite faces: over the edge of the target to a side face, then over the edge of that face to the viewer. The shortest of the four ways wins.
			best = null;
			double bestLength = Double.MAX_VALUE;
			for (int c = 0; c < SeamLayout.FACES.length; c++) {
				double[] nC = normal(c);
				if (dot(nA, nC) != 0.0) {
					continue;
				}
				double[] unfolded = unfold(nA, nC, unfold(nC, nB, q, r), r);
				double length = Math.hypot(unfolded[0] - p[0], Math.hypot(unfolded[1] - p[1], unfolded[2] - p[2]));
				if (length < bestLength) {
					bestLength = length;
					best = unfolded;
				}
			}
		}
		// The vector lies in the plane of the viewer's face, so in the local axes of that face it has no vertical part.
		int[] m = layout.rotation(a);
		double vx = best[0] - p[0];
		double vy = best[1] - p[1];
		double vz = best[2] - p[2];
		double localX = m[0] * vx + m[3] * vy + m[6] * vz;
		double localZ = m[2] * vx + m[5] * vy + m[8] * vz;
		return new Vec3(from.x + localX, target.y, from.z + localZ);
	}

	/** A point on the plane of a neighbor face, unfolded into the plane of {@code face}. The point {@code x} lies on the plane of {@code next}. */
	private static double[] unfold(double[] face, double[] next, double[] x, double r) {
		double alongFace = dot(x, face);
		double[] result = new double[3];
		for (int i = 0; i < 3; i++) {
			// What stays: the part along the shared edge.
			double edge = x[i] - alongFace * face[i] - r * next[i];
			result[i] = edge + r * face[i] + (2.0 * r - alongFace) * next[i];
		}
		return result;
	}

	private static double[] centered(Vec3 cube) {
		return new double[] {cube.x - 0.5, cube.y - 0.5, cube.z - 0.5};
	}

	/** The point moved along the normal onto the plane at the surface of the face. */
	private static double[] onPlane(double[] u, double[] normal, double r) {
		double along = dot(u, normal);
		return new double[] {u[0] + (r - along) * normal[0], u[1] + (r - along) * normal[1], u[2] + (r - along) * normal[2]};
	}

	private static double[] normal(int face) {
		Direction direction = SeamLayout.FACES[face];
		return new double[] {direction.getStepX(), direction.getStepY(), direction.getStepZ()};
	}

	private static double dot(double[] p, double[] q) {
		return p[0] * q[0] + p[1] * q[1] + p[2] * q[2];
	}
}

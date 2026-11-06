package dev.cubeworld.math;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

public final class CubePlanetMath {
	public static final int MIN_DIAMETER_CHUNKS = 24;
	public static final int MAX_DIAMETER_CHUNKS = 128;
	public static final int TOP_SURFACE_Y = 64;
	public static final int CRUST_THICKNESS = 191;

	private CubePlanetMath() {}

	public static int getRadiusBlocks(int diameterChunks) {
		return Math.max(MIN_DIAMETER_CHUNKS, diameterChunks) * 8;
	}

	public static int getCenterY(int diameterChunks) {
		return TOP_SURFACE_Y - getRadiusBlocks(diameterChunks);
	}

	public static int getInnerBedrockRadius(int diameterChunks) {
		int r = getRadiusBlocks(diameterChunks);
		return Math.max(1, r - CRUST_THICKNESS);
	}

	/** Height of the open sky above the surface of each face. */
	public static final int ATMOSPHERE_HEIGHT = 96;

	public static final int MIN_EDGE_ROUNDING = 2;
	public static final int MAX_EDGE_ROUNDING = 96;
	public static final int DEFAULT_EDGE_ROUNDING = 16;
	/** Radius of the cube edges and corners that terrain height is measured from. It comes from the {@code CubeSettings} of the world: the server sets it when the level starts, a client when it joins. */
	private static volatile int edgeRounding = DEFAULT_EDGE_ROUNDING;

	public static int edgeRounding() {
		return edgeRounding;
	}

	/**
	 * The lower bound keeps the vanilla sea level, one block under the surface, on the rounded part of the contour,
	 * where {@code SeamLayout.seaLevelAt} can solve it.
	 */
	public static void setEdgeRounding(int rounding) {
		edgeRounding = Math.clamp(rounding, MIN_EDGE_ROUNDING, MAX_EDGE_ROUNDING);
	}

	/**
	 * Signed distance from the cube of half size {@code r} with edges and corners rounded by {@link #edgeRounding}.
	 * The value is negative inside. With this distance as the terrain height, contours of equal height are rounded
	 * boxes, so terrain near an edge bends over the edge. The Chebyshev distance makes square contours, and terrain
	 * on them goes on past the edge as a flat shelf in the sky of the next face.
	 */
	public static double roundedCubeDistance(double dx, double dy, double dz, double r) {
		int rounding = edgeRounding;
		double core = r - rounding;
		double qx = Math.abs(dx) - core;
		double qy = Math.abs(dy) - core;
		double qz = Math.abs(dz) - core;
		double ox = Math.max(qx, 0.0);
		double oy = Math.max(qy, 0.0);
		double oz = Math.max(qz, 0.0);
		return Math.sqrt(ox * ox + oy * oy + oz * oz) + Math.min(Math.max(qx, Math.max(qy, qz)), 0.0) - rounding;
	}

	/**
	 * Writes the nearest point of the rounded cube surface of {@link #roundedCubeDistance} into {@code out}. A point
	 * deeper than the rounding maps straight out along its face axis, so the points of a face column share one
	 * surface point, like a vanilla column. At that depth the map jumps across the diagonal between two faces.
	 */
	public static void roundedCubeSurfacePoint(double dx, double dy, double dz, double r, double[] out) {
		int rounding = edgeRounding;
		double core = r - rounding;
		double cx = Math.clamp(dx, -core, core);
		double cy = Math.clamp(dy, -core, core);
		double cz = Math.clamp(dz, -core, core);
		double ex = dx - cx;
		double ey = dy - cy;
		double ez = dz - cz;
		double length = Math.sqrt(ex * ex + ey * ey + ez * ez);
		if (length > 1.0e-6) {
			double scale = rounding / length;
			out[0] = cx + ex * scale;
			out[1] = cy + ey * scale;
			out[2] = cz + ez * scale;
			return;
		}
		out[0] = dx;
		out[1] = dy;
		out[2] = dz;
		double ax = Math.abs(dx);
		double ay = Math.abs(dy);
		double az = Math.abs(dz);
		if (ay >= ax && ay >= az) {
			out[1] = Math.copySign(r, dy);
		} else if (ax >= az) {
			out[0] = Math.copySign(r, dx);
		} else {
			out[2] = Math.copySign(r, dz);
		}
	}

	public static Vec3 up(Direction gravity) {
		return new Vec3(-gravity.getStepX(), -gravity.getStepY(), -gravity.getStepZ());
	}

	/**
	 * Transforms a vector from the local frame (where -Y is along gravity) into world coordinates.
	 * Matches getCameraRotation(gravity) on all 6 faces.
	 */
	public static Vec3 localToWorld(Vec3 local, Direction gravity) {
		return switch (gravity) {
			case DOWN -> local;
			case UP -> new Vec3(-local.x, -local.y, local.z);
			case NORTH -> new Vec3(local.x, -local.z, local.y);
			case SOUTH -> new Vec3(local.x, local.z, -local.y);
			case WEST -> new Vec3(local.y, -local.x, local.z);
			case EAST -> new Vec3(-local.y, local.x, local.z);
		};
	}

	public static Vec3 worldToLocal(Vec3 world, Direction gravity) {
		return switch (gravity) {
			case DOWN -> world;
			case UP -> new Vec3(-world.x, -world.y, world.z);
			case NORTH -> new Vec3(world.x, world.z, -world.y);
			case SOUTH -> new Vec3(world.x, -world.z, world.y);
			case WEST -> new Vec3(-world.y, world.x, world.z);
			case EAST -> new Vec3(world.y, -world.x, world.z);
		};
	}

	// The frames are signed axis permutations about the origin, so transforming the two corners keeps a box exact.
	public static AABB localToWorld(AABB local, Direction gravity) {
		if (gravity == Direction.DOWN) {
			return local;
		}
		return new AABB(localToWorld(local.getMinPosition(), gravity), localToWorld(local.getMaxPosition(), gravity));
	}

	public static AABB worldToLocal(AABB world, Direction gravity) {
		if (gravity == Direction.DOWN) {
			return world;
		}
		return new AABB(worldToLocal(world.getMinPosition(), gravity), worldToLocal(world.getMaxPosition(), gravity));
	}

	public static Direction rotateDirection(Direction localDir, Direction gravity) {
		if (localDir == null || gravity == Direction.DOWN) {
			return localDir;
		}
		Vec3 w = localToWorld(new Vec3(localDir.getStepX(), localDir.getStepY(), localDir.getStepZ()), gravity);
		return Direction.getApproximateNearest(w.x, w.y, w.z);
	}

	/**
	 * Returns the face rotation that maps local UP (+Y) to the opposite of gravity.
	 */
	public static Quaternionf getCameraRotation(Direction gravity) {
		Quaternionf q = new Quaternionf();
		return switch (gravity) {
			case DOWN -> q.identity();
			case UP -> q.rotationZ((float) Math.PI);
			case NORTH -> q.rotationX((float) (Math.PI / 2.0));
			case SOUTH -> q.rotationX((float) (-Math.PI / 2.0));
			case WEST -> q.rotationZ((float) (-Math.PI / 2.0));
			case EAST -> q.rotationZ((float) (Math.PI / 2.0));
		};
	}
}

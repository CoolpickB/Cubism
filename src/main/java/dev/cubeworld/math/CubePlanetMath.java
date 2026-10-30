package dev.cubeworld.math;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

public final class CubePlanetMath {
	public static final int MIN_DIAMETER_CHUNKS = 24;
	public static final int MAX_DIAMETER_CHUNKS = 128;
	public static final int TOP_SURFACE_Y = 64;
	public static final int CRUST_THICKNESS = 191;

	// Set on the client from the planet payload. The server reads the chunk generator instead.
	private static volatile int activeClientDiameterChunks = MIN_DIAMETER_CHUNKS;
	private static volatile boolean activeClientIsCube = false;

	private CubePlanetMath() {}

	public static void setClientCubeState(boolean isCube, int diameterChunks) {
		activeClientIsCube = isCube;
		activeClientDiameterChunks = diameterChunks;
	}

	public static boolean isClientCubeActive() {
		return activeClientIsCube;
	}

	public static int getClientCenterY() {
		return getCenterY(activeClientDiameterChunks);
	}

	public static int getClientRadius() {
		return getRadiusBlocks(activeClientDiameterChunks);
	}

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

	public static boolean hasHollowCore(int diameterChunks) {
		return getInnerBedrockRadius(diameterChunks) > 1;
	}

	/**
	 * The sticky gravity rule (GOALS.md, core model item 6). The feet position decides. The offset uses
	 * the same block-center convention as {@link #getPyramidOutwardFace}, so the block at index R from the
	 * center is the first block inside the plane.
	 *
	 * @return the gravity direction (pointing toward the center)
	 */
	public static Direction stickyGravity(Vec3 feet, int centerY, int radius, Direction current) {
		double ox = feet.x - 0.5;
		double oy = feet.y - centerY - 0.5;
		double oz = feet.z - 0.5;
		Direction currentFace = current.getOpposite();
		if (planeExcess(currentFace, ox, oy, oz, radius) > 0.0) {
			return current;
		}
		Direction best = null;
		double bestExcess = 0.0;
		for (Direction face : Direction.values()) {
			double excess = planeExcess(face, ox, oy, oz, radius);
			if (excess > bestExcess) {
				bestExcess = excess;
				best = face;
			}
		}
		return best == null ? current : best.getOpposite();
	}

	private static double planeExcess(Direction face, double ox, double oy, double oz, int radius) {
		return face.getStepX() * ox + face.getStepY() * oy + face.getStepZ() * oz - radius;
	}

	public static Direction sectorGravity(Vec3 pos, int centerY) {
		return getPyramidOutwardFace((int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z), centerY).getOpposite();
	}

	public static Direction getPyramidOutwardFace(int x, int y, int z, int centerY) {
		int dx = x;
		int dy = y - centerY;
		int dz = z;
		int ax = Math.abs(dx);
		int ay = Math.abs(dy);
		int az = Math.abs(dz);

		if (ay >= ax && ay >= az) {
			return dy >= 0 ? Direction.UP : Direction.DOWN;
		} else if (ax >= ay && ax >= az) {
			return dx >= 0 ? Direction.EAST : Direction.WEST;
		} else {
			return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
		}
	}

	public static int getChebyshevDistance(int x, int y, int z, int centerY) {
		int ax = Math.abs(x);
		int ay = Math.abs(y - centerY);
		int az = Math.abs(z);
		return Math.max(ax, Math.max(ay, az));
	}

	public static int getSecondMaxCoord(int x, int y, int z, int centerY) {
		int ax = Math.abs(x);
		int ay = Math.abs(y - centerY);
		int az = Math.abs(z);
		if (ax >= ay && ax >= az) {
			return Math.max(ay, az);
		} else if (ay >= ax && ay >= az) {
			return Math.max(ax, az);
		} else {
			return Math.max(ax, ay);
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

	public static Direction.Axis worldAxis(Direction.Axis localAxis, Direction gravity) {
		return rotateDirection(Direction.fromAxisAndDirection(localAxis, Direction.AxisDirection.POSITIVE), gravity).getAxis();
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

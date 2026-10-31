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

	/** How far above the surface plane the sky check of {@link #stickyGravity} looks. Matches the generated atmosphere. */
	public static final int ATMOSPHERE_HEIGHT = 96;

	/**
	 * The sticky gravity rule (GOALS.md, core model item 6). The feet position decides. The offset uses
	 * the same block-center convention as {@link #getPyramidOutwardFace}, so the block at index R from the
	 * center is the first block inside the plane.
	 *
	 * <p>Above the plane of its face, an entity is outside the square of every other face, so no other face can
	 * take it. Below that plane, a face takes the entity when the entity is inside the square of that face and
	 * the column above the entity, in the frame of that face, is open to the sky. Thus a player flips when the
	 * player steps out into the open air of the next face, and never in a closed cave.
	 *
	 * @return the gravity direction (pointing toward the center)
	 */
	public static Direction stickyGravity(BlockGetter level, Vec3 feet, int centerY, int radius, Direction current) {
		double ox = feet.x - 0.5;
		double oy = feet.y - centerY - 0.5;
		double oz = feet.z - 0.5;
		Direction currentFace = current.getOpposite();
		if (planeExcess(currentFace, ox, oy, oz, radius) > 0.0) {
			return current;
		}
		BlockPos feetBlock = BlockPos.containing(feet);
		if (insideSquare(currentFace, ox, oy, oz, radius) && skyOpen(level, feetBlock, currentFace, centerY, radius)) {
			return current;
		}
		Direction best = null;
		double bestHeight = Double.NEGATIVE_INFINITY;
		for (Direction face : Direction.values()) {
			if (face == currentFace || !insideSquare(face, ox, oy, oz, radius)) {
				continue;
			}
			double height = planeExcess(face, ox, oy, oz, radius);
			if (height > bestHeight && skyOpen(level, feetBlock, face, centerY, radius)) {
				bestHeight = height;
				best = face;
			}
		}
		return best == null ? current : best.getOpposite();
	}

	private static boolean insideSquare(Direction face, double ox, double oy, double oz, int radius) {
		return switch (face.getAxis()) {
			case X -> Math.abs(oy) <= radius && Math.abs(oz) <= radius;
			case Y -> Math.abs(ox) <= radius && Math.abs(oz) <= radius;
			case Z -> Math.abs(ox) <= radius && Math.abs(oy) <= radius;
		};
	}

	/** True when no motion-blocking block is between the feet and the top of the atmosphere of the face. */
	private static boolean skyOpen(BlockGetter level, BlockPos feetBlock, Direction face, int centerY, int radius) {
		int axisCoord = face.getAxis().choose(feetBlock.getX(), feetBlock.getY() - centerY, feetBlock.getZ());
		int distance = axisCoord * face.getAxisDirection().getStep();
		int steps = radius + ATMOSPHERE_HEIGHT - distance;
		BlockPos.MutableBlockPos pos = feetBlock.mutable();
		for (int i = 0; i <= steps; i++) {
			if (level.getBlockState(pos).isSolid()) {
				return false;
			}
			pos.move(face);
		}
		return true;
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

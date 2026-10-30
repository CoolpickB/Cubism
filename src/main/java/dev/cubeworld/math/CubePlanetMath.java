package dev.cubeworld.math;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class CubePlanetMath {
	public static final int MIN_DIAMETER_CHUNKS = 24;
	public static final int MAX_DIAMETER_CHUNKS = 128;
	public static final int TOP_SURFACE_Y = 64;
	public static final int CRUST_THICKNESS = 191;

	/**
	 * Half-width (in blocks) of the continuous Mario-Galaxy edge/corner rounding band (R - r).
	 * Within 16 blocks of any cube edge or corner, the continuous up vector tilts smoothly
	 * around the inner cube's edge/corner, reaching 45 degrees at the crest.
	 */
	public static final double EDGE_ROUNDING_BAND = 16.0;

	/**
	 * Hysteresis for the discrete AABB hitbox axis swap (~6 degrees past the 45-degree crest).
	 */
	public static final double HITBOX_AXIS_HYSTERESIS = 1.6;

	private static volatile int activeClientCenterY = TOP_SURFACE_Y - (MIN_DIAMETER_CHUNKS * 8);
	private static volatile boolean activeClientIsCube = false;

	private CubePlanetMath() {}

	public static void setClientCubeState(boolean isCube, int diameterChunks) {
		activeClientIsCube = isCube;
		activeClientCenterY = getCenterY(diameterChunks);
	}

	public static boolean isClientCubeActive() {
		return activeClientIsCube;
	}

	public static int getClientCenterY() {
		return activeClientCenterY;
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
	 * Closest-point-on-inner-cube continuous outward "up" unit vector u(p).
	 * - Over the interior of a face (more than EDGE_ROUNDING_BAND from an edge), u(p) is exact face-normal.
	 * - Near an edge, u(p) rotates smoothly around the inner cube edge like a cylinder (45 deg at the crest).
	 * - Near a 3-way corner, u(p) rotates smoothly in both directions like a sphere.
	 */
	public static Vec3 getContinuousUpVector(double x, double y, double z, int centerY) {
		double dx = x;
		double dy = y - centerY;
		double dz = z;

		double ax = Math.abs(dx);
		double ay = Math.abs(dy);
		double az = Math.abs(dz);
		double cheb = Math.max(ax, Math.max(ay, az));
		if (cheb < 1.0E-4) {
			return new Vec3(0.0, 1.0, 0.0);
		}

		double innerR = Math.max(4.0, cheb - EDGE_ROUNDING_BAND);
		double qx = Mth.clamp(dx, -innerR, innerR);
		double qy = Mth.clamp(dy, -innerR, innerR);
		double qz = Mth.clamp(dz, -innerR, innerR);

		double ux = dx - qx;
		double uy = dy - qy;
		double uz = dz - qz;
		double len = Math.sqrt(ux * ux + uy * uy + uz * uz);
		if (len < 1.0E-6) {
			return new Vec3(0.0, 1.0, 0.0);
		}
		return new Vec3(ux / len, uy / len, uz / len);
	}

	/**
	 * Returns the continuous camera orientation quaternion at body center (x, y, z)
	 * by parallel-transporting the discrete face frame's up vector (-discreteGravity) to the continuous up vector u(p).
	 */
	public static Quaternionf getContinuousCameraRotation(double x, double y, double z, int centerY, Direction discreteGravity) {
		Quaternionf faceRot = getCameraRotation(discreteGravity);
		Vec3 contUp = getContinuousUpVector(x, y, z, centerY);
		Vector3f faceUpVec = new Vector3f(
			-discreteGravity.getStepX(),
			-discreteGravity.getStepY(),
			-discreteGravity.getStepZ()
		);
		Vector3f contUpVec = new Vector3f((float) contUp.x, (float) contUp.y, (float) contUp.z);
		Quaternionf tilt = new Quaternionf().rotationTo(faceUpVec, contUpVec);
		return tilt.mul(faceRot);
	}

	/**
	 * Determines the discrete dominant gravity Direction (for the axis-aligned hitbox and grounded anti-slide)
	 * with hysteresis across the 45-degree crest.
	 */
	public static Direction getGravityDirection(double x, double y, double z, int centerY, Direction currentGravity) {
		double dx = x;
		double dy = y - centerY;
		double dz = z;

		double ax = Math.abs(dx);
		double ay = Math.abs(dy);
		double az = Math.abs(dz);

		if (currentGravity != null) {
			boolean sameSign = switch (currentGravity) {
				case DOWN -> dy >= -1.0;
				case UP -> dy <= 1.0;
				case WEST -> dx >= -1.0;
				case EAST -> dx <= 1.0;
				case NORTH -> dz >= -1.0;
				case SOUTH -> dz <= 1.0;
			};
			if (sameSign) {
				switch (currentGravity.getAxis()) {
					case X -> ax += HITBOX_AXIS_HYSTERESIS;
					case Y -> ay += HITBOX_AXIS_HYSTERESIS;
					case Z -> az += HITBOX_AXIS_HYSTERESIS;
				}
			}
		}

		if (ay >= ax && ay >= az) {
			return dy >= 0.0 ? Direction.DOWN : Direction.UP;
		} else if (ax >= ay && ax >= az) {
			return dx >= 0.0 ? Direction.WEST : Direction.EAST;
		} else {
			return dz >= 0.0 ? Direction.NORTH : Direction.SOUTH;
		}
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

	/**
	 * Transforms a vector from the entity's local frame (where -Y is along gravity) into world coordinates.
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

	/**
	 * Transforms a vector from world coordinates into the entity's local frame (where -Y is along gravity).
	 */
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

	/**
	 * Rotates a block-model Direction into world space for a face whose inward gravity is `gravity`.
	 */
	public static Direction rotateDirection(Direction localDir, Direction gravity) {
		if (localDir == null || gravity == Direction.DOWN) {
			return localDir;
		}
		int lx = localDir.getStepX();
		int ly = localDir.getStepY();
		int lz = localDir.getStepZ();
		int wx;
		int wy;
		int wz;
		switch (gravity) {
			case UP -> { wx = -lx; wy = -ly; wz = lz; }
			case NORTH -> { wx = lx; wy = -lz; wz = ly; }
			case SOUTH -> { wx = lx; wy = lz; wz = -ly; }
			case WEST -> { wx = ly; wy = -lx; wz = lz; }
			case EAST -> { wx = -ly; wy = lx; wz = lz; }
			default -> { return localDir; }
		}
		if (wx > 0) return Direction.EAST;
		if (wx < 0) return Direction.WEST;
		if (wy > 0) return Direction.UP;
		if (wy < 0) return Direction.DOWN;
		if (wz > 0) return Direction.SOUTH;
		return Direction.NORTH;
	}

	/**
	 * Returns the canonical face rotation quaternion that maps local UP (+Y) to the opposite of gravity (-gravity).
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

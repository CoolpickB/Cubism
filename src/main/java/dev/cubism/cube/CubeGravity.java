package dev.cubism.cube;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The pull of gravity around a cube. In the air over the faces it points along the inward normal of nested boxes with
 * rounded edges. Above the storage top it rounds into a sphere, and past the exosphere it fades out.
 */
public final class CubeGravity {
	private final SeamLayout layout;

	CubeGravity(SeamLayout layout) {
		this.layout = layout;
	}

	/** Blocks between the box corners of the storage top and the exosphere, so the sphere holds the whole box. */
	private static final double EXOSPHERE_CORNER_GAP = 20.0;
	/** Blocks past the exosphere over which the pull fades out, with a smoothstep, so weightlessness comes on slowly. */
	private static final double EXOSPHERE_FADE = 120.0;
	/** The smallest radius of the turn over an edge, so that a sharp edge still turns the pull and the view smoothly. */
	public static final int MIN_TURN_RADIUS = 12;

	/** The radius of the turn of the pull over an edge, at every height: the edge rounding, but at least {@link #MIN_TURN_RADIUS}. */
	public int turnRadius() {
		return Math.max(MIN_TURN_RADIUS, this.layout.edgeRounding());
	}

	/** How much wider the turn of the pull gets for each block of height over the ground, for creative and spectator flight. */
	private static final double FLIGHT_RADIUS_RATE = 0.5;

	/**
	 * The radius of the turn of the pull for creative and spectator flight at a level (the box norm of the position,
	 * relative to the cube center). It grows with the height over the ground, as the levels of a rounded cube do, so
	 * high up the pull turns slowly over an edge and meets the round pull of space without a step. Walking and elytra
	 * keep {@link #turnRadius} at every height.
	 */
	public double flightRadius(double level) {
		return this.turnRadius() + FLIGHT_RADIUS_RATE * Math.max(0.0, level - this.layout.radius);
	}

	/** {@link #airGravity(double, double, double, double)} with the turn of the pull, {@link #turnRadius}. */
	public @Nullable Vec3 airGravity(double x, double y, double z) {
		return this.airGravity(x, y, z, this.turnRadius());
	}

	/**
	 * The direction of gravity in the air at a continuous storage position, or null where it is straight down. It is
	 * the inward normal of the level through the position, one of a nested family of boxes with rounded edges, so the
	 * field is continuous everywhere, also across the diagonals, and a jump off an edge arcs onto the next face. On a
	 * seam it is half way between the two faces, over a corner it points to the corner.
	 *
	 * <p>Every level has the same rounding, the given radius, so the turn over an edge is equally wide at every height:
	 * in the sky, at the surface and underground. A level smaller than the radius is round, so near the center of the
	 * cube the turn narrows down to the center, where the pull points to the center from every side.
	 *
	 * <p>The rounding is a curve of the norm of power 2.5, between a circle (2) and the cubic norm (3). A circle starts the
	 * turn at its full rate, about 7 degrees per block, so the view snapped from level into a turn. The cubic norm starts
	 * at a rate of 0 but then felt too sudden on the diagonal (9 degrees per block at a radius of 8). This curve starts at
	 * a rate of 0 and is fastest on the diagonal, about 56 / radius degrees per block (4.7 at the smallest radius, 12).
	 */
	public @Nullable Vec3 airGravity(double x, double y, double z, double radius) {
		int bx = Mth.floor(x);
		if (!this.layout.inRegion(bx, Mth.floor(z))) {
			return null;
		}
		double cx = x - this.layout.offsetX(this.layout.faceIndexAt(bx)) - 0.5;
		double cy = y - this.layout.centerY - 0.5;
		double cz = z - 0.5;
		double core = levelCore(Math.abs(cx), Math.abs(cy), Math.abs(cz), radius);
		double dx = cx - Math.clamp(cx, -core, core);
		double dy = cy - Math.clamp(cy, -core, core);
		double dz = cz - Math.clamp(cz, -core, core);
		// Close to straight down is straight down, which saves the callers the turn on most of a wedge.
		if (dy > 0.0 && dx * dx + dz * dz < 1.0E-6 * dy * dy) {
			return null;
		}
		return levelNormal(dx, dy, dz);
	}

	/** The inward normal of the level through an offset {@code d} from its box: the gradient of the norm of the offset. */
	private static @Nullable Vec3 levelNormal(double dx, double dy, double dz) {
		double nx = dx * Math.sqrt(Math.abs(dx));
		double ny = dy * Math.sqrt(Math.abs(dy));
		double nz = dz * Math.sqrt(Math.abs(dz));
		double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
		return length < 1.0E-12 ? null : new Vec3(-nx / length, -ny / length, -nz / length);
	}

	/** The pull of the box levels at a position relative to the cube center (see {@link SeamLayout#cubeOffset}), in the cube frame. */
	private @Nullable Vec3 boxPull(double cx, double cy, double cz, double radius) {
		double core = levelCore(Math.abs(cx), Math.abs(cy), Math.abs(cz), radius);
		return levelNormal(cx - Math.clamp(cx, -core, core), cy - Math.clamp(cy, -core, core), cz - Math.clamp(cz, -core, core));
	}

	/** The radius of the exosphere: the sphere around the box of the storage top, {@link #EXOSPHERE_CORNER_GAP} past its corners. */
	public double exosphereRadius() {
		return Math.sqrt(3.0) * this.layout.stratosphereTop() + EXOSPHERE_CORNER_GAP;
	}

	/**
	 * The pull in space at a position relative to the cube center, in the cube frame, which is also the frame of the
	 * space region. Its length is the share of the full pull, from 1 down to 0, or null for no pull at all. Up to the
	 * top of the stratosphere it is the pull of the box levels, as over the faces. Above it the direction turns from
	 * the box normal to the center of the cube on the way out to the exosphere, so the levels round into a sphere, and
	 * past the exosphere the pull fades out.
	 */
	public @Nullable Vec3 spacePull(double cx, double cy, double cz) {
		return this.spacePull(cx, cy, cz, this.turnRadius());
	}

	/** {@link #spacePull(double, double, double)} with the radius of the turn of the box part, see {@link #flightRadius}. */
	public @Nullable Vec3 spacePull(double cx, double cy, double cz, double radius) {
		double level = Math.max(Math.abs(cx), Math.max(Math.abs(cy), Math.abs(cz)));
		double top = this.layout.stratosphereTop();
		Vec3 box = this.boxPull(cx, cy, cz, radius);
		if (level <= top) {
			return box;
		}
		double distance = Math.sqrt(cx * cx + cy * cy + cz * cz);
		Vec3 radial = new Vec3(-cx / distance, -cy / distance, -cz / distance);
		double exosphere = this.exosphereRadius();
		if (distance >= exosphere) {
			double x = Mth.clamp((distance - exosphere) / EXOSPHERE_FADE, 0.0, 1.0);
			double share = 1.0 - x * x * (3.0 - 2.0 * x);
			return share <= 0.0 ? null : radial.scale(share);
		}
		// The share of the way from the box of the top to the sphere, along the ray from the center.
		double t = (level - top) / (exosphere * level / distance - top);
		return box == null ? radial : box.scale(1.0 - t).add(radial.scale(t)).normalize();
	}

	/**
	 * The direction of the pull at a continuous storage position of a face or of space, in the frame of its region,
	 * or null where it is straight down. On a face it is {@link #airGravity}. In space it is {@link #spacePull}, and
	 * past the exosphere, where the pull is gone, the direction to the cube center.
	 */
	public @Nullable Vec3 pullAt(double x, double y, double z, double radius) {
		if (!this.layout.inSpace(Mth.floor(x), Mth.floor(z))) {
			int region = this.layout.hasSpace() ? this.layout.regionAt(Mth.floor(x), Mth.floor(z)) : -1;
			if (region >= 0) {
				// Over the top of the stratosphere the field is the one of space, also on the face that still stores the
				// entity: the hand-off to the space region, 24 blocks higher, then turns the view by nothing.
				Vec3 up = this.layout.cubeOffset(region, x, y, z);
				if (Math.max(Math.abs(up.x), Math.max(Math.abs(up.y), Math.abs(up.z))) > this.layout.stratosphereTop()) {
					Vec3 pull = this.spacePull(up.x, up.y, up.z, radius);
					Vec3 cube = pull == null || pull.lengthSqr() < 1.0E-6 ? up.reverse() : pull;
					if (cube.lengthSqr() < 1.0E-12) {
						return null;
					}
					cube = cube.normalize();
					int[] m = this.layout.rotation(region);
					return new Vec3(
						m[0] * cube.x + m[3] * cube.y + m[6] * cube.z,
						m[1] * cube.x + m[4] * cube.y + m[7] * cube.z,
						m[2] * cube.x + m[5] * cube.y + m[8] * cube.z
					);
				}
			}
			return this.airGravity(x, y, z, radius);
		}
		Vec3 p = this.layout.cubeOffset(SeamLayout.SPACE, x, y, z);
		Vec3 pull = this.spacePull(p.x, p.y, p.z, radius);
		Vec3 direction = pull == null || pull.lengthSqr() < 1.0E-6 ? p.reverse() : pull;
		return direction.lengthSqr() < 1.0E-12 ? null : direction.normalize();
	}

	/**
	 * The half size of the box that the level through a point of the positive octant is rounded about: the box at the
	 * norm distance of the radius from the point. Without a radius the levels are plain cubes, and a box a hair smaller
	 * gives the direction of the largest axis.
	 */
	private static double levelCore(double ax, double ay, double az, double radius) {
		double max = Math.max(ax, Math.max(ay, az));
		if (radius <= 0.0) {
			return max - 1.0E-6;
		}
		double mid = Math.max(Math.min(ax, ay), Math.min(Math.max(ax, ay), az));
		// Over the flat part of a level the box is the radius below the point.
		if (mid <= max - radius) {
			return max - radius;
		}
		if (power(ax) + power(ay) + power(az) <= power(radius)) {
			return 0.0;
		}
		// The distance to the box falls as the box grows: it is at least the radius at the low end, 0 at the high end.
		double low = Math.max(0.0, max - radius);
		double high = max;
		for (int i = 0; i < 40; i++) {
			double half = (low + high) * 0.5;
			if (boxPower(ax, ay, az, half) > power(radius)) {
				low = half;
			} else {
				high = half;
			}
		}
		return (low + high) * 0.5;
	}

	/** The norm distance from a point to the box, to the power 2.5. */
	private static double boxPower(double ax, double ay, double az, double half) {
		return power(Math.max(ax - half, 0.0)) + power(Math.max(ay - half, 0.0)) + power(Math.max(az - half, 0.0));
	}

	/** A value of at least 0 to the power 2.5, without the cost of {@link Math#pow}: the turn runs for every block shade. */
	private static double power(double value) {
		return value * value * Math.sqrt(value);
	}
}

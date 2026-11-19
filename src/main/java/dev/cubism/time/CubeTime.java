package dev.cubism.time;

import dev.cubism.CubismMod;
import dev.cubism.mixin.time.AttributeTrackSamplerAccessor;
import dev.cubism.cube.SeamLayout;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.attribute.WeatherAttributes;
import net.minecraft.world.clock.ClockManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.timeline.Timeline;
import org.joml.Matrix3f;

import java.util.function.Supplier;

/**
 * One sun for the whole cube. The cube turns once a day about its diagonal (1, 1, 1) under a fixed sun, so every face
 * gets the same day, shifted in time: noon on UP is 4000 ticks after noon on WEST, and DOWN has midnight then. Each
 * face samples the vanilla timelines at its own local time, so the sky, light, mobs and beds follow the local sun.
 * The sun of a face rises and sets at the vanilla times, but stands 54.7 degrees high at noon, not overhead.
 */
public final class CubeTime {
	public static final int DAY_TICKS = 24000;
	private static final int NOON = 6000;
	/**
	 * Over this many blocks before an edge, the local time blends toward the time of the neighbor face. It is half way
	 * at the diagonal, where the camera crosses, so the sky has no step there.
	 */
	public static final int EDGE_BLEND = 32;
	/** Local times closer than this share one sky darkening value per tick. */
	private static final int DARKEN_STEP = 20;

	// The sun moves from U toward V in the plane normal to the axis. U is where the sun stands at noon on UP.
	private static final double[] AXIS = {1.0 / Math.sqrt(3.0), 1.0 / Math.sqrt(3.0), 1.0 / Math.sqrt(3.0)};
	private static final double[] U = {-1.0 / Math.sqrt(6.0), 2.0 / Math.sqrt(6.0), -1.0 / Math.sqrt(6.0)};
	private static final double[] V = {-1.0 / Math.sqrt(2.0), 0.0, 1.0 / Math.sqrt(2.0)};

	private CubeTime() {}

	/**
	 * Ticks to add to the clock for the local time at a storage position. The time follows the direction of the rounded
	 * cube surface under the position, which is the face normal except near the edges.
	 */
	public static int offsetTicks(SeamLayout layout, double x, double y, double z) {
		int face = layout.regionOfX(Mth.floor(x));
		double[] e = surfaceNormal(layout, x, y, z);
		double ex = e[0], ey = e[1], ez = e[2];
		int[] m = layout.rotation(face);
		double nx = m[0] * ex + m[1] * ey + m[2] * ez;
		double ny = m[3] * ex + m[4] * ey + m[5] * ez;
		double nz = m[6] * ex + m[7] * ey + m[8] * ez;
		double phase = Math.atan2(nx * V[0] + ny * V[1] + nz * V[2], nx * U[0] + ny * U[1] + nz * U[2]);
		return (int) Math.round(-phase / (2.0 * Math.PI) * DAY_TICKS);
	}

	/**
	 * The direction of the rounded cube surface under a position, in the storage frame of the position's face. It is not
	 * normalized. The sun is above the horizon of a position exactly when it is on this side of the plane normal to it.
	 */
	public static double[] surfaceNormal(SeamLayout layout, double x, double y, double z) {
		int face = layout.regionOfX(Mth.floor(x));
		double inner = layout.radius - EDGE_BLEND;
		// The center of the cube is the center of a block, as in SeamLayout.crossingFace. From the block corner, the two
		// faces of an edge would put it half a block apart, a step of about 25 ticks at each crossing.
		double ex = beyond(x - layout.offsetX(face) - 0.5, inner);
		double ey = beyond(y - layout.centerY - 0.5, inner);
		double ez = beyond(z - 0.5, inner);
		if (ex == 0.0 && ey == 0.0 && ez == 0.0) {
			ey = 1.0;
		}
		return new double[] {ex, ey, ez};
	}

	private static double beyond(double value, double limit) {
		return value - Mth.clamp(value, -limit, limit);
	}

	/**
	 * The rotation from the sky frame of vanilla, where the sun is at +Y, into the storage frame of a face. The stars
	 * keep their place relative to the sun, and the moon is opposite to it.
	 */
	public static Matrix3f celestial(SeamLayout layout, int face, double ticks) {
		double[] sun = sunDirection(ticks);
		double[] side = new double[3];
		side[0] = sun[1] * AXIS[2] - sun[2] * AXIS[1];
		side[1] = sun[2] * AXIS[0] - sun[0] * AXIS[2];
		side[2] = sun[0] * AXIS[1] - sun[1] * AXIS[0];
		// A cube direction goes into the face frame with the transpose of the face rotation.
		int[] m = layout.rotation(face);
		Matrix3f result = new Matrix3f();
		for (int row = 0; row < 3; row++) {
			result.setRowColumn(row, 0, (float) (m[row] * side[0] + m[3 + row] * side[1] + m[6 + row] * side[2]));
			result.setRowColumn(row, 1, (float) (m[row] * sun[0] + m[3 + row] * sun[1] + m[6 + row] * sun[2]));
			result.setRowColumn(row, 2, (float) (m[row] * AXIS[0] + m[3 + row] * AXIS[1] + m[6 + row] * AXIS[2]));
		}
		return result;
	}

	/** The direction from the cube toward the sun at a clock time, in cube coordinates. */
	public static double[] sunDirection(double ticks) {
		double angle = (ticks - NOON) / DAY_TICKS * 2.0 * Math.PI;
		double[] sun = new double[3];
		for (int i = 0; i < 3; i++) {
			sun[i] = Math.cos(angle) * U[i] + Math.sin(angle) * V[i];
		}
		return sun;
	}

	/** Ticks until the next local morning at a position, when sleepers wake. */
	public static int ticksToMorning(SeamLayout layout, long clockTicks, Vec3 pos) {
		return Math.floorMod(-(clockTicks + offsetTicks(layout, pos.x, pos.y, pos.z)), DAY_TICKS);
	}

	/**
	 * Replaces the timeline layers of vanilla in the cube level with layers that sample the local time. Returns false
	 * for any other level, which keeps the vanilla layers.
	 */
	public static boolean addDynamicLayers(EnvironmentAttributeSystem.Builder builder, Level level) {
		if (!level.dimensionTypeRegistration().is(CubismMod.CUBE_OVERWORLD_TYPE)) {
			return false;
		}
		State state = new State(level);
		((CubeTimeLevel) level).cubism$setCubeTime(state);
		ClockManager clocks = level.clockManager();
		level.dimensionType().timelines().forEach(timeline -> {
			Timeline value = ((Holder<Timeline>) timeline).value();
			for (EnvironmentAttribute<?> attribute : value.attributes()) {
				addTrack(builder, state, attribute, value, clocks);
			}
		});
		if (level.canHaveWeather()) {
			WeatherAttributes.addBuiltinLayers(builder, WeatherAttributes.WeatherAccess.from(level));
		}
		return true;
	}

	@SuppressWarnings("unchecked")
	private static <V, A> void addTrack(
		EnvironmentAttributeSystem.Builder builder, State state, EnvironmentAttribute<V> attribute, Timeline timeline, ClockManager clocks
	) {
		AttributeTrackSamplerAccessor<V, A> track = (AttributeTrackSamplerAccessor<V, A>) (Object) timeline.createTrackSampler(attribute, clocks);
		if (attribute == EnvironmentAttributes.MOON_PHASE) {
			// The moon is one body over the whole cube. With the local time its phase changed where the local day number
			// did, at a crossing of faces, so it takes the clock of the world.
			builder.addTimeBasedLayer(attribute, (base, cacheTickId) ->
				track.cubism$modifier().apply(base, track.cubism$argumentSampler().sample(clocks.getInstance(track.cubism$clock()).totalTicks())));
		} else if (attribute.isPositional()) {
			builder.addPositionalLayer(attribute, (base, pos, interpolator) ->
				track.cubism$modifier().apply(base, track.cubism$argumentSampler().sample(state.ticks(clocks.getInstance(track.cubism$clock()).totalTicks(), state.offsetAt(pos)))));
		} else {
			// No position: the time of the entity or chunk that ticks, which State.darken sets.
			builder.addTimeBasedLayer(attribute, (base, cacheTickId) ->
				track.cubism$modifier().apply(base, track.cubism$argumentSampler().sample(state.ticks(clocks.getInstance(track.cubism$clock()).totalTicks(), state.sampleOffset))));
		}
	}

	/** Runs an action with the sky darkening of the level at the local time of a position. */
	public static void at(Level level, double x, double y, double z, Runnable action) {
		at(level, x, y, z, () -> {
			action.run();
			return null;
		});
	}

	/**
	 * Runs an action with the sky darkening of the level at the local time of a position. Vanilla reads one darkening
	 * value for the whole level in many places without a position, such as {@code isBrightOutside} in mob goals, so
	 * the level holds the local value while the entity, chunk or block entity ticks.
	 */
	public static <T> T at(Level level, double x, double y, double z, Supplier<T> action) {
		CubeTimeLevel access = (CubeTimeLevel) level;
		State state = access.cubism$cubeTime();
		SeamLayout layout = state == null ? null : SeamLayout.of(level);
		if (layout == null) {
			return action.get();
		}
		int previous = access.cubism$skyDarken();
		access.cubism$setSkyDarken(state.darken(offsetTicks(layout, x, y, z)));
		try {
			return action.get();
		} finally {
			access.cubism$setSkyDarken(previous);
		}
	}

	/** The sky darkening at the local time of a position, as {@link Level#getSkyDarken} gives it in vanilla. */
	public static int darkenAt(Level level, double x, double y, double z) {
		State state = ((CubeTimeLevel) level).cubism$cubeTime();
		SeamLayout layout = state == null ? null : SeamLayout.of(level);
		return layout == null ? level.getSkyDarken() : state.darken(offsetTicks(layout, x, y, z));
	}

	public static final class State {
		private final Level level;
		/** Added to the clock by the layers of attributes that have no position. Zero outside {@link #darken}. */
		private int sampleOffset;
		private final Int2IntOpenHashMap darkenByOffset = new Int2IntOpenHashMap();
		private long darkenTick = Long.MIN_VALUE;
		/** When set, every layer samples this local time, for any position. Set only inside {@link #sampleAt}. */
		private long forcedTicks;
		private boolean forced;

		State(Level level) {
			this.level = level;
		}

		private int offsetAt(Vec3 pos) {
			SeamLayout layout = SeamLayout.of(this.level);
			return layout == null ? 0 : offsetTicks(layout, pos.x, pos.y, pos.z);
		}

		private long ticks(long clockTicks, int offset) {
			return this.forced ? this.forcedTicks : clockTicks + offset;
		}

		/** Reads attribute values as they are at one local time of day, wherever they are sampled. */
		public <T> T sampleAt(long ticks, Supplier<T> action) {
			EnvironmentAttributeSystem attributes = this.level.environmentAttributes();
			this.forcedTicks = ticks;
			this.forced = true;
			attributes.invalidateTickCache();
			try {
				return action.get();
			} finally {
				this.forced = false;
				attributes.invalidateTickCache();
			}
		}

		private int darken(int offset) {
			if (this.level.getGameTime() != this.darkenTick) {
				this.darkenTick = this.level.getGameTime();
				this.darkenByOffset.clear();
			}
			int key = Math.floorMod(Math.round(offset / (float) DARKEN_STEP) * DARKEN_STEP, DAY_TICKS);
			return this.darkenByOffset.computeIfAbsent(key, k -> {
				// The level caches attribute values for the tick, so the cache is cleared around the sample.
				EnvironmentAttributeSystem attributes = this.level.environmentAttributes();
				this.sampleOffset = k;
				attributes.invalidateTickCache();
				float light = attributes.getDimensionValue(EnvironmentAttributes.SKY_LIGHT_LEVEL);
				this.sampleOffset = 0;
				attributes.invalidateTickCache();
				return (int) (15.0F - light);
			});
		}
	}
}

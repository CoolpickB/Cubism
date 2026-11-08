package dev.cubism.client;

import dev.cubism.gravity.GravityLock;
import dev.cubism.debug.LoadTrace;
import dev.cubism.seam.SeamCrossing;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The frame of the air for the local player in flight: with an elytra, or in creative or spectator flight. Its up is
 * the direction of the rounded cube surface under the player, the reverse of {@link SeamLayout#airGravity}. Above the
 * flat part of a face it is the frame of the face. Over an edge it turns with the surface, and on the diagonal it is
 * half way between the two faces, so the frame has no step at a crossing.
 *
 * <p>The flight physics, the mouse look and the camera all work in this frame. A look in the face frame becomes yaw
 * and pitch in the air frame, vanilla moves the player there, and the result turns back. The frame eases in and out
 * over {@link #EASE_TICKS} ticks when flight starts and stops. All state here is used on the client thread only.
 */
public final class CubeFlight {
	private static final float EASE_TICKS = 8.0F;
	/** The radius of the view turn over an edge with the Edge Camera Tilt option off. */
	private static final double TILT_OFF_RADIUS = 2.0;
	/** The pitch in the air frame stays this far from straight up or down, where it gives no yaw. */
	private static final float MAX_PITCH = 89.9F;

	private static float weight;
	private static float weightO;
	/** True while vanilla moves the player in the air frame, where velocity and look are in that frame. */
	private static boolean inFrame;
	private static final Quaternionf physicsFrame = new Quaternionf();
	private static float worldYRot;
	private static float worldXRot;
	/** The last ticks before a crossing and the ticks after it, for the load trace. */
	private static final ArrayDeque<String> RECENT_TICKS = new ArrayDeque<>();
	private static int traceTicksLeft;

	private CubeFlight() {}

	/**
	 * Where the view frame of the player is taken, as an offset from the player. The turn over an edge depends on the
	 * height, so a step of a stair or a slab tilted the view by a few degrees in one tick, and a jump bobbed it. On foot
	 * the point eases after the ground, and a jump does not raise it. In flight and in a vehicle it is at the player.
	 */
	private static Vec3 viewOffset = Vec3.ZERO;
	private static Vec3 viewOffsetO = Vec3.ZERO;
	private static double groundY;
	private static int viewFace = -1;
	private static final double VIEW_EASE = 0.35;
	/**
	 * The most the eased point trails the target. A fall then moves the view at the speed of the fall, a block behind,
	 * where a snap at a larger gap tilted the view by up to 20 degrees in one tick.
	 */
	private static final double VIEW_MAX_LAG = 1.0;

	private static void tickViewY(LocalPlayer player, boolean flies) {
		SeamLayout layout = SeamLayout.of(player.level());
		int face = layout == null ? -1 : layout.faceIndexAt(Mth.floor(player.getX()));
		double y = player.getY();
		viewOffsetO = viewOffset;
		if (face != viewFace && face >= 0 && viewFace >= 0) {
			// The up of the old face is sideways in the new one. Kept as a height in the new face, a lag of half a block
			// moved the view 4 to 7 degrees at the crossing.
			int[] m = layout.motion(viewFace, face);
			viewOffset = SeamInteraction.rotate(m, viewOffset);
			viewOffsetO = SeamInteraction.rotate(m, viewOffsetO);
			// A fall over the edge moves up in the frame of the new face. Held as a jump from the crossing, the view
			// fell a third of a block behind and the turn slowed to half for two ticks. Until the next landing there is
			// no ground to hold to.
			groundY = Double.POSITIVE_INFINITY;
			traceLine(String.format(Locale.ROOT, "  view: face %d -> %d ground=%b y=%.2f offset=%.2f %.2f %.2f", viewFace, face, player.onGround(), y, viewOffset.x, viewOffset.y, viewOffset.z));
		}
		if (player.onGround()) {
			groundY = y;
		}
		if (flies || player.isPassenger() || face < 0 || viewFace < 0) {
			viewOffset = Vec3.ZERO;
			viewOffsetO = Vec3.ZERO;
			groundY = y;
		} else {
			Vec3 target = new Vec3(0.0, player.onGround() ? 0.0 : Math.min(0.0, groundY - y), 0.0);
			Vec3 lag = viewOffset.add(target.subtract(viewOffset).scale(VIEW_EASE)).subtract(target);
			viewOffset = target.add(lag.length() > VIEW_MAX_LAG ? lag.scale(VIEW_MAX_LAG / lag.length()) : lag);
		}
		viewFace = face;
	}

	/** Eases the frame in or out, then makes a crossing of a diagonal when the player flew past it. */
	public static void tick(LocalPlayer player) {
		weightO = weight;
		boolean flies = SeamLayout.of(player.level()) != null && SeamCrossing.flies(player);
		weight = Mth.clamp(weight + (flies ? 1.0F : -1.0F) / EASE_TICKS, 0.0F, 1.0F);
		float startYRot = player.getYRot();
		float startXRot = player.getXRot();
		if (flies || !player.isPassenger() && !player.isSpectator() || SeamCrossing.ridden(player.getRootVehicle()) == player) {
			cross(player, flies);
		}
		float crossYRot = player.getYRot();
		float crossXRot = player.getXRot();
		tickViewY(player, flies);
		carryLook(player);
		traceTick(player);
		traceLook(player);
	}

	/** The up of the view frame at the end of the last tick, in the face frame, and the face region it was in. */
	private static final Vector3f lastUp = new Vector3f(0.0F, 1.0F, 0.0F);
	private static int lastUpFace = -1;

	/**
	 * Turns the look with the up of the view frame, by the shortest turn from the last up to this one, as games with
	 * round planets do. The look then keeps its angle to the up: a look that stood still in the face while the frame
	 * tilted under it went over straight down of the frame, where the yaw in the frame flips, so the keys walked the
	 * other way and the camera rolled over, back and forth each tick.
	 */
	/** The cosine of the largest turn of the up at a crossing that the look turns with: 30 degrees. */
	private static final float SEAM_CARRY_MIN_COS = 0.866F;

	/** The roll of the screen about the look that eases out after a glide tilted the view frame, in radians. */
	private static float glideRoll;
	private static float glideRollO;
	private static final float GLIDE_ROLL_KEEP = 0.9F;

	private static Vec3 lastPosition = Vec3.ZERO;
	private static final double TELEPORT_DISTANCE = 8.0;

	/** Whether the player is farther from where the last tick put it, carried over a face change, than a step can reach. */
	private static boolean teleported(SeamLayout layout, int lastFace, int face, Vec3 position) {
		Vec3 last = lastPosition;
		if (face != lastFace) {
			int[] m = layout.motion(lastFace, face);
			if (m[4] == -1) {
				return true;
			}
			last = new Vec3(
				m[0] * last.x + m[1] * last.y + m[2] * last.z + m[9],
				m[3] * last.x + m[4] * last.y + m[5] * last.z + m[10],
				m[6] * last.x + m[7] * last.y + m[8] * last.z + m[11]);
		}
		return last.distanceToSqr(position) > TELEPORT_DISTANCE * TELEPORT_DISTANCE;
	}

	private static void carryLook(LocalPlayer player) {
		glideRollO = glideRoll;
		glideRoll *= GLIDE_ROLL_KEEP;
		SeamLayout layout = SeamLayout.of(player.level());
		int blockX = Mth.floor(player.getX());
		int face = layout != null && layout.inRegion(blockX, Mth.floor(player.getZ())) ? layout.faceIndexAt(blockX) : -1;
		Quaternionf view = viewFrame(player, 1.0F);
		Vector3f up = view.transform(new Vector3f(0.0F, 1.0F, 0.0F));
		Vector3f last = new Vector3f(lastUp);
		int lastFace = lastUpFace;
		lastUp.set(up);
		lastUpFace = face;
		if (inFrame || face < 0) {
			return;
		}
		if (BarrelRoll.rolling(player)) {
			// The roll of the mod holds the screen up. When it stops, the screen up starts again from the yaw.
			hasScreenUp = false;
			screenUpTilted = false;
			glideRoll = 0.0F;
			glideRollO = 0.0F;
			return;
		}
		boolean tiltEnds = screenUpTilted && isIdentity(view);
		// A world that was just joined or a teleport keeps no screen up from before: it would stay in the frame of the old
		// place, tilted wrong, until the look changed.
		Vec3 position = player.position();
		boolean placed = lastFace < 0 || teleported(layout, lastFace, face, position);
		lastPosition = position;
		if (placed) {
			hasScreenUp = false;
			screenUpTilted = false;
		}
		syncScreenUp(player, view);
		if (placed) {
			upright(screenUp, syncedLook, view);
			screenUpTilted = !isIdentity(view);
			lastUp.set(up);
		}
		screenUpO.set(screenUp);
		// The tilt ended and the yaw took the screen heading. The last step of the tilt is a fraction of a degree, but
		// carried after that it moved a yaw close to straight down by 20 degrees, and the screen rolled with it.
		if (placed || tiltEnds) {
			return;
		}
		if (face != lastFace) {
			// The crossing turned the look by the face motion, so the last up turns the same way.
			int[] m = layout.motion(lastFace, face);
			last.set(m[0] * last.x + m[1] * last.y + m[2] * last.z, m[3] * last.x + m[4] * last.y + m[5] * last.z, m[6] * last.x + m[7] * last.y + m[8] * last.z);
		}
		if (last.distanceSquared(up) < 1.0E-12F) {
			return;
		}
		// A glide keeps its look where the player put it: carried, a fast glide over a small rounding turned the look 90
		// degrees in a few ticks, down to the new face and into the ground there. The screen still levels with the tilt.
		// The level comes over some ticks: at once, a fast glide over a small rounding rolled the screen with a jerk.
		if (player.isFallFlying()) {
			Vector3f before = new Vector3f(screenUp);
			upright(screenUp, syncedLook, view);
			glideRoll += (float) Math.atan2(new Vector3f(screenUp).cross(before).dot(syncedLook), screenUp.dot(before));
			return;
		}
		if (face != lastFace && last.dot(up) < SEAM_CARRY_MIN_COS) {
			// The view did not tilt with the ground, so the up turns by a whole face at the seam. The camera eases that
			// turn (see CameraMixin), and the look keeps its direction. Carried, it turned 90 degrees in one tick.
			return;
		}
		float yRot = player.getYRot();
		float xRot = player.getXRot();
		Quaternionf carry = new Quaternionf().rotationTo(last, up);
		Vector3f carried = carry.transform(look(yRot, xRot));
		player.setYRot(yaw(carried, yRot));
		player.setXRot(pitch(carried));
		// The camera shows the carry from the last look over the tick, so the sway moves only at the end of the tick.
		player.yBob += player.getYRot() - yRot;
		player.xBob += player.getXRot() - xRot;
		// The head and body keep their yaw relative to the look, or the model in third person turns a tick late.
		player.yHeadRot += player.getYRot() - yRot;
		player.yBodyRot += player.getYRot() - yRot;
		carry.transform(screenUp);
		syncedLook.set(look(player.getYRot(), player.getXRot()));
		upright(screenUp, syncedLook, view);
	}

	/**
	 * The up of the screen for the local player in the face frame, at the end of this tick and at its start. It is kept
	 * as a direction and turned with the look, as games with round planets keep the camera. Worked out from the
	 * angles, it comes from the small part of a look close to straight down that is not straight down, so near there
	 * a small change spun the screen, and the screen shook or rolled over.
	 */
	private static final Vector3f screenUp = new Vector3f(0.0F, 1.0F, 0.0F);
	private static final Vector3f screenUpO = new Vector3f(0.0F, 1.0F, 0.0F);
	/** The look that {@link #screenUp} belongs to. */
	private static final Vector3f syncedLook = new Vector3f(0.0F, 0.0F, 1.0F);
	private static boolean hasScreenUp;
	/** Whether {@link #screenUp} was last synced in a tilted view frame. */
	private static boolean screenUpTilted;

	/**
	 * Gives the yaw the heading of the screen when the view frame turns back to the face frame, where the screen up
	 * comes from the yaw again. A look near straight down can be a little past it, and its yaw then points away from
	 * the screen heading: the screen spun half a turn the tick the tilt ended, and the keys walked the other way.
	 */
	private static void yawToScreenHeading(LocalPlayer player) {
		float yRot = player.getYRot();
		float change = yaw(heading(look(yRot, player.getXRot()), new Quaternionf()), yRot) - yRot;
		player.setYRot(yRot + change);
		player.yRotO += change;
		swayHand(player, change, 0.0F);
	}

	/**
	 * Moves the sway of the held item with a change of the look that the screen does not show. The sway trails the yaw
	 * and pitch, so a yaw that jumps half a turn at a look close to straight down swung the hand across the screen.
	 */
	private static void swayHand(LocalPlayer player, float yawChange, float pitchChange) {
		player.yBob += yawChange;
		player.yBobO += yawChange;
		player.xBob += pitchChange;
		player.xBobO += pitchChange;
	}

	/** The up of the screen that vanilla shows for a look of yaw and pitch in the face frame. */
	private static Vector3f faceScreenUp(float yRot, float xRot) {
		return new Quaternionf().rotationYXZ((float) Math.PI - yRot * Mth.DEG_TO_RAD, -xRot * Mth.DEG_TO_RAD, 0.0F).transform(new Vector3f(0.0F, 1.0F, 0.0F));
	}

	/**
	 * Brings the screen up in line with the look of the player. Over a flat face it is the up that vanilla shows. A
	 * change of the look from elsewhere, such as vanilla or the server, carries it along by the shortest turn.
	 */
	private static void syncScreenUp(LocalPlayer player, Quaternionf view) {
		boolean identity = isIdentity(view);
		if (identity && hasScreenUp && screenUpTilted) {
			yawToScreenHeading(player);
		}
		screenUpTilted = !identity;
		Vector3f look = look(player.getYRot(), player.getXRot());
		if (!hasScreenUp || identity) {
			screenUp.set(faceScreenUp(player.getYRot(), player.getXRot()));
			screenUpO.set(faceScreenUp(player.yRotO, player.xRotO));
			hasScreenUp = true;
		} else if (!look.equals(syncedLook, 1.0E-6F)) {
			new Quaternionf().rotationTo(syncedLook, look).transform(screenUp);
			upright(screenUp, look, view);
		}
		syncedLook.set(look);
	}

	/**
	 * Makes the screen up upright in the view frame where that is clear. Within 10 degrees of straight down or up it
	 * only stays square to the look, as every turn here keeps it upright already.
	 */
	private static void upright(Vector3f up, Vector3f look, Quaternionf view) {
		Vector3f viewUp = view.transform(new Vector3f(0.0F, 1.0F, 0.0F));
		float along = viewUp.dot(look);
		if (Math.abs(along) < 0.985F) {
			up.set(viewUp).fma(-along, look).normalize();
		} else {
			up.fma(-up.dot(look), look).normalize();
		}
	}

	/** The direction the screen faces level in the view frame, from the look and the screen up. */
	private static Vector3f heading(Vector3f look, Quaternionf view) {
		Vector3f viewUp = view.transform(new Vector3f(0.0F, 1.0F, 0.0F));
		return new Vector3f(look).mul(screenUp.dot(viewUp)).fma(-look.dot(viewUp), screenUp).normalize();
	}

	/** The up of the screen of the local player for the camera, in the face frame. */
	public static Vector3f screenUp(LocalPlayer player, float partialTicks) {
		syncScreenUp(player, viewFrame(player, 1.0F));
		return new Vector3f(screenUpO).lerp(screenUp, partialTicks);
	}

	/** The roll about the look that the camera adds to {@link #screenUp}, in radians. */
	public static float glideRoll(float partialTicks) {
		return Mth.lerp(partialTicks, glideRollO, glideRoll);
	}

	/** Ticks before Steadfast ends in which the view wobbles, and the largest roll of the wobble in degrees. */
	private static final float WOBBLE_TICKS = 100.0F;
	private static final float WOBBLE_DEGREES = 5.0F;

	/**
	 * A roll of the view, in radians, that warns that Steadfast runs out: it grows over the last 5 s. The effect of
	 * worn treads is ambient and lasts 1 s at a time, and it does not wobble.
	 */
	public static float steadfastWobble(LocalPlayer player, float partialTicks) {
		MobEffectInstance effect = player.getEffect(GravityLock.STEADFAST);
		if (effect == null || effect.isAmbient() || effect.isInfiniteDuration()) {
			return 0.0F;
		}
		float left = effect.getDuration() - partialTicks;
		if (left >= WOBBLE_TICKS) {
			return 0.0F;
		}
		float strength = 1.0F - Math.max(left, 0.0F) / WOBBLE_TICKS;
		return strength * WOBBLE_DEGREES * Mth.DEG_TO_RAD * Mth.sin((player.tickCount + partialTicks) * Mth.TWO_PI / 20.0F);
	}

	/** The up of the screen that vanilla shows, for the camera. */
	public static Vector3f vanillaScreenUp(float yRot, float xRot) {
		return faceScreenUp(yRot, xRot);
	}

	private static java.io.PrintWriter lookLog;
	private static int lookTicksLeft;

	/** Writes one line per tick while the view frame is tilted and for 20 ticks after: for finding where the look and the keys disagree. */
	/** Writes a line to the look log when {@link ClientConfig#traceLook} is on. */
	/** Wall time spent since the last frame line, for the look trace: terrain setup (all faces), the folded faces alone, and chunk moves on a recenter. */
	public static long traceTerrainNanos;
	public static long traceFoldedNanos;
	public static long traceRecenterNanos;
	private static long traceLastFrame;

	/** The frame time and the work measured since the last call, and starts the next count. */
	public static String traceFrameTimes() {
		long now = System.nanoTime();
		String times = String.format(Locale.ROOT, " ms=%.1f terrain=%.1f folded=%.1f recenter=%.1f",
			traceLastFrame == 0 ? 0.0 : (now - traceLastFrame) / 1.0E6, traceTerrainNanos / 1.0E6, traceFoldedNanos / 1.0E6, traceRecenterNanos / 1.0E6);
		traceLastFrame = now;
		traceTerrainNanos = 0;
		traceFoldedNanos = 0;
		traceRecenterNanos = 0;
		return times;
	}

	public static void traceLine(String line) {
		if (!ClientConfig.traceLook || lookLog == null || lookTicksLeft <= 0) {
			return;
		}
		lookLog.println(line);
	}

	private static void traceLook(LocalPlayer player) {
		if (!ClientConfig.traceLook) {
			return;
		}
		Quaternionf view = viewFrame(player, 1.0F);
		if (!isIdentity(view) || BarrelRoll.rolling(player) || player.hasEffect(GravityLock.STEADFAST)) {
			lookTicksLeft = 20;
		} else if (lookTicksLeft > 0) {
			lookTicksLeft--;
		} else {
			return;
		}
		try {
			if (lookLog == null) {
				java.nio.file.Path file = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("logs").resolve("cubism-look.log");
				lookLog = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(file), true);
			}
			SeamLayout layout = SeamLayout.of(player.level());
			int face = layout == null ? -1 : layout.faceIndexAt(Mth.floor(player.getX()));
			float[] angles = anglesIn(view, player.getYRot(), player.getXRot());
			Vec3 delta = player.getDeltaMovement();
			lookLog.println(String.format(Locale.ROOT, "t=%d face=%d pos=%.2f %.2f %.2f yaw=%.1f pitch=%.1f roll=%.1f airYaw=%.1f airPitch=%.1f moveYaw=%.1f tilt=%.1f vel=%.3f %.3f %.3f move=%s ground=%b viewY=%.2f lag=%.2f %.2f %.2f groundY=%.2f lock=%s fly=%b glide=%b sneak=%b",
				player.tickCount, face, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(), BarrelRoll.roll(player), angles[0], angles[1], moveYaw(player),
				Math.toDegrees(view.angle()), delta.x, delta.y, delta.z, player.input.getMoveVector(), player.onGround(), player.getY() + viewOffset.y, viewOffset.x, viewOffset.y, viewOffset.z, groundY,
				player.hasEffect(GravityLock.STEADFAST) ? player.getEffect(GravityLock.STEADFAST).getAmplifier() + (GravityLock.holdsFace(player) ? "held" : "free") : "none",
				player.getAbilities().flying, player.isFallFlying(), player.isShiftKeyDown()));
		} catch (java.io.IOException e) {
			lookLog = null;
		}
	}

	/** Logs the position, velocity and view of the player for 10 ticks before and 30 after each crossing. */
	private static void traceTick(LocalPlayer player) {
		if (!LoadTrace.active()) {
			return;
		}
		Quaternionf view = viewFrame(player, 1.0F);
		Vec3 delta = player.getDeltaMovement();
		String line = String.format(Locale.ROOT, "  tick pos=%.3f %.3f %.3f vel=%.3f %.3f %.3f yaw=%.1f pitch=%.1f ground=%b tilt=%.1f",
			player.getX(), player.getY(), player.getZ(), delta.x, delta.y, delta.z, player.getYRot(), player.getXRot(),
			player.onGround(), Math.toDegrees(view.angle()));
		if (traceTicksLeft > 0) {
			traceTicksLeft--;
			LoadTrace.event(line);
			return;
		}
		RECENT_TICKS.addLast(line);
		if (RECENT_TICKS.size() > 10) {
			RECENT_TICKS.removeFirst();
		}
	}

	/** The rotation from the air frame into the face frame of the region the entity is in. Identity for other entities. */
	public static Quaternionf frame(Entity entity, float partialTicks) {
		SeamLayout layout = SeamLayout.of(entity.level());
		float t = Mth.lerp(partialTicks, weightO, weight);
		if (layout == null || !(entity instanceof LocalPlayer) || t <= 0.0F || GravityLock.locked(entity)) {
			return new Quaternionf();
		}
		return frameAt(layout, entity.getPosition(partialTicks).add(0.0, entity.getBbHeight() / 2.0, 0.0), t, layout.turnRadius());
	}

	/** The air frame at a body center for a radius of the turn over an edge, eased in by a weight. */
	private static Quaternionf frameAt(SeamLayout layout, Vec3 center, float t, double radius) {
		Vec3 gravity = layout.airGravity(center.x, center.y, center.z, radius);
		if (gravity == null || t <= 0.0F) {
			return new Quaternionf();
		}
		Quaternionf full = new Quaternionf().rotationTo(0.0F, 1.0F, 0.0F, (float) -gravity.x, (float) -gravity.y, (float) -gravity.z);
		return new Quaternionf().slerp(full, t * t * (3.0F - 2.0F * t));
	}

	private static boolean isIdentity(Quaternionf q) {
		return Math.abs(q.x) < 1.0E-6F && Math.abs(q.y) < 1.0E-6F && Math.abs(q.z) < 1.0E-6F;
	}

	/** The look of yaw and pitch, in degrees, as a unit direction. */
	public static Vector3f look(float yRot, float xRot) {
		Vec3 look = Vec3.directionFromRotation(xRot, yRot);
		return new Vector3f((float) look.x, (float) look.y, (float) look.z);
	}

	/** Yaw of a unit direction, in degrees, near a yaw it continues from. */
	private static float yaw(Vector3f look, float near) {
		return near + Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-look.x, look.z)) - near);
	}

	private static float pitch(Vector3f look) {
		return (float) Math.toDegrees(Math.asin(Mth.clamp(-look.y, -1.0F, 1.0F)));
	}

	/** Yaw and pitch in a frame for a look of yaw and pitch in the face frame. */
	public static float[] anglesIn(Quaternionf frame, float yRot, float xRot) {
		Vector3f local = new Quaternionf(frame).conjugate().transform(look(yRot, xRot));
		return new float[] {yaw(local, yRot), pitch(local)};
	}

	/**
	 * The frame of the view: the air frame at the entity, at full weight, also on the ground. Over a rounded edge the
	 * ground is a slope of the face frame, up to 45 degrees on a seam, and this frame turns the view with the surface so
	 * that the ground looks level. It is the identity above the flat part of a face. Unlike {@link #frame} it does not
	 * ease with the flight. The eye stands along its up, on the ground too: the eye of vanilla stands along the up of
	 * the face, and at a crossing it swung about 1 block to the up of the next face.
	 */
	public static Quaternionf viewFrame(Entity entity, float partialTicks) {
		SeamLayout layout = SeamLayout.of(entity.level());
		// Under Steadfast the pull stays straight down in the face frame, so the view does too.
		if (layout == null || entity instanceof LocalPlayer && BarrelRoll.rolling(entity) || GravityLock.locked(entity)) {
			return new Quaternionf();
		}
		Vec3 position = entity.getPosition(partialTicks);
		Vec3 offset = entity instanceof LocalPlayer ? viewOffsetO.lerp(viewOffset, partialTicks) : Vec3.ZERO;
		Vec3 center = position.add(offset).add(0.0, entity.getBbHeight() / 2.0, 0.0);
		if (ClientConfig.edgeCameraTilt() || !(entity instanceof LocalPlayer)) {
			return frameAt(layout, center, 1.0F, layout.turnRadius());
		}
		// With the tilt off the view on the ground turns only over the last blocks before the diagonal, so it does not
		// snap at the crossing. In flight it eases to the frame of the flight.
		return frameAt(layout, center, 1.0F, TILT_OFF_RADIUS).slerp(frameAt(layout, center, 1.0F, layout.turnRadius()), flightWeight(partialTicks));
	}

	/** How far the body is in flight, from 0 on the ground to 1 in flight. */
	public static float flightWeight(float partialTicks) {
		return Mth.lerp(partialTicks, weightO, weight);
	}

	/**
	 * Turns the look by mouse movement about the up of the frame the camera stands in: the air frame in flight, the
	 * view frame near an edge. Vanilla turns about the up of the face, which the camera does not show there, so a move to the
	 * right turned the view sideways, and at a pitch of 90 it did nothing. Returns false when the camera stands in the
	 * face frame and vanilla can turn the look.
	 */
	public static boolean turn(LocalPlayer player, double xo, double yo) {
		Quaternionf frame = viewFrame(player, 1.0F);
		if (isIdentity(frame)) {
			return false;
		}
		// The mouse handler turns the player every frame, also by nothing. The look through the frame and back changes
		// by a rounding error each time, which adds up to a slow drift.
		if (xo == 0.0 && yo == 0.0) {
			return true;
		}
		float yawChange = (float) xo * 0.15F;
		float pitchChange = (float) yo * 0.15F;
		syncScreenUp(player, frame);
		Vector3f look = look(player.getYRot(), player.getXRot());
		Vector3f up = frame.transform(new Vector3f(0.0F, 1.0F, 0.0F));
		// The pitch from the look and the screen up, which goes past 90 where the look went over straight down.
		float pitch = (float) Math.toDegrees(Math.atan2(-look.dot(up), screenUp.dot(up)));
		float change = Mth.clamp(pitch + pitchChange, -MAX_PITCH, MAX_PITCH) - pitch;
		// Down about the right of the screen, then right about the up of the frame. Both keep the screen upright. The
		// last look turns the same way, as vanilla adds the change to both.
		Quaternionf turn = new Quaternionf().rotationAxis(-yawChange * Mth.DEG_TO_RAD, up)
			.mul(new Quaternionf().rotationAxis(change * Mth.DEG_TO_RAD, new Vector3f(screenUp).cross(look).normalize()));
		Vector3f turned = turn.transform(new Vector3f(look));
		Vector3f oldLook = turn.transform(look(player.yRotO, player.xRotO));
		turn.transform(screenUp);
		turn.transform(screenUpO);
		float newYRot = yaw(turned, player.getYRot());
		float newXRot = pitch(turned);
		// The hand sways by the turn the screen shows, as in vanilla, and not by the larger turn of the yaw near straight down.
		swayHand(player, newYRot - player.getYRot() - yawChange, newXRot - player.getXRot() - change);
		player.setYRot(newYRot);
		player.setXRot(newXRot);
		player.yRotO = yaw(oldLook, newYRot);
		player.xRotO = pitch(oldLook);
		syncedLook.set(look(player.getYRot(), player.getXRot()));
		return true;
	}

	/**
	 * The yaw that the movement keys walk along: the forward of the screen, level in the view frame. Near an edge the
	 * view frame tilts the camera, so a look that the screen shows 45 degrees down can be straight down in the face.
	 * There the yaw of the face turned far with a small mouse move and flipped past the vertical, and W walked away
	 * from where the screen looked.
	 */
	public static float moveYaw(LocalPlayer player) {
		Quaternionf frame = viewFrame(player, 1.0F);
		if (inFrame || isIdentity(frame)) {
			return player.getYRot();
		}
		syncScreenUp(player, frame);
		return yaw(heading(look(player.getYRot(), player.getXRot()), frame), player.getYRot());
	}

	/**
	 * Starts vanilla flight physics in the air frame: the velocity and look turn into the frame. Returns false when the
	 * player does not fly or the frame is the face frame, and nothing changes. After a landing the camera eases back to
	 * the face frame, but walking is in the face frame at once.
	 */
	public static boolean enterFrame(LocalPlayer player) {
		Quaternionf frame = frame(player, 1.0F);
		if (!SeamCrossing.flies(player) || isIdentity(frame)) {
			return false;
		}
		physicsFrame.set(frame);
		worldYRot = player.getYRot();
		worldXRot = player.getXRot();
		syncScreenUp(player, viewFrame(player, 1.0F));
		// The yaw comes from the heading of the screen, which a look close to straight down of the frame still has.
		Quaternionf toAir = new Quaternionf(frame).conjugate();
		Vector3f look = look(worldYRot, worldXRot);
		Vector3f heading = toAir.transform(heading(look, frame));
		player.setDeltaMovement(turn(toAir, player.getDeltaMovement()));
		player.setYRot(yaw(heading, worldYRot));
		player.setXRot(pitch(toAir.transform(look)));
		inFrame = true;
		return true;
	}

	/** Ends flight physics in the air frame: the velocity turns back, and the look is the one from before. */
	public static void leaveFrame(LocalPlayer player) {
		inFrame = false;
		player.setDeltaMovement(turn(physicsFrame, player.getDeltaMovement()));
		player.setYRot(worldYRot);
		player.setXRot(worldXRot);
	}

	public static boolean inFrame() {
		return inFrame;
	}

	/**
	 * Runs a move of vanilla, which collides with blocks in the face frame, from inside the air frame: the move and the
	 * velocity turn into the face frame for it and back after.
	 */
	public static void moveInFaceFrame(LocalPlayer player, Vec3 delta, Consumer<Vec3> move) {
		float yRot = player.getYRot();
		float xRot = player.getXRot();
		leaveFrame(player);
		try {
			move.accept(turn(physicsFrame, delta));
		} finally {
			player.setDeltaMovement(turn(new Quaternionf(physicsFrame).conjugate(), player.getDeltaMovement()));
			player.setYRot(yRot);
			player.setXRot(xRot);
			inFrame = true;
		}
	}

	/** A change of velocity along the up of the face, turned to the up of the air frame. */
	public static Vec3 upInFrame(LocalPlayer player, Vec3 change) {
		return turn(frame(player, 1.0F), change);
	}

	/**
	 * The eye as the camera shows it: the eye stands along the up of the air frame, about the center of the body. The
	 * eye of vanilla stands along the up of the face.
	 */
	public static Vec3 eyePosition(Entity entity, float partialTicks, Vec3 vanillaEye) {
		Quaternionf frame = viewFrame(entity, partialTicks);
		if (isIdentity(frame)) {
			return vanillaEye;
		}
		float halfHeight = entity.getBbHeight() / 2.0F;
		Vector3f eye = frame.transform(new Vector3f(0.0F, entity.getEyeHeight() - halfHeight, 0.0F));
		return SeamCrossing.clearEye(entity, vanillaEye, entity.getPosition(partialTicks).add(eye.x, halfHeight + eye.y, eye.z));
	}

	/** A direction in the face frame, turned into the air frame. */
	public static Vec3 inAirFrame(LocalPlayer player, Vec3 v) {
		return turn(frame(player, 1.0F).conjugate(), v);
	}

	private static Vec3 turn(Quaternionf q, Vec3 v) {
		Vector3f turned = q.transform(new Vector3f((float) v.x, (float) v.y, (float) v.z));
		return new Vec3(turned.x, turned.y, turned.z);
	}

	/**
	 * Moves the player into the region of the next face when it flew or walked past the diagonal, the same way the server
	 * moves other entities. The server follows with the first movement in the new region, so a walker does not wait a
	 * tick for the server to move it. The last tick moves along, so the frame between the two ticks stays in place.
	 */
	private static void cross(LocalPlayer player, boolean flies) {
		SeamLayout layout = SeamLayout.of(player.level());
		if (layout == null) {
			return;
		}
		// A ridden vehicle carries the player over, as the player alone would cross.
		Entity body = player.isPassenger() ? player.getRootVehicle() : player;
		Vec3 center = body.getBoundingBox().getCenter();
		int forced = GravityLock.forcedFace(player);
		int toFace = forced >= 0 ? (forced == layout.faceIndexAt(Mth.floor(body.getX())) ? -1 : forced)
			: GravityLock.holdsFace(player) ? -1 : layout.crossingFace(center.x, center.y, center.z, SeamCrossing.MARGIN);
		if (toFace < 0) {
			return;
		}
		// A walker crosses by the rule of the server: far enough past the diagonal, or with ground under it in the new frame.
		if (forced < 0 && !flies && layout.crossingFace(center.x, center.y, center.z, SeamCrossing.FORCED_MARGIN) < 0
			&& !SeamCrossing.landsOn(player.level(), body, SeamCrossing.turned(layout, body, toFace).position())) {
			return;
		}
		int[] m = layout.motion(layout.faceIndexAt(Mth.floor(body.getX())), toFace);
		Vector3f oldLook = turnLook(m, look(player.yRotO, player.xRotO));
		SeamCrossing.Turned turned = SeamCrossing.turned(layout, player, toFace);
		if (body != player) {
			float yawChange = turnBody(body, m, SeamCrossing.turned(layout, body, toFace));
			if (body instanceof LivingEntity living) {
				living.yBodyRot += yawChange;
				living.yBodyRotO += yawChange;
				living.yHeadRot += yawChange;
				living.yHeadRotO += yawChange;
			}
		}
		turnBody(player, m, turned);
		// The chunk cache sorts chunks into the vanilla and the seam store by this center. The server moves it only when
		// it follows the crossing, and until then the chunks of the new region would be seam chunks.
		((ClientLevel) player.level()).getChunkSource().updateViewCenter(SectionPos.blockToSectionCoord(player.getX()), SectionPos.blockToSectionCoord(player.getZ()));
		// The hand sways behind the look. It keeps the same lag behind the turned look, or it swings at the crossing.
		float yRot = player.getYRot();
		float xRot = player.getXRot();
		player.yBob = turned.yRot() - Mth.wrapDegrees(yRot - player.yBob);
		player.yBobO = turned.yRot() - Mth.wrapDegrees(yRot - player.yBobO);
		player.xBob = turned.xRot() - (xRot - player.xBob);
		player.xBobO = turned.xRot() - (xRot - player.xBobO);
		// The body and head keep their yaw relative to the look, or the model spins to catch up.
		float yawChange = turned.yRot() - yRot;
		player.yBodyRot += yawChange;
		player.yBodyRotO += yawChange;
		player.yHeadRot += yawChange;
		player.yHeadRotO += yawChange;
		player.setYRot(turned.yRot());
		player.setXRot(turned.xRot());
		player.yRotO = yaw(oldLook, turned.yRot());
		player.xRotO = pitch(oldLook);
		screenUp.set(turnLook(m, screenUp));
		syncedLook.set(look(player.getYRot(), player.getXRot()));
		((TurnableAvatarState) player.avatarState()).cubism$turn(m, player.getBbHeight() / 2.0);
		if (LoadTrace.active()) {
			RECENT_TICKS.forEach(LoadTrace::event);
			RECENT_TICKS.clear();
			traceTicksLeft = 30;
		}
	}

	/** The camera rotation that vanilla, with the roll of the Do a Barrel Roll mod, shows for a look in the face frame. */
	public static Quaternionf rolledView(float yRot, float xRot, float roll) {
		return new Quaternionf().rotationYXZ((float) Math.PI - yRot * Mth.DEG_TO_RAD, -xRot * Mth.DEG_TO_RAD, -roll * Mth.DEG_TO_RAD);
	}

	/** Where the sky and the light read the local time: the body center, which a crossing carries over exactly. */
	public static Vec3 timeFocus(Entity body, float partialTicks) {
		return body.getPosition(partialTicks).add(0.0, body.getBbHeight() / 2.0, 0.0);
	}

	/**
	 * Puts a body where the crossing turns it, with its velocity and yaw. The last positions turn along, so the frame
	 * between the two ticks stays in place. The crossing can move the body off a block (see SeamCrossing.turned); the
	 * last positions move with it, or the camera passes through that block on the way during the first tick.
	 */
	/** Returns how far the yaw of a body other than the player turned, in degrees. */
	private static float turnBody(Entity body, int[] m, SeamCrossing.Turned turned) {
		double halfHeight = body.getBbHeight() / 2.0;
		Vec3 old = SeamInteraction.move(m, new Vec3(body.xo, body.yo + halfHeight, body.zo));
		Vec3 walked = SeamInteraction.move(m, new Vec3(body.xOld, body.yOld + halfHeight, body.zOld));
		Vec3 here = SeamInteraction.move(m, new Vec3(body.getX(), body.getY() + halfHeight, body.getZ()));
		body.setPos(turned.position());
		body.setDeltaMovement(turned.velocity());
		Vec3 nudge = turned.position().subtract(here.subtract(0.0, halfHeight, 0.0));
		body.xo = old.x + nudge.x;
		body.yo = old.y - halfHeight + nudge.y;
		body.zo = old.z + nudge.z;
		body.xOld = walked.x + nudge.x;
		body.yOld = walked.y - halfHeight + nudge.y;
		body.zOld = walked.z + nudge.z;
		if (body instanceof LocalPlayer) {
			return 0.0F;
		}
		float yawChange = Mth.wrapDegrees(turned.yRot() - body.getYRot());
		body.setYRot(turned.yRot());
		body.yRotO += yawChange;
		return yawChange;
	}

	private static Vector3f turnLook(int[] m, Vector3f v) {
		Vec3 turned = SeamInteraction.rotate(m, new Vec3(v.x, v.y, v.z));
		return new Vector3f((float) turned.x, (float) turned.y, (float) turned.z);
	}
}

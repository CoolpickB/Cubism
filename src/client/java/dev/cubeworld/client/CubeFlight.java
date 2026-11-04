package dev.cubeworld.client;

import dev.cubeworld.debug.LoadTrace;
import dev.cubeworld.seam.SeamCrossing;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
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

	/** Eases the frame in or out, then makes a crossing of a diagonal when the player flew past it. */
	public static void tick(LocalPlayer player) {
		weightO = weight;
		boolean flies = SeamLayout.of(player.level()) != null && SeamCrossing.flies(player);
		weight = Mth.clamp(weight + (flies ? 1.0F : -1.0F) / EASE_TICKS, 0.0F, 1.0F);
		if (flies || !player.isPassenger() && !player.isSpectator()) {
			cross(player, flies);
		}
		traceTick(player);
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
		if (layout == null || !(entity instanceof LocalPlayer) || t <= 0.0F) {
			return new Quaternionf();
		}
		return frameAt(layout, entity.getPosition(partialTicks).add(0.0, entity.getBbHeight() / 2.0, 0.0), t);
	}

	/** The air frame at a body center, eased in by a weight. */
	private static Quaternionf frameAt(SeamLayout layout, Vec3 center, float t) {
		Vec3 gravity = layout.airGravity(center.x, center.y, center.z);
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
		if (layout == null) {
			return new Quaternionf();
		}
		return frameAt(layout, entity.getPosition(partialTicks).add(0.0, entity.getBbHeight() / 2.0, 0.0), 1.0F);
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
		// A steep look on the ground turns about the up of the face, like the roll of the camera (see CameraMixin).
		if (isIdentity(frame) || !inFrame && !SeamCrossing.flies(player) && SeamCrossing.groundWeight(Math.abs(player.getXRot())) >= 1.0F) {
			return false;
		}
		// The mouse handler turns the player every frame, also by nothing. The look through the frame and back changes
		// by a rounding error each time, which adds up to a slow drift.
		if (xo == 0.0 && yo == 0.0) {
			return true;
		}
		float yawChange = (float) xo * 0.15F;
		float pitchChange = (float) yo * 0.15F;
		Vector3f look = turnIn(frame, player.getYRot(), player.getXRot(), yawChange, pitchChange);
		Vector3f oldLook = turnIn(frame, player.yRotO, player.xRotO, yawChange, pitchChange);
		float newYRot = yaw(look, player.getYRot());
		player.setYRot(newYRot);
		player.setXRot(pitch(look));
		// The last look turns the same way, as vanilla adds the change to both. Past straight down or up of the face, the
		// yaw flips, and a change added to the old angles would point the old look elsewhere.
		player.yRotO = yaw(oldLook, newYRot);
		player.xRotO = pitch(oldLook);
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
		float[] angles = anglesIn(frame, player.getYRot(), player.getXRot());
		float air = yaw(frame.transform(look(angles[0], 0.0F)), player.getYRot());
		// Near straight down or up the air yaw is the direction in which the frame tilts, which is the opposite of the
		// facing when the player walks backward over an edge. The keys would push the player out of the edge band and
		// back in, so a steep look walks along the facing.
		float ground = SeamCrossing.flies(player) ? 0.0F : SeamCrossing.groundWeight(Math.abs(player.getXRot()));
		return air + ground * Mth.wrapDegrees(player.getYRot() - air);
	}

	private static Vector3f turnIn(Quaternionf frame, float yRot, float xRot, float yawChange, float pitchChange) {
		float[] angles = anglesIn(frame, yRot, xRot);
		return frame.transform(look(angles[0] + yawChange, Mth.clamp(angles[1] + pitchChange, -MAX_PITCH, MAX_PITCH)));
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
		float[] angles = anglesIn(frame, worldYRot, worldXRot);
		player.setDeltaMovement(turn(new Quaternionf(frame).conjugate(), player.getDeltaMovement()));
		player.setYRot(angles[0]);
		player.setXRot(angles[1]);
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
		return entity.getPosition(partialTicks).add(eye.x, halfHeight + eye.y, eye.z);
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
		Vec3 center = player.getBoundingBox().getCenter();
		int toFace = layout.crossingFace(center.x, center.y, center.z, SeamCrossing.MARGIN);
		if (toFace < 0) {
			return;
		}
		// A walker crosses by the rule of the server: far enough past the diagonal, or with ground under it in the new frame.
		if (!flies && layout.crossingFace(center.x, center.y, center.z, SeamCrossing.FORCED_MARGIN) < 0
			&& !SeamCrossing.landsOn(player.level(), player, SeamCrossing.turned(layout, player, toFace).position())) {
			return;
		}
		int[] m = layout.motion(layout.faceIndexAt(Mth.floor(player.getX())), toFace);
		double halfHeight = player.getBbHeight() / 2.0;
		Vec3 old = SeamInteraction.move(m, new Vec3(player.xo, player.yo + halfHeight, player.zo));
		Vec3 walked = SeamInteraction.move(m, new Vec3(player.xOld, player.yOld + halfHeight, player.zOld));
		Vector3f oldLook = turnLook(m, look(player.yRotO, player.xRotO));
		SeamCrossing.Turned turned = SeamCrossing.turned(layout, player, toFace);
		player.setPos(turned.position());
		// The chunk cache sorts chunks into the vanilla and the seam store by this center. The server moves it only when
		// it follows the crossing, and until then the chunks of the new region would be seam chunks.
		((ClientLevel) player.level()).getChunkSource().updateViewCenter(SectionPos.blockToSectionCoord(player.getX()), SectionPos.blockToSectionCoord(player.getZ()));
		player.setDeltaMovement(turned.velocity());
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
		player.xo = old.x;
		player.yo = old.y - halfHeight;
		player.zo = old.z;
		player.xOld = walked.x;
		player.yOld = walked.y - halfHeight;
		player.zOld = walked.z;
		player.yRotO = yaw(oldLook, turned.yRot());
		player.xRotO = pitch(oldLook);
		((TurnableAvatarState) player.avatarState()).cubeworld$turn(m, halfHeight);
		if (LoadTrace.active()) {
			RECENT_TICKS.forEach(LoadTrace::event);
			RECENT_TICKS.clear();
			traceTicksLeft = 30;
		}
	}

	private static Vector3f turnLook(int[] m, Vector3f v) {
		Vec3 turned = SeamInteraction.rotate(m, new Vec3(v.x, v.y, v.z));
		return new Vector3f((float) turned.x, (float) turned.y, (float) turned.z);
	}
}

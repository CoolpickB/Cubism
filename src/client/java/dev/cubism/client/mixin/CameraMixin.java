package dev.cubism.client.mixin;

import dev.cubism.client.BarrelRoll;
import dev.cubism.client.CubeFlight;
import dev.cubism.seam.SeamCrossing;
import dev.cubism.debug.LoadTrace;
import dev.cubism.client.SeamClient;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.AxisAngle4f;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An entity that crosses a seam moves to another face region and its view turns with the face motion. The camera
 * then starts from the old view, carried over by the motion, and turns to the new view over {@link #SMOOTH_TICKS}
 * ticks, about the center of the entity.
 *
 * <p>In flight the camera stands upright in the air frame of {@link CubeFlight}, which tilts over the edges. The
 * frame turns with the face motion at a crossing, so the seam turn is then near zero.
 *
 * <p>On the ground near an edge the camera also stands upright in the air frame, so the rounded edge looks level, up
 * to 45 degrees on a seam. The turn is a roll about the look, and the eye stands along the up of the air frame.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Unique private static final float SMOOTH_TICKS = 8.0F;
	/** A crossing while the Do a Barrel Roll mod rolls the player leaves a larger turn of the screen, which eases slower so the bank does not snap back. */
	@Unique private static final float ROLL_SMOOTH_TICKS = 20.0F;
	@Unique private float cubism$smoothTicks = SMOOTH_TICKS;
	/** A seam crossing moves the entity less than this far from where the face motion puts it, in blocks. */
	@Unique private static final double SEAM_TURN_MAX_JUMP = 8.0;

	@Shadow @Final private static Vector3fc FORWARDS;
	@Shadow @Final private static Vector3fc UP;
	@Shadow @Final private static Vector3fc LEFT;

	@Shadow private Entity entity;
	@Shadow private float eyeHeight;
	@Shadow private float eyeHeightOld;
	@Shadow @Final private Quaternionf rotation;
	@Shadow @Final private Vector3f forwards;
	@Shadow @Final private Vector3f up;
	@Shadow @Final private Vector3f left;
	@Shadow private int matrixPropertiesDirty;

	@Shadow protected abstract void setPosition(double x, double y, double z);

	/**
	 * The probe gives the sky color, the sun angle and the lightmap the local time at the point it samples. It samples
	 * the point the sky of the cube reads, or the shader pack mixes two local times near an edge.
	 */
	@ModifyArg(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/attribute/EnvironmentAttributeProbe;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;)V"), index = 1)
	private Vec3 cubism$probeAtTimeFocus(Vec3 position) {
		SeamLayout layout = SeamLayout.of(this.entity.level());
		return layout == null ? position : layout.ownerStorage(CubeFlight.timeFocus(this.entity, 1.0F));
	}

	@Unique private Entity cubism$lastEntity;
	@Unique private final Quaternionf cubism$correction = new Quaternionf();
	@Unique private float cubism$flipTime;
	@Unique private final Quaternionf cubism$frameRotation = new Quaternionf();
	/** The frame rotation without the turn into the air frame or the lean, which roll the view but not the eye. */
	@Unique private final Quaternionf cubism$eyeFrame = new Quaternionf();
	/** Turns the eye about the center of the entity during a seam turn. It eases to identity with the correction. */
	@Unique private final Quaternionf cubism$eyeCorrection = new Quaternionf();
	@Unique private int cubism$lastFace = -1;
	@Unique private Vec3 cubism$lastPosition = Vec3.ZERO;
	/** The frame of the view of {@link CubeFlight#viewFrame} in this frame and the last one. The eye stands along it. */
	@Unique private Quaternionf cubism$view = new Quaternionf();
	@Unique private Quaternionf cubism$lastView = new Quaternionf();
	@Unique private Quaternionf cubism$viewBefore = new Quaternionf();
	/** The camera rotation of the frame before the last one, and the times of the two frames, for the turn rate of the camera. */
	@Unique private final Quaternionf cubism$rotationBefore = new Quaternionf();
	@Unique private float cubism$timeBefore;
	@Unique private float cubism$lastTime;
	/**
	 * How far the shown eye stands from the vanilla eye, in blocks. It moves in at once when a wall comes near and out
	 * at {@link #EYE_EASE_OUT} blocks per tick. A broken block loses its collision at once, but its chunk mesh is built
	 * again a few frames later, so an eye that jumped into the gap would look through the old faces.
	 */
	@Unique private double cubism$eyeReach = Double.MAX_VALUE;
	@Unique private float cubism$eyeReachTime;
	@Unique private static final double EYE_EASE_OUT = 0.15;

	@Inject(method = "alignWithEntity", at = @At("HEAD"))
	private void cubism$updateFrameRotation(float partialTicks, CallbackInfo ci) {
		float now = this.entity.tickCount + partialTicks;
		this.cubism$viewBefore = this.cubism$lastView;
		this.cubism$lastView = this.cubism$view;
		this.cubism$view = CubeFlight.viewFrame(this.entity, partialTicks);
		Quaternionf airTurn = this.cubism$airTurn(partialTicks);
		if (this.entity != this.cubism$lastEntity) {
			this.cubism$lastEntity = this.entity;
			this.cubism$correction.identity();
			this.cubism$eyeCorrection.identity();
			this.cubism$lastFace = -1;
			this.cubism$lastView = this.cubism$view;
			this.cubism$viewBefore = this.cubism$view;
		}
		this.cubism$turnAcrossSeam(partialTicks, now, airTurn);
		this.cubism$rotationBefore.set(this.rotation);
		this.cubism$timeBefore = this.cubism$lastTime;
		this.cubism$lastTime = now;
		this.cubism$frameRotation.set(this.cubism$smoothedCorrection(now).mul(airTurn));
		this.cubism$eyeFrame.set(this.cubism$smoothedCorrection(now));
		SeamClient.lightFrame.set(this.cubism$smoothedCorrection(now).mul(this.cubism$view));
	}

	@Unique
	private static Quaternionf cubism$view(float yRot, float xRot) {
		return new Quaternionf().rotationYXZ((float) Math.PI - yRot * Mth.DEG_TO_RAD, -xRot * Mth.DEG_TO_RAD, 0.0F);
	}

	/**
	 * The turn from the view of vanilla to the view upright in the air frame. Both look the same way, so it is a
	 * roll about the look.
	 */
	@Unique
	private Quaternionf cubism$airTurn(float partialTicks) {
		Quaternionf air = this.cubism$view;
		// The kept screen up also holds in the frames before the view tilts within the tick. A look close to straight
		// down that the first tilt carried over it has a yaw half a turn from the last one, and the yaw of vanilla
		// spun the screen between the two.
		Quaternionf now = this.entity instanceof LocalPlayer ? CubeFlight.viewFrame(this.entity, 1.0F) : air;
		float glideRoll = this.entity instanceof LocalPlayer player ? CubeFlight.glideRoll(partialTicks) + CubeFlight.steadfastWobble(player, partialTicks) : 0.0F;
		float yRot = this.entity.getViewYRot(partialTicks);
		float xRot = this.entity.getViewXRot(partialTicks);
		if (Math.abs(air.x) < 1.0E-6F && Math.abs(air.y) < 1.0E-6F && Math.abs(air.z) < 1.0E-6F
			&& Math.abs(now.x) < 1.0E-6F && Math.abs(now.y) < 1.0E-6F && Math.abs(now.z) < 1.0E-6F) {
			return new Quaternionf().rotationAxis(glideRoll, CubeFlight.look(yRot, xRot));
		}
		if (this.entity instanceof LocalPlayer player) {
			// Vanilla blends the yaw and the pitch of the face frame between two ticks. Over an edge the look in that frame
			// turns fast and passes near straight up or down, where the yaw swings, and the blended look left the straight
			// turn within each tick: a wobble that grew with the speed. The look is blended in the view frame instead and
			// turned with the frame at this moment. The turn from the look of vanilla to it goes before the roll.
			Vector3f vanillaLook = CubeFlight.look(yRot, xRot);
			Vector3f look = this.cubism$lookInFrame(player, partialTicks);
			Quaternionf toLook = new Quaternionf().rotationTo(vanillaLook, look);
			// The roll that turns the up of vanilla to the kept up of the screen, about the look.
			Vector3f shown = toLook.transform(CubeFlight.vanillaScreenUp(yRot, xRot));
			Vector3f wanted = CubeFlight.screenUp(player, partialTicks);
			wanted.fma(-wanted.dot(look), look);
			if (wanted.lengthSquared() < 1.0E-8F) {
				return toLook;
			}
			float angle = (float) Math.atan2(new Vector3f(shown).cross(wanted).dot(look), shown.dot(wanted.normalize()));
			return new Quaternionf().rotationAxis(angle + glideRoll, look).mul(toLook);
		}
		float[] angles = CubeFlight.anglesIn(air, yRot, xRot);
		return new Quaternionf(air).mul(cubism$view(angles[0], angles[1])).mul(cubism$view(yRot, xRot).conjugate());
	}

	/** The look of the last two ticks, each in the view frame of its tick, blended, and turned by the view frame now. */
	@Unique
	private Vector3f cubism$lookInFrame(LocalPlayer player, float partialTicks) {
		Vector3f before = CubeFlight.viewFrame(player, 0.0F).conjugate().transform(CubeFlight.look(player.yRotO, player.xRotO));
		Vector3f after = CubeFlight.viewFrame(player, 1.0F).conjugate().transform(CubeFlight.look(player.getYRot(), player.getXRot()));
		Quaternionf turn = new Quaternionf().slerp(new Quaternionf().rotationTo(before, after), partialTicks);
		return this.cubism$view.transform(turn.transform(before)).normalize();
	}

	/**
	 * Starts a seam turn when the entity moved to another face region at the place where the face motion puts its
	 * last position. A teleport by command also changes the region, but lands elsewhere, and gets no turn.
	 */
	@Unique
	private void cubism$turnAcrossSeam(float partialTicks, float now, Quaternionf airTurn) {
		SeamLayout layout = SeamLayout.of(this.entity.level());
		Vec3 position = this.entity.getPosition(partialTicks);
		int face = layout == null ? -1 : SeamInteraction.regionFace(layout, position.x, position.z);
		if (face >= 0 && this.cubism$lastFace >= 0 && face != this.cubism$lastFace && this.entity instanceof LocalPlayer player
			&& player.level() instanceof ClientLevel level) {
			// The chunks of the old face stay in the vanilla store until the server moves the chunk center, a few ticks
			// later. With a large view distance the vanilla view area of the new face reaches into the old region, and
			// vanilla drew the old terrain there unturned, floating in the sky. The center moves before this frame.
			level.getChunkSource().updateViewCenter(SectionPos.blockToSectionCoord(player.getX()), SectionPos.blockToSectionCoord(player.getZ()));
		}
		if (face >= 0 && this.cubism$lastFace >= 0 && face != this.cubism$lastFace) {
			LoadTrace.event(String.format("client: face %d -> %d at %.1f %.1f %.1f", this.cubism$lastFace, face, position.x, position.y, position.z));
			int[] m = layout.motion(this.cubism$lastFace, face);
			Vec3 last = this.cubism$lastPosition;
			Vec3 carried = new Vec3(
				m[0] * last.x + m[1] * last.y + m[2] * last.z + m[9],
				m[3] * last.x + m[4] * last.y + m[5] * last.z + m[10],
				m[6] * last.x + m[7] * last.y + m[8] * last.z + m[11]
			);
			if (carried.distanceToSqr(position) < SEAM_TURN_MAX_JUMP * SEAM_TURN_MAX_JUMP) {
				Quaternionf motion = new Quaternionf().setFromNormalized(new Matrix3f(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]));
				// The camera rotation still holds the last frame, with any turn in progress. The correction starts from
				// where the camera turned on to by now at its last rate. From the last frame itself, the view stood still
				// for this frame and then caught up, a visible hitch while the view turns over an edge.
				Quaternionf shown = new Quaternionf(motion).mul(this.cubism$continued(this.rotation, this.cubism$rotationBefore, now));
				float yRot = this.entity.getViewYRot(partialTicks);
				float xRot = this.entity.getViewXRot(partialTicks);
				// The front view of vanilla looks back at the entity, and the last rotation holds that view.
				boolean mirrored = Minecraft.getInstance().options.getCameraType().isMirrored();
				// The camera holds the roll of the Do a Barrel Roll mod, which the crossing keeps. The correction eases only the face turn.
				float roll = BarrelRoll.rolling(this.entity) ? BarrelRoll.roll(this.entity) : 0.0F;
				Quaternionf view = new Quaternionf(airTurn).mul(mirrored ? CubeFlight.rolledView(yRot + 180.0F, -xRot, roll) : CubeFlight.rolledView(yRot, xRot, roll));
				this.cubism$correction.set(shown.mul(view.conjugate()));
				CubeFlight.traceLine(String.format(java.util.Locale.ROOT, "  seam correction=%.2f deg", Math.toDegrees(this.cubism$correction.angle())));
				// The eye stands along the air frame. The correction keeps it where the motion carries the last eye. It is
				// the shortest turn between the two ups: the two frames come from other storage axes and also differ by a
				// twist about the up, which moves no eye at the crossing, but half eased it swung the eye 15 degrees aside.
				// The last up also turns on at its last rate, as the camera rotation does, or the eye stood still for a frame.
				Quaternionf lastView = this.cubism$continued(this.cubism$lastView, this.cubism$viewBefore, now);
				Vector3f lastUp = motion.mul(this.cubism$smoothed(this.cubism$eyeCorrection, now)).mul(lastView).transform(new Vector3f(UP));
				Vector3f nowUp = this.cubism$view.transform(new Vector3f(UP));
				this.cubism$eyeCorrection.rotationTo(nowUp, lastUp);
				this.cubism$flipTime = now;
				this.cubism$smoothTicks = roll != 0.0F ? ROLL_SMOOTH_TICKS : SMOOTH_TICKS;
			}
		}
		this.cubism$lastFace = face;
		this.cubism$lastPosition = position;
	}

	/** A rotation of the last frame, turned on to the given time at its rate since the frame before. */
	@Unique
	private Quaternionf cubism$continued(Quaternionf lastRotation, Quaternionf rotationBefore, float now) {
		Quaternionf last = new Quaternionf(lastRotation);
		float span = this.cubism$lastTime - this.cubism$timeBefore;
		float ahead = now - this.cubism$lastTime;
		if (span <= 1.0E-4F || span > 1.0F || ahead <= 0.0F) {
			return last;
		}
		Quaternionf delta = new Quaternionf(lastRotation).mul(new Quaternionf(rotationBefore).conjugate()).normalize();
		// With no turn between the frames, rounding can put |w| just over 1, and JOML then gives a NaN axis. A NaN
		// correction never eases out, and the camera drew nothing until the next crossing.
		if (Math.abs(delta.w) >= 1.0F - 1.0E-6F) {
			return last;
		}
		AxisAngle4f turn = new AxisAngle4f(delta);
		float angle = turn.angle > (float) Math.PI ? turn.angle - 2.0F * (float) Math.PI : turn.angle;
		return new Quaternionf().rotationAxis(angle * Math.min(ahead / span, 3.0F), turn.x, turn.y, turn.z).mul(last);
	}

	@Unique
	private Quaternionf cubism$smoothedCorrection(float now) {
		return this.cubism$smoothed(this.cubism$correction, now);
	}

	@Unique
	private Quaternionf cubism$smoothed(Quaternionf correction, float now) {
		float t = Mth.clamp((now - this.cubism$flipTime) / this.cubism$smoothTicks, 0.0F, 1.0F);
		float eased = t * t * (3.0F - 2.0F * t);
		return new Quaternionf(correction).slerp(new Quaternionf(), eased);
	}

	@Unique
	private boolean cubism$isTurned() {
		Quaternionf q = this.cubism$frameRotation;
		return Math.abs(q.x) > 1.0E-6F || Math.abs(q.y) > 1.0E-6F || Math.abs(q.z) > 1.0E-6F;
	}

	@Inject(method = "setRotation", at = @At("TAIL"))
	private void cubism$applyFrameRotation(float yRot, float xRot, CallbackInfo ci) {
		if (this.cubism$isTurned()) {
			this.rotation.premul(this.cubism$frameRotation);
			FORWARDS.rotate(this.rotation, this.forwards);
			UP.rotate(this.rotation, this.up);
			LEFT.rotate(this.rotation, this.left);
			this.matrixPropertiesDirty |= 3;
		}
	}

	@Inject(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V", shift = At.Shift.AFTER))
	private void cubism$placeEyeInFrame(float partialTicks, CallbackInfo ci) {
		Quaternionf eyeTurn = this.cubism$smoothed(this.cubism$eyeCorrection, this.entity.tickCount + partialTicks).mul(this.cubism$view);
		if (Math.abs(eyeTurn.x) > 1.0E-6F || Math.abs(eyeTurn.y) > 1.0E-6F || Math.abs(eyeTurn.z) > 1.0E-6F) {
			float halfHeight = this.entity.getBbHeight() / 2.0F;
			float eyeHeight = Mth.lerp(partialTicks, this.eyeHeightOld, this.eyeHeight);
			Vector3f eye = eyeTurn.transform(new Vector3f(0.0F, eyeHeight - halfHeight, 0.0F));
			Vec3 position = this.entity.getPosition(partialTicks);
			Vec3 vanillaEye = position.add(0.0, eyeHeight, 0.0);
			Vec3 wantedEye = position.add(eye.x, halfHeight + eye.y, eye.z);
			float now = this.entity.tickCount + partialTicks;
			Vec3 clear = wantedEye;
			double reach = clear.distanceTo(vanillaEye);
			if (this.entity.isSpectator()) {
				this.cubism$eyeReach = Double.MAX_VALUE;
			} else {
				clear = SeamCrossing.clearEye(this.entity, vanillaEye, wantedEye);
				reach = clear.distanceTo(vanillaEye);
				double eased = this.cubism$eyeReach + EYE_EASE_OUT * Math.max(0.0F, now - this.cubism$eyeReachTime);
				this.cubism$eyeReachTime = now;
				if (eased < reach) {
					clear = vanillaEye.add(clear.subtract(vanillaEye).scale(eased / reach));
					reach = eased;
				}
				this.cubism$eyeReach = reach;
			}
			this.setPosition(clear.x, clear.y, clear.z);
			Vec3 wanted = position.add(eye.x, halfHeight + eye.y, eye.z);
			CubeFlight.traceLine(String.format(java.util.Locale.ROOT,
				"  frame=%.3f wanted=%.3f %.3f %.3f shown=%.3f %.3f %.3f reach=%.3f of %.3f fwd=%.3f %.3f %.3f up=%.3f %.3f %.3f",
				now, wanted.x, wanted.y, wanted.z, clear.x, clear.y, clear.z, reach, wanted.distanceTo(vanillaEye),
				this.forwards.x, this.forwards.y, this.forwards.z, this.up.x, this.up.y, this.up.z));
			return;
		}
		Quaternionf eyeFrame = this.cubism$eyeFrame;
		if (Math.abs(eyeFrame.x) < 1.0E-6F && Math.abs(eyeFrame.y) < 1.0E-6F && Math.abs(eyeFrame.z) < 1.0E-6F) {
			return;
		}
		Vector3f eye = new Vector3f(0.0F, Mth.lerp(partialTicks, this.eyeHeightOld, this.eyeHeight), 0.0F);
		eyeFrame.transform(eye);
		this.setPosition(
			Mth.lerp(partialTicks, this.entity.xo, this.entity.getX()) + eye.x,
			Mth.lerp(partialTicks, this.entity.yo, this.entity.getY()) + eye.y,
			Mth.lerp(partialTicks, this.entity.zo, this.entity.getZ()) + eye.z
		);
	}
}

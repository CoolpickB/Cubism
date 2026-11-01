package dev.cubeworld.client.mixin;

import dev.cubeworld.client.CubeFlight;
import dev.cubeworld.debug.LoadTrace;
import dev.cubeworld.client.SeamClient;
import dev.cubeworld.gravity.GravityEntityAccess;
import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Turns the camera into the frame of the entity. When the frame changes, the camera keeps the old view and
 * turns to the new frame over {@link #SMOOTH_TICKS} ticks, about the feet of the entity.
 *
 * <p>In a seam world the frame never changes, but an entity that crosses a seam moves to another face region and
 * its view turns with the face motion. The camera then starts from the old view, carried over by the motion, and
 * turns to the new view in the same way, about the center of the entity.
 *
 * <p>In flight the camera stands upright in the air frame of {@link CubeFlight}, which tilts over the edges. The
 * frame turns with the face motion at a crossing, so the seam turn is then near zero.
 *
 * <p>On the ground near an edge the camera also stands upright in the air frame, so the rounded edge looks level, up
 * to 45 degrees on a seam. The turn is a roll about the look, and the eye stays where vanilla puts it, so the crosshair
 * stays where vanilla aims.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Unique private static final float SMOOTH_TICKS = 8.0F;
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

	@Unique private Entity cubeworld$lastEntity;
	@Unique private Direction cubeworld$lastFrame = Direction.DOWN;
	@Unique private final Quaternionf cubeworld$correction = new Quaternionf();
	@Unique private float cubeworld$flipTime;
	@Unique private final Quaternionf cubeworld$frameRotation = new Quaternionf();
	/** The frame rotation without the turn into the air frame or the lean, which roll the view but not the eye. */
	@Unique private final Quaternionf cubeworld$eyeFrame = new Quaternionf();
	/** Turns the eye about the center of the entity during a seam turn. It eases to identity with the correction. */
	@Unique private final Quaternionf cubeworld$eyeCorrection = new Quaternionf();
	@Unique private int cubeworld$lastFace = -1;
	@Unique private Vec3 cubeworld$lastPosition = Vec3.ZERO;
	/** The air frame of {@link CubeFlight} in this frame and the last one. */
	@Unique private Quaternionf cubeworld$air = new Quaternionf();
	@Unique private Quaternionf cubeworld$lastAir = new Quaternionf();
	/** The frame of the view of {@link CubeFlight#viewFrame}, which also turns the view on the ground near an edge. */
	@Unique private Quaternionf cubeworld$view = new Quaternionf();

	@Inject(method = "alignWithEntity", at = @At("HEAD"))
	private void cubeworld$updateFrameRotation(float partialTicks, CallbackInfo ci) {
		Direction frame = this.entity instanceof GravityEntityAccess access ? access.cubeworld$getFrame() : Direction.DOWN;
		float now = this.entity.tickCount + partialTicks;
		this.cubeworld$lastAir = this.cubeworld$air;
		this.cubeworld$air = CubeFlight.frame(this.entity, partialTicks);
		this.cubeworld$view = CubeFlight.viewFrame(this.entity, partialTicks);
		Quaternionf airTurn = this.cubeworld$airTurn(partialTicks);
		if (this.entity != this.cubeworld$lastEntity) {
			this.cubeworld$lastEntity = this.entity;
			this.cubeworld$correction.identity();
			this.cubeworld$eyeCorrection.identity();
			this.cubeworld$lastFace = -1;
			this.cubeworld$lastAir = this.cubeworld$air;
		} else if (frame != this.cubeworld$lastFrame) {
			// Start from the rotation on screen, also when the last turn is not complete.
			Quaternionf shown = this.cubeworld$smoothedCorrection(now).mul(CubePlanetMath.getCameraRotation(this.cubeworld$lastFrame));
			this.cubeworld$correction.set(shown.mul(CubePlanetMath.getCameraRotation(frame).conjugate()));
			this.cubeworld$flipTime = now;
		}
		this.cubeworld$lastFrame = frame;
		this.cubeworld$turnAcrossSeam(partialTicks, now, airTurn);
		this.cubeworld$frameRotation.set(this.cubeworld$smoothedCorrection(now).mul(airTurn).mul(CubePlanetMath.getCameraRotation(frame)));
		this.cubeworld$eyeFrame.set(this.cubeworld$smoothedCorrection(now).mul(CubePlanetMath.getCameraRotation(frame)));
		SeamClient.lightFrame.set(this.cubeworld$smoothedCorrection(now).mul(this.cubeworld$view));
	}

	@Unique
	private static Quaternionf cubeworld$view(float yRot, float xRot) {
		return new Quaternionf().rotationYXZ((float) Math.PI - yRot * Mth.DEG_TO_RAD, -xRot * Mth.DEG_TO_RAD, 0.0F);
	}

	/**
	 * The turn from the view of vanilla to the view upright in the air frame. Both look the same way, so it is a
	 * roll about the look.
	 */
	@Unique
	private Quaternionf cubeworld$airTurn(float partialTicks) {
		Quaternionf air = this.cubeworld$view;
		if (Math.abs(air.x) < 1.0E-6F && Math.abs(air.y) < 1.0E-6F && Math.abs(air.z) < 1.0E-6F) {
			return new Quaternionf();
		}
		float yRot = this.entity.getViewYRot(partialTicks);
		float xRot = this.entity.getViewXRot(partialTicks);
		float[] angles = CubeFlight.anglesIn(air, yRot, xRot);
		return new Quaternionf(air).mul(cubeworld$view(angles[0], angles[1])).mul(cubeworld$view(yRot, xRot).conjugate());
	}

	/**
	 * Starts a seam turn when the entity moved to another face region at the place where the face motion puts its
	 * last position. A teleport by command also changes the region, but lands elsewhere, and gets no turn.
	 */
	@Unique
	private void cubeworld$turnAcrossSeam(float partialTicks, float now, Quaternionf airTurn) {
		SeamLayout layout = SeamLayout.of(this.entity.level());
		Vec3 position = this.entity.getPosition(partialTicks);
		int face = layout != null && layout.inRegion(Mth.floor(position.x), Mth.floor(position.z)) ? layout.faceIndexAt(Mth.floor(position.x)) : -1;
		if (face >= 0 && this.cubeworld$lastFace >= 0 && face != this.cubeworld$lastFace && this.entity instanceof LocalPlayer player
			&& player.level() instanceof ClientLevel level) {
			// The chunks of the old face stay in the vanilla store until the server moves the chunk center, a few ticks
			// later. With a large view distance the vanilla view area of the new face reaches into the old region, and
			// vanilla drew the old terrain there unturned, floating in the sky. The center moves before this frame.
			level.getChunkSource().updateViewCenter(SectionPos.blockToSectionCoord(player.getX()), SectionPos.blockToSectionCoord(player.getZ()));
		}
		if (face >= 0 && this.cubeworld$lastFace >= 0 && face != this.cubeworld$lastFace) {
			LoadTrace.event(String.format("client: face %d -> %d at %.1f %.1f %.1f", this.cubeworld$lastFace, face, position.x, position.y, position.z));
			int[] m = layout.motion(this.cubeworld$lastFace, face);
			Vec3 last = this.cubeworld$lastPosition;
			Vec3 carried = new Vec3(
				m[0] * last.x + m[1] * last.y + m[2] * last.z + m[9],
				m[3] * last.x + m[4] * last.y + m[5] * last.z + m[10],
				m[6] * last.x + m[7] * last.y + m[8] * last.z + m[11]
			);
			if (carried.distanceToSqr(position) < SEAM_TURN_MAX_JUMP * SEAM_TURN_MAX_JUMP) {
				Quaternionf motion = new Quaternionf().setFromNormalized(new Matrix3f(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]));
				// The camera rotation still holds the last frame, with any turn in progress.
				Quaternionf shown = new Quaternionf(motion).mul(this.rotation);
				float yRot = this.entity.getViewYRot(partialTicks);
				float xRot = this.entity.getViewXRot(partialTicks);
				// The front view of vanilla looks back at the entity, and the last rotation holds that view.
				boolean mirrored = Minecraft.getInstance().options.getCameraType().isMirrored();
				Quaternionf view = new Quaternionf(airTurn).mul(mirrored ? cubeworld$view(yRot + 180.0F, -xRot) : cubeworld$view(yRot, xRot));
				this.cubeworld$correction.set(shown.mul(view.conjugate()));
				// The eye stands along the air frame. The correction keeps it where the motion carries the last eye.
				this.cubeworld$eyeCorrection.set(motion.mul(this.cubeworld$smoothed(this.cubeworld$eyeCorrection, now))
					.mul(this.cubeworld$lastAir).mul(new Quaternionf(this.cubeworld$air).conjugate()));
				this.cubeworld$flipTime = now;
			}
		}
		this.cubeworld$lastFace = face;
		this.cubeworld$lastPosition = position;
	}

	@Unique
	private Quaternionf cubeworld$smoothedCorrection(float now) {
		return this.cubeworld$smoothed(this.cubeworld$correction, now);
	}

	@Unique
	private Quaternionf cubeworld$smoothed(Quaternionf correction, float now) {
		float t = Mth.clamp((now - this.cubeworld$flipTime) / SMOOTH_TICKS, 0.0F, 1.0F);
		float eased = t * t * (3.0F - 2.0F * t);
		return new Quaternionf(correction).slerp(new Quaternionf(), eased);
	}

	@Unique
	private boolean cubeworld$isTurned() {
		Quaternionf q = this.cubeworld$frameRotation;
		return Math.abs(q.x) > 1.0E-6F || Math.abs(q.y) > 1.0E-6F || Math.abs(q.z) > 1.0E-6F;
	}

	@Inject(method = "setRotation", at = @At("TAIL"))
	private void cubeworld$applyFrameRotation(float yRot, float xRot, CallbackInfo ci) {
		if (this.cubeworld$isTurned()) {
			this.rotation.premul(this.cubeworld$frameRotation);
			FORWARDS.rotate(this.rotation, this.forwards);
			UP.rotate(this.rotation, this.up);
			LEFT.rotate(this.rotation, this.left);
			this.matrixPropertiesDirty |= 3;
		}
	}

	@Inject(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V", shift = At.Shift.AFTER))
	private void cubeworld$placeEyeInFrame(float partialTicks, CallbackInfo ci) {
		Quaternionf eyeTurn = this.cubeworld$smoothed(this.cubeworld$eyeCorrection, this.entity.tickCount + partialTicks).mul(this.cubeworld$air);
		if (Math.abs(eyeTurn.x) > 1.0E-6F || Math.abs(eyeTurn.y) > 1.0E-6F || Math.abs(eyeTurn.z) > 1.0E-6F) {
			float halfHeight = this.entity.getBbHeight() / 2.0F;
			Vector3f eye = eyeTurn.transform(new Vector3f(0.0F, Mth.lerp(partialTicks, this.eyeHeightOld, this.eyeHeight) - halfHeight, 0.0F));
			Vec3 position = this.entity.getPosition(partialTicks);
			this.setPosition(position.x + eye.x, position.y + halfHeight + eye.y, position.z + eye.z);
			return;
		}
		Quaternionf eyeFrame = this.cubeworld$eyeFrame;
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

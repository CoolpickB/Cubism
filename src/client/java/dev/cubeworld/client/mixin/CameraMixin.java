package dev.cubeworld.client.mixin;

import dev.cubeworld.gravity.GravityEntityAccess;
import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.client.Camera;
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
	/** Turns the eye about the center of the entity during a seam turn. It eases to identity with the correction. */
	@Unique private final Quaternionf cubeworld$eyeCorrection = new Quaternionf();
	@Unique private int cubeworld$lastFace = -1;
	@Unique private Vec3 cubeworld$lastPosition = Vec3.ZERO;

	@Inject(method = "alignWithEntity", at = @At("HEAD"))
	private void cubeworld$updateFrameRotation(float partialTicks, CallbackInfo ci) {
		Direction frame = this.entity instanceof GravityEntityAccess access ? access.cubeworld$getFrame() : Direction.DOWN;
		float now = this.entity.tickCount + partialTicks;
		if (this.entity != this.cubeworld$lastEntity) {
			this.cubeworld$lastEntity = this.entity;
			this.cubeworld$correction.identity();
			this.cubeworld$eyeCorrection.identity();
			this.cubeworld$lastFace = -1;
		} else if (frame != this.cubeworld$lastFrame) {
			// Start from the rotation on screen, also when the last turn is not complete.
			Quaternionf shown = this.cubeworld$smoothedCorrection(now).mul(CubePlanetMath.getCameraRotation(this.cubeworld$lastFrame));
			this.cubeworld$correction.set(shown.mul(CubePlanetMath.getCameraRotation(frame).conjugate()));
			this.cubeworld$flipTime = now;
		}
		this.cubeworld$lastFrame = frame;
		this.cubeworld$turnAcrossSeam(partialTicks, now);
		this.cubeworld$frameRotation.set(this.cubeworld$smoothedCorrection(now).mul(CubePlanetMath.getCameraRotation(frame)));
	}

	/**
	 * Starts a seam turn when the entity moved to another face region at the place where the face motion puts its
	 * last position. A teleport by command also changes the region, but lands elsewhere, and gets no turn.
	 */
	@Unique
	private void cubeworld$turnAcrossSeam(float partialTicks, float now) {
		SeamLayout layout = SeamLayout.of(this.entity.level());
		Vec3 position = this.entity.getPosition(partialTicks);
		int face = layout != null && layout.inRegion(Mth.floor(position.x), Mth.floor(position.z)) ? layout.faceIndexAt(Mth.floor(position.x)) : -1;
		if (face >= 0 && this.cubeworld$lastFace >= 0 && face != this.cubeworld$lastFace) {
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
				Quaternionf view = new Quaternionf().rotationYXZ(
					(float) Math.PI - this.entity.getViewYRot(partialTicks) * Mth.DEG_TO_RAD, -this.entity.getViewXRot(partialTicks) * Mth.DEG_TO_RAD, 0.0F
				);
				this.cubeworld$correction.set(shown.mul(view.conjugate()));
				this.cubeworld$eyeCorrection.set(motion.mul(this.cubeworld$smoothed(this.cubeworld$eyeCorrection, now)));
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
		if (!this.cubeworld$isTurned()) {
			return;
		}
		Quaternionf eyeTurn = this.cubeworld$smoothed(this.cubeworld$eyeCorrection, this.entity.tickCount + partialTicks);
		if (Math.abs(eyeTurn.x) > 1.0E-6F || Math.abs(eyeTurn.y) > 1.0E-6F || Math.abs(eyeTurn.z) > 1.0E-6F) {
			float halfHeight = this.entity.getBbHeight() / 2.0F;
			Vector3f eye = eyeTurn.transform(new Vector3f(0.0F, Mth.lerp(partialTicks, this.eyeHeightOld, this.eyeHeight) - halfHeight, 0.0F));
			Vec3 position = this.entity.getPosition(partialTicks);
			this.setPosition(position.x + eye.x, position.y + halfHeight + eye.y, position.z + eye.z);
			return;
		}
		Vector3f eye = new Vector3f(0.0F, Mth.lerp(partialTicks, this.eyeHeightOld, this.eyeHeight), 0.0F);
		this.cubeworld$frameRotation.transform(eye);
		this.setPosition(
			Mth.lerp(partialTicks, this.entity.xo, this.entity.getX()) + eye.x,
			Mth.lerp(partialTicks, this.entity.yo, this.entity.getY()) + eye.y,
			Mth.lerp(partialTicks, this.entity.zo, this.entity.getZ()) + eye.z
		);
	}
}

package dev.cubeworld.client.mixin;

import dev.cubeworld.gravity.GravityEntityAccess;
import dev.cubeworld.math.CubePlanetMath;
import net.minecraft.client.Camera;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
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
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Unique private static final float SMOOTH_TICKS = 8.0F;

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

	@Inject(method = "alignWithEntity", at = @At("HEAD"))
	private void cubeworld$updateFrameRotation(float partialTicks, CallbackInfo ci) {
		Direction frame = this.entity instanceof GravityEntityAccess access ? access.cubeworld$getFrame() : Direction.DOWN;
		float now = this.entity.tickCount + partialTicks;
		if (this.entity != this.cubeworld$lastEntity) {
			this.cubeworld$lastEntity = this.entity;
			this.cubeworld$correction.identity();
		} else if (frame != this.cubeworld$lastFrame) {
			// Start from the rotation on screen, also when the last turn is not complete.
			Quaternionf shown = this.cubeworld$smoothedCorrection(now).mul(CubePlanetMath.getCameraRotation(this.cubeworld$lastFrame));
			this.cubeworld$correction.set(shown.mul(CubePlanetMath.getCameraRotation(frame).conjugate()));
			this.cubeworld$flipTime = now;
		}
		this.cubeworld$lastFrame = frame;
		this.cubeworld$frameRotation.set(this.cubeworld$smoothedCorrection(now).mul(CubePlanetMath.getCameraRotation(frame)));
	}

	@Unique
	private Quaternionf cubeworld$smoothedCorrection(float now) {
		float t = Mth.clamp((now - this.cubeworld$flipTime) / SMOOTH_TICKS, 0.0F, 1.0F);
		float eased = t * t * (3.0F - 2.0F * t);
		return new Quaternionf(this.cubeworld$correction).slerp(new Quaternionf(), eased);
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
		Vector3f eye = new Vector3f(0.0F, Mth.lerp(partialTicks, this.eyeHeightOld, this.eyeHeight), 0.0F);
		this.cubeworld$frameRotation.transform(eye);
		this.setPosition(
			Mth.lerp(partialTicks, this.entity.xo, this.entity.getX()) + eye.x,
			Mth.lerp(partialTicks, this.entity.yo, this.entity.getY()) + eye.y,
			Mth.lerp(partialTicks, this.entity.zo, this.entity.getZ()) + eye.z
		);
	}
}

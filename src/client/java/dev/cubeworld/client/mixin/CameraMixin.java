package dev.cubeworld.client.mixin;

import dev.cubeworld.gravity.GravityEntityAccess;
import net.minecraft.client.Camera;
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

@Mixin(Camera.class)
public abstract class CameraMixin {
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

	@Unique
	private float cubeworld$partialTicks = 1.0f;

	@Inject(method = "alignWithEntity", at = @At("HEAD"))
	private void cubeworld$capturePartialTicks(float partialTicks, CallbackInfo ci) {
		this.cubeworld$partialTicks = partialTicks;
	}

	@Inject(method = "setRotation", at = @At("TAIL"))
	private void cubeworld$applyGravityCameraRotation(float yRot, float xRot, CallbackInfo ci) {
		if (this.entity instanceof GravityEntityAccess access) {
			Quaternionf gravRot = access.cubeworld$getRenderCameraRotation(this.cubeworld$partialTicks);
			if (Math.abs(gravRot.x) > 1.0E-5f || Math.abs(gravRot.y) > 1.0E-5f || Math.abs(gravRot.z) > 1.0E-5f) {
				Quaternionf combined = new Quaternionf(gravRot).mul(this.rotation);
				this.rotation.set(combined);
				FORWARDS.rotate(this.rotation, this.forwards);
				UP.rotate(this.rotation, this.up);
				LEFT.rotate(this.rotation, this.left);
				this.matrixPropertiesDirty |= 3;
			}
		}
	}

	@Inject(
		method = "alignWithEntity",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/Camera;setPosition(DDD)V",
			shift = At.Shift.AFTER
		)
	)
	private void cubeworld$setOrientedEyePosition(float partialTicks, CallbackInfo ci) {
		if (this.entity instanceof GravityEntityAccess access) {
			Quaternionf q = access.cubeworld$getRenderCameraRotation(partialTicks);
			net.minecraft.core.Direction dir = access.cubeworld$getGravityDirection();
			if (dir != net.minecraft.core.Direction.DOWN
				|| Math.abs(q.x) > 1.0E-5f || Math.abs(q.y) > 1.0E-5f || Math.abs(q.z) > 1.0E-5f) {
				double halfH = this.entity.getBbHeight() * 0.5;
				double cx = Mth.lerp(partialTicks, this.entity.xo, this.entity.getX()) - dir.getStepX() * halfH;
				double cy = Mth.lerp(partialTicks, this.entity.yo, this.entity.getY()) - dir.getStepY() * halfH;
				double cz = Mth.lerp(partialTicks, this.entity.zo, this.entity.getZ()) - dir.getStepZ() * halfH;
				float eye = Mth.lerp(partialTicks, this.eyeHeightOld, this.eyeHeight);
				Vector3f eyeOffset = new Vector3f(0.0f, (float) (eye - halfH), 0.0f);
				q.transform(eyeOffset);
				this.setPosition(cx + eyeOffset.x, cy + eyeOffset.y, cz + eyeOffset.z);
			}
		}
	}
}

package dev.cubeworld.client.sodium;

import dev.cubeworld.client.sodiumseam.FrameCrossing;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Puts the camera position of the last frame, which Iris keeps, in the frame of the face that the camera crosses into. */
@Mixin(targets = "net.irisshaders.iris.uniforms.CameraUniforms$CameraPositionTracker", remap = false)
public abstract class IrisCrossingPositionMixin {
	@Inject(method = "update", at = @At("HEAD"))
	private void cubeworld$beginFrame(CallbackInfo ci) {
		FrameCrossing.begin();
	}

	@Inject(method = "getPreviousCameraPosition", at = @At("RETURN"), cancellable = true)
	private void cubeworld$carryPosition(CallbackInfoReturnable<Vector3d> cir) {
		cir.setReturnValue(FrameCrossing.position(cir.getReturnValue()));
	}

	@Inject(method = "getPreviousCameraPositionUnshifted", at = @At("RETURN"), cancellable = true)
	private void cubeworld$carryUnshifted(CallbackInfoReturnable<Vector3d> cir) {
		cir.setReturnValue(FrameCrossing.position(cir.getReturnValue()));
	}
}

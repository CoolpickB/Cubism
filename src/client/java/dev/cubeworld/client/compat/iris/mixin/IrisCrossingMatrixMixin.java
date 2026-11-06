package dev.cubeworld.client.compat.iris.mixin;

import dev.cubeworld.client.compat.iris.FrameCrossing;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Puts the model view matrix of the last frame, which Iris keeps, in the frame of the face that the camera crosses into. */
@Mixin(targets = "net.irisshaders.iris.uniforms.MatrixUniforms$Previous", remap = false)
public abstract class IrisCrossingMatrixMixin {
	@Inject(method = "get()Lorg/joml/Matrix4fc;", at = @At("RETURN"), cancellable = true)
	private void cubeworld$carryMatrix(CallbackInfoReturnable<Matrix4fc> cir) {
		cir.setReturnValue(FrameCrossing.modelView(cir.getReturnValue()));
	}
}

package dev.cubeworld.client.compat.voxy.mixin;

import dev.cubeworld.client.compat.iris.mixin.IrisCrossingMatrixMixin;

import dev.cubeworld.client.compat.iris.FrameCrossing;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Voxy keeps the matrices of the last frame for the shader pack itself, apart from the ones of Iris that
 * {@link IrisCrossingMatrixMixin} carries over a crossing. The pack reprojects the reflections on the water of the levels of
 * detail with them, and they jumped for a frame when the camera crossed to the next face.
 */
@Mixin(targets = "me.cortex.voxy.client.iris.VoxyUniforms$PreviousMat", remap = false)
public abstract class VoxyPreviousMatrixMixin {
	@Inject(method = "get()Lorg/joml/Matrix4fc;", at = @At("RETURN"), cancellable = true)
	private void cubeworld$carryMatrix(CallbackInfoReturnable<Matrix4fc> cir) {
		cir.setReturnValue(FrameCrossing.modelView(cir.getReturnValue()));
	}
}

package dev.cubeworld.client.compat.iris.mixin;

import dev.cubeworld.client.compat.iris.IrisFaceUniforms;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives a folded face the frame of its own face in the program of the pack, see {@link IrisFaceUniforms}. */
@Mixin(value = ExtendedShader.class, remap = false)
public abstract class IrisExtendedShaderMixin {
	@Inject(method = "iris$setupState", at = @At("RETURN"))
	private void cubeworld$faceFrame(CallbackInfo ci) {
		IrisFaceUniforms.onSetup(((ExtendedShader) (Object) this).getProgramId());
	}
}

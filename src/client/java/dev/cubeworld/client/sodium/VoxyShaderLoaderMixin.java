package dev.cubeworld.client.sodium;

import dev.cubeworld.client.sodiumseam.VoxyFaces;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "me.cortex.voxy.client.core.gl.shader.ShaderLoader$ShaderLoadingParser", remap = false)
public abstract class VoxyShaderLoaderMixin {
	@Inject(method = "loadShaderAsset", at = @At("RETURN"), cancellable = true)
	private static void cubeworld$patch(Identifier id, CallbackInfoReturnable<String> cir) {
		cir.setReturnValue(VoxyFaces.patchShader(id.getPath(), cir.getReturnValue()));
	}
}

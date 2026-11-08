package dev.cubism.client.compat.voxy.mixin;

import dev.cubism.client.compat.voxy.VoxyFaces;
import me.cortex.voxy.client.core.rendering.util.LightMapHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LightMapHelper.class, remap = false)
public abstract class VoxyLightMapHelperMixin {
	@Shadow
	@Final
	private static int LM_SAMPLER;

	@Inject(method = "bind", at = @At("TAIL"))
	private static void cubism$blendLight(int unit, CallbackInfo ci) {
		VoxyFaces.bindLightBlend(LM_SAMPLER);
	}
}

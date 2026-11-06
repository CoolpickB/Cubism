package dev.cubeworld.client.compat.voxy.mixin;

import dev.cubeworld.client.compat.voxy.VoxyFaces;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.ViewportSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A viewport per face: Voxy keeps its occlusion and the sections seen last frame per viewport, and each face has its own camera. */
@Mixin(value = ViewportSelector.class, remap = false)
public abstract class VoxyViewportSelectorMixin<T extends Viewport<?>> {
	@Shadow
	private T getOrCreate(Object holder) {
		throw new AssertionError();
	}

	@Inject(method = "getViewport", at = @At("HEAD"), cancellable = true)
	private void cubeworld$faceViewport(CallbackInfoReturnable<T> cir) {
		if (VoxyFaces.active()) {
			cir.setReturnValue(this.getOrCreate(VoxyFaces.viewportKey()));
		}
	}
}

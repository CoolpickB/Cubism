package dev.cubism.client.mixin.sky;

import dev.cubism.client.render.FaceLightmaps;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Lightmap.class)
public abstract class LightmapMixin {
	@Inject(method = "render", at = @At("TAIL"))
	private void cubism$faces(LightmapRenderState state, CallbackInfo ci) {
		FaceLightmaps.render();
	}
}

package dev.cubism.client.mixin.sky;

import dev.cubism.client.render.FaceLightmaps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** After the lightmap state of the camera, reads the lightmap state of each face of the cube from the probe of that face. */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapExtractorMixin {
	@Shadow private boolean needsUpdate;
	@Shadow @Final private Minecraft minecraft;

	@Shadow
	public abstract void extract(LightmapRenderState state, float partialTicks);

	@Inject(method = "extract", at = @At("RETURN"))
	private void cubism$faces(LightmapRenderState state, float partialTicks, CallbackInfo ci) {
		if (!state.needsUpdate || FaceLightmaps.extracting() || this.minecraft.level == null) {
			return;
		}
		FaceLightmaps.extract(this.minecraft.level, state, (face, probe) -> {
			this.needsUpdate = true;
			this.extract(face, partialTicks);
		});
	}
}

package dev.cubism.client.mixin;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.cubism.client.render.FaceLightmaps;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Lightmap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sodium and Voxy ask for the lightmap each time they draw a face, so each face can have its own. */
@Mixin(GameRenderer.class)
public abstract class GameRendererLightmapMixin {
	@Shadow @Final private Lightmap lightmap;

	@Inject(method = {"lightmap", "levelLightmap"}, at = @At("RETURN"), cancellable = true)
	private void cubism$faceLightmap(CallbackInfoReturnable<GpuTextureView> cir) {
		cir.setReturnValue(FaceLightmaps.view(cir.getReturnValue(), this.lightmap.getTextureView()));
	}
}

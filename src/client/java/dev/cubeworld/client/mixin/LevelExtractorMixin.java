package dev.cubeworld.client.mixin;

import dev.cubeworld.client.render.SeamRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block and light changes in seam sections reach this method too, but vanilla only knows its own view area. */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
	@Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
	private void cubeworld$markSeamDirty(int sectionX, int sectionY, int sectionZ, boolean playerChanged, CallbackInfo ci) {
		SeamRenderer.markDirty(sectionX, sectionY, sectionZ);
	}
}

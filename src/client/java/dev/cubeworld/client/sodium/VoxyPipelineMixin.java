package dev.cubeworld.client.sodium;

import dev.cubeworld.client.sodiumseam.VoxyFaces;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Each face marks where the terrain stands from the depth before any face of Voxy drew, see {@link VoxyFaces#terrainDepth}. */
@Mixin(value = AbstractRenderPipeline.class, remap = false)
public abstract class VoxyPipelineMixin {
	@ModifyArg(
		method = "runPipeline",
		at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/AbstractRenderPipeline;setup(Lme/cortex/voxy/client/core/rendering/Viewport;III)I"),
		index = 1
	)
	private int cubeworld$terrainDepth(int sourceDepthTexture) {
		return VoxyFaces.terrainDepth(sourceDepthTexture);
	}
}

package dev.cubeworld.client.mixin;

import dev.cubeworld.client.render.SeamRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A section of the vanilla view area that moves to another position gives its mesh to the seam renderer, and back. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionMixin {
	@Inject(method = "setSectionNode", at = @At("HEAD"))
	private void cubeworld$handMeshToSeam(long sectionNode, CallbackInfo ci) {
		SeamRenderer.onVanillaSectionMoving((SectionRenderDispatcher.RenderSection) (Object) this, sectionNode);
	}

	@Inject(method = "setSectionNode", at = @At("TAIL"))
	private void cubeworld$takeMeshFromSeam(long sectionNode, CallbackInfo ci) {
		SeamRenderer.onVanillaSectionMoved((SectionRenderDispatcher.RenderSection) (Object) this);
	}
}

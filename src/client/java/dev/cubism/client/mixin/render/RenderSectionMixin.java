package dev.cubism.client.mixin.render;

import dev.cubism.client.debug.LoadTraceClient;
import dev.cubism.client.render.SeamRenderer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A section of the vanilla view area that moves to another position gives its mesh to the seam renderer, and back. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionMixin {
	@Shadow
	private volatile long sectionNode;

	@Shadow
	private void releaseSectionMesh(SectionMesh oldMesh) {
	}

	@Inject(method = "setSectionNode", at = @At("HEAD"))
	private void cubism$handMeshToSeam(long sectionNode, CallbackInfo ci) {
		LoadTraceClient.count("vanilla.sectionMoved");
		SeamRenderer.onVanillaSectionMoving((SectionRenderDispatcher.RenderSection) (Object) this, sectionNode);
	}

	@Inject(method = "setSectionNode", at = @At("TAIL"))
	private void cubism$takeMeshFromSeam(long sectionNode, CallbackInfo ci) {
		SeamRenderer.onVanillaSectionMoved((SectionRenderDispatcher.RenderSection) (Object) this);
	}

	/**
	 * Vanilla installs a mesh when its buffers finish the upload, also when the section moved to another position
	 * after the compile. The mesh then showed at the new position until the next compile. A moved seam mesh is drawn
	 * with the motion of its new face, so terrain showed turned in the sky for a moment.
	 */
	@Inject(method = "checkSectionMesh", at = @At("HEAD"), cancellable = true)
	private void cubism$dropMovedMesh(CompiledSectionMesh mesh, CallbackInfo ci) {
		Long node = SeamRenderer.COMPILED_FOR.get(mesh);
		if (node != null && node != this.sectionNode) {
			this.releaseSectionMesh(mesh);
			ci.cancel();
		}
	}
}

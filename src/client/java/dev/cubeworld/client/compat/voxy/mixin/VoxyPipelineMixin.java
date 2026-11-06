package dev.cubeworld.client.compat.voxy.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.client.compat.voxy.VoxyFaces;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.IrisVoxyRenderPipeline;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import org.lwjgl.opengl.GL11C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Each face marks where the terrain stands from the depth before any face of Voxy drew, see {@link VoxyFaces#terrainDepth}.
 * Only the first face updates the geometry, and the later faces draw only the lists they build after it, see
 * {@link VoxyFaces#laterFace}.
 */
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

	@WrapOperation(
		method = "runPipeline",
		at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/rendering/section/backend/AbstractSectionRenderer;renderOpaque(Lme/cortex/voxy/client/core/rendering/Viewport;)V")
	)
	private void cubeworld$skipOldList(AbstractSectionRenderer renderer, Viewport viewport, Operation<Void> original) {
		if (!VoxyFaces.laterFace()) {
			original.call(renderer, viewport);
		}
	}

	@WrapOperation(
		method = "runPipeline",
		at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/rendering/section/backend/AbstractSectionRenderer;buildDrawCalls(Lme/cortex/voxy/client/core/rendering/Viewport;)V")
	)
	private void cubeworld$drawNewList(AbstractSectionRenderer renderer, Viewport viewport, Operation<Void> original) {
		if (VoxyFaces.waterPass()) {
			return;
		}
		original.call(renderer, viewport);
		if (VoxyFaces.laterFace()) {
			renderer.renderOpaque(viewport);
		}
	}

	/**
	 * The second pass draws only the water of each face, see {@link VoxyFaces#holdWater}, over the depth and stencil that
	 * the first pass left. The stencil marks where Voxy draws, as the setup leaves it.
	 */
	@WrapOperation(
		method = "runPipeline",
		at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/AbstractRenderPipeline;setup(Lme/cortex/voxy/client/core/rendering/Viewport;III)I")
	)
	private int cubeworld$keepDepthForWater(AbstractRenderPipeline pipeline, Viewport viewport, int depth, int width, int height, Operation<Integer> original) {
		if (!VoxyFaces.waterPass()) {
			return original.call(pipeline, viewport, depth, width, height);
		}
		GL11C.glEnable(GL11C.GL_STENCIL_TEST);
		GL11C.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_KEEP);
		GL11C.glStencilFunc(GL11C.GL_EQUAL, 1, 0xFF);
		GL11C.glStencilMask(0xFF);
		return 0;
	}

	@WrapOperation(
		method = "runPipeline",
		at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/AbstractRenderPipeline;innerPrimaryWork(Lme/cortex/voxy/client/core/rendering/Viewport;I)V")
	)
	private void cubeworld$noWorkForWater(AbstractRenderPipeline pipeline, Viewport viewport, int depth, Operation<Void> original) {
		if (!VoxyFaces.waterPass()) {
			original.call(pipeline, viewport, depth);
		}
	}

	@WrapOperation(
		method = "runPipeline",
		at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/AbstractRenderPipeline;postOpaquePreTranslucent(Lme/cortex/voxy/client/core/rendering/Viewport;I)V")
	)
	private void cubeworld$copyDepthOnce(AbstractRenderPipeline pipeline, Viewport viewport, int depth, Operation<Void> original) {
		if (!VoxyFaces.waterPass() || VoxyFaces.firstWater()) {
			original.call(pipeline, viewport, depth);
		}
	}

	@WrapOperation(
		method = "runPipeline",
		at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/rendering/section/backend/AbstractSectionRenderer;renderTranslucent(Lme/cortex/voxy/client/core/rendering/Viewport;)V")
	)
	private void cubeworld$holdWater(AbstractSectionRenderer renderer, Viewport viewport, Operation<Void> original) {
		if (!VoxyFaces.holdWater(this)) {
			original.call(renderer, viewport);
		}
	}

	/** The finish copies the translucent depth into the depth of the frame, so it waits for the water. */
	@WrapOperation(
		method = "runPipeline",
		at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/AbstractRenderPipeline;finish(Lme/cortex/voxy/client/core/rendering/Viewport;IIII)V")
	)
	private void cubeworld$finishAfterWater(AbstractRenderPipeline pipeline, Viewport viewport, int depth, int framebuffer, int width, int height,
		Operation<Void> original) {
		if (!VoxyFaces.holdWater(this)) {
			original.call(pipeline, viewport, depth, framebuffer, width, height);
		}
	}

	/** The new list already holds what the temporal pass adds. */
	@WrapOperation(
		method = "runPipeline",
		at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/rendering/section/backend/AbstractSectionRenderer;renderTemporal(Lme/cortex/voxy/client/core/rendering/Viewport;)V")
	)
	private void cubeworld$skipTemporal(AbstractSectionRenderer renderer, Viewport viewport, Operation<Void> original) {
		if (!VoxyFaces.laterFace()) {
			original.call(renderer, viewport);
		}
	}

	/**
	 * With a shader pack, Voxy draws its levels of detail straight into the buffers of the pack and keeps their depth in
	 * its own buffer, which the pack reads later in the frame to find them. Each draw cleared that depth, so the pack
	 * found only the levels of detail of the last face and drew sky over the others. A later face keeps the depth of the
	 * earlier faces. All faces share the screen and the projection, so its depth tests against them as the faces do in
	 * the frame.
	 */
	@WrapOperation(
		method = "initDepthStencil",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45;glClearNamedFramebufferfi(IIIFI)V")
	)
	private void cubeworld$keepEarlierFaces(int framebuffer, int buffer, int drawBuffer, float depth, int stencil, Operation<Void> original) {
		if (!(VoxyFaces.laterFace() && (Object) this instanceof IrisVoxyRenderPipeline)) {
			original.call(framebuffer, buffer, drawBuffer, depth, stencil);
		}
	}

	@WrapOperation(
		method = "innerPrimaryWork",
		at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager;tick(Lme/cortex/voxy/client/core/gl/GlBuffer;Lme/cortex/voxy/client/core/rendering/hierachical/NodeCleaner;)V")
	)
	private void cubeworld$tickNodesOnce(AsyncNodeManager manager, GlBuffer nodes, NodeCleaner cleaner, Operation<Void> original) {
		if (!VoxyFaces.laterFace()) {
			original.call(manager, nodes, cleaner);
		}
	}

	@WrapOperation(
		method = "innerPrimaryWork",
		at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/rendering/hierachical/NodeCleaner;tick(Lme/cortex/voxy/client/core/gl/GlBuffer;)V")
	)
	private void cubeworld$tickCleanerOnce(NodeCleaner cleaner, GlBuffer nodes, Operation<Void> original) {
		if (!VoxyFaces.laterFace()) {
			original.call(cleaner, nodes);
		}
	}
}

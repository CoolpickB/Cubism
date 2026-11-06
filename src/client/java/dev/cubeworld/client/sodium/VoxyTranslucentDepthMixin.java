package dev.cubeworld.client.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.client.sodiumseam.VoxyFaces;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.IrisVoxyRenderPipeline;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The shader pack finds the water of the levels of detail where their translucent depth is nearer than their opaque
 * depth, and gives it the fog of water there. Before its translucent pass, each face copies its opaque depth over the
 * translucent depth, which cleared the water of the faces drawn before it, and that water showed clear to the bottom. A
 * later face puts the translucent depth of the earlier faces back where it is nearer. All faces share the screen and the
 * projection, so the depths compare as they are.
 */
@Mixin(value = IrisVoxyRenderPipeline.class, remap = false)
public abstract class VoxyTranslucentDepthMixin {
	@Shadow
	@Final
	public DepthFramebuffer fbTranslucent;

	@Shadow
	@Final
	private FullscreenBlit depthBlit;

	@Unique
	private DepthFramebuffer cubeworld$earlier;

	@Unique
	private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

	@WrapOperation(
		method = "postOpaquePreTranslucent",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;glBlitNamedFramebuffer(IIIIIIIIIIII)V")
	)
	private void cubeworld$keepEarlierWater(int source, int target, int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1,
		int mask, int filter, Operation<Void> original) {
		if (!VoxyFaces.laterFace()) {
			original.call(source, target, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, mask, filter);
			return;
		}
		if (this.cubeworld$earlier == null) {
			this.cubeworld$earlier = new DepthFramebuffer(this.fbTranslucent.getFormat());
		}
		DepthFramebuffer earlier = this.cubeworld$earlier;
		earlier.resize(dx1, dy1);
		GL45C.glBlitNamedFramebuffer(target, earlier.framebuffer.id, 0, 0, dx1, dy1, 0, 0, dx1, dy1, GL11C.GL_DEPTH_BUFFER_BIT, GL11C.GL_NEAREST);
		original.call(source, target, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, mask, filter);

		// The blit shader takes a depth through the inverse of one matrix and back through the other. With the identity it
		// writes the depth as it is, and skips the pixels that hold no depth.
		GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, target);
		GL11C.glColorMask(false, false, false, false);
		GL11C.glDepthMask(true);
		GL11C.glDisable(GL11C.GL_STENCIL_TEST);
		GL11C.glEnable(GL11C.GL_DEPTH_TEST);
		GL11C.glDepthFunc(((AbstractRenderPipeline) (Object) this).properties.closerEqualDepthCompare());
		this.depthBlit.bind();
		GL45C.glBindTextureUnit(0, earlier.getDepthTex().id);
		GL30C.glUniformMatrix4fv(1, false, IDENTITY);
		GL30C.glUniformMatrix4fv(2, false, IDENTITY);
		this.depthBlit.blit();
		GL11C.glColorMask(true, true, true, true);
	}
}

package dev.cubeworld.client.sodium;

import dev.cubeworld.client.sodiumseam.VoxyFaces;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clips the quads of Voxy to the storage region of the face that draws, see {@link VoxyFaces#region}. */
@Mixin(value = MDICSectionRenderer.class, remap = false)
public abstract class VoxySectionRendererMixin {
	@Shadow
	@Final
	private GlBuffer uniform;

	@Inject(method = "uploadUniformBuffer", at = @At("TAIL"))
	private void cubeworld$uploadRegion(MDICViewport viewport, CallbackInfo ci) {
		float[] region = VoxyFaces.region(viewport);
		// The patched scene block puts the region after the members of Voxy, at byte 96.
		long ptr = UploadStream.INSTANCE.upload(this.uniform, 96L, 16L);
		for (int i = 0; i < 4; i++) {
			MemoryUtil.memPutFloat(ptr + i * 4L, region[i]);
		}
		UploadStream.INSTANCE.commit();
	}

	@Inject(method = "renderTerrain", at = @At("HEAD"))
	private void cubeworld$clipTerrain(CallbackInfo ci) {
		cubeworld$clip(VoxyFaces.active());
	}

	@Inject(method = "renderTerrain", at = @At("RETURN"))
	private void cubeworld$unclipTerrain(CallbackInfo ci) {
		cubeworld$clip(false);
	}

	@Inject(method = "renderTranslucent(Lme/cortex/voxy/client/core/rendering/section/backend/mdic/MDICViewport;)V", at = @At("HEAD"))
	private void cubeworld$clipTranslucent(CallbackInfo ci) {
		cubeworld$clip(VoxyFaces.active());
	}

	@Inject(method = "renderTranslucent(Lme/cortex/voxy/client/core/rendering/section/backend/mdic/MDICViewport;)V", at = @At("RETURN"))
	private void cubeworld$unclipTranslucent(CallbackInfo ci) {
		cubeworld$clip(false);
	}

	private static void cubeworld$clip(boolean on) {
		for (int i = 0; i < 4; i++) {
			if (on) {
				GL30C.glEnable(GL30C.GL_CLIP_DISTANCE0 + i);
			} else {
				GL30C.glDisable(GL30C.GL_CLIP_DISTANCE0 + i);
			}
		}
	}
}

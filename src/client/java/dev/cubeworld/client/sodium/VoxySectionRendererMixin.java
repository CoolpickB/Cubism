package dev.cubeworld.client.sodium;

import dev.cubeworld.client.sodiumseam.VoxyFaces;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives the shaders of Voxy the wedge of the face that draws, see {@link VoxyFaces#region}. */
@Mixin(value = MDICSectionRenderer.class, remap = false)
public abstract class VoxySectionRendererMixin {
	@Shadow
	@Final
	private GlBuffer uniform;

	@Inject(method = "uploadUniformBuffer", at = @At("TAIL"))
	private void cubeworld$uploadRegion(MDICViewport viewport, CallbackInfo ci) {
		float[] region = VoxyFaces.region(viewport);
		// The patched scene block puts the wedge after the members of Voxy, at byte 96.
		long ptr = UploadStream.INSTANCE.upload(this.uniform, 96L, region.length * 4L);
		for (int i = 0; i < region.length; i++) {
			MemoryUtil.memPutFloat(ptr + i * 4L, region[i]);
		}
		UploadStream.INSTANCE.commit();
	}
}

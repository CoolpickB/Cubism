package dev.cubism.client.compat.voxy.mixin;

import dev.cubism.client.compat.voxy.VoxyFaces;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Voxy stores a chunk when it loads; the cube also stores again the chunks across the seam that read from it. */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class VoxySectionManagerMixin {
	@Shadow
	@Final
	private ClientLevel level;

	@Inject(method = "onChunkAdded", at = @At("TAIL"))
	private void cubism$reingestAcrossSeam(int x, int z, CallbackInfo ci) {
		VoxyFaces.reingestAcrossSeam(this.level, x, z);
	}
}

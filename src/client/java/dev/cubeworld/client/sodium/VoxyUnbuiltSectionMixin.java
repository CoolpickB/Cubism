package dev.cubeworld.client.sodium;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.VisibleChunkCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Voxy hides its levels of detail only behind sections that Sodium has built, so a new chunk shows its
 * level of detail for some frames before the real blocks. Sections that Sodium has but did not build yet
 * hide the level of detail too, so a new chunk shows only the real blocks.
 */
@Mixin(value = VisibleChunkCollector.class, remap = false)
public abstract class VoxyUnbuiltSectionMixin {
	@Shadow
	@Final
	private RenderRegionManager regions;

	@Inject(method = "visit", at = @At("HEAD"))
	private void cubeworld$hideUnbuilt(int x, int y, int z, CallbackInfo ci) {
		VoxyRenderSystem vrs = IVoxyRenderSystemHolder.getNullable();
		if (vrs == null || vrs.visbleSectionStream == null || IrisUtil.irisShadowActive()) {
			return;
		}
		RenderRegion region = this.regions.getForChunk(x, y, z);
		if (region != null && (region.getSectionFlags(LocalSectionIndex.pack(x & 7, y & 3, z & 7)) & RenderSectionFlags.MASK_IS_BUILT) == 0) {
			vrs.visbleSectionStream.put(SectionPos.asLong(x, y, z));
		}
	}
}

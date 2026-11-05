package dev.cubeworld.client.sodium;

import dev.cubeworld.client.sodiumseam.SeamFaces;
import dev.cubeworld.client.sodiumseam.VoxyFaces;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.bounding.StreamedBoundStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Voxy collects the sections that Sodium draws into one set, and empties it whenever a Sodium manager builds its list.
 * The cube has a manager per face, so each face gets its own set, filled and emptied by the manager of that face.
 */
@Mixin(value = StreamedBoundStore.class, remap = false)
public abstract class VoxyBoundStoreMixin {
	@Inject(method = "reset", at = @At("HEAD"), cancellable = true)
	private void cubeworld$resetFace(CallbackInfo ci) {
		StreamedBoundStore face = this.cubeworld$faceStore();
		if (face != null) {
			face.reset();
			ci.cancel();
		}
	}

	@Inject(method = "put", at = @At("HEAD"), cancellable = true)
	private void cubeworld$putFace(long pos, CallbackInfo ci) {
		StreamedBoundStore face = this.cubeworld$faceStore();
		if (face != null) {
			face.put(pos);
			ci.cancel();
		}
	}

	/**
	 * Voxy marks only sections that Sodium has built, so a new chunk showed its level of detail, refining over some frames,
	 * until Sodium built it. Every chunk that Sodium will build hides the level of detail too, as Voxy does itself under FREX.
	 */
	@Inject(method = "reset", at = @At("TAIL"))
	private void cubeworld$readyColumns(CallbackInfo ci) {
		ClientLevel level = Minecraft.getInstance().level;
		VoxyRenderSystem system = IVoxyRenderSystemHolder.getNullable();
		if (level == null || system == null || system.visbleSectionStream == null) {
			return;
		}
		boolean faces = VoxyFaces.active();
		if (system.visbleSectionStream == (Object) this ? faces : !faces) {
			return;
		}
		StreamedBoundStore store = (StreamedBoundStore) (Object) this;
		int face = faces ? SeamFaces.currentFace() : 0;
		int minY = level.getMinSectionY();
		int maxY = level.getMaxSectionY();
		ChunkTracker.forEachChunk(ChunkTrackerHolder.get(level).getReadyChunks(), (x, z) -> {
			if (faces && SeamFaces.faceOfChunk(x) != face) {
				return;
			}
			for (int y = minY; y <= maxY; y++) {
				store.put(SectionPos.asLong(x, y, z));
			}
		});
	}

	/** The set of the current face when this is the shared set of Voxy, else null. */
	private StreamedBoundStore cubeworld$faceStore() {
		VoxyRenderSystem system = IVoxyRenderSystemHolder.getNullable();
		if (system == null || system.visbleSectionStream != (Object) this || !VoxyFaces.active()) {
			return null;
		}
		return VoxyFaces.store(system);
	}
}

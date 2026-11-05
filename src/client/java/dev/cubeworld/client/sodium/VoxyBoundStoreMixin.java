package dev.cubeworld.client.sodium;

import dev.cubeworld.client.ClientConfig;
import dev.cubeworld.client.sodiumseam.VoxyFaces;
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
		if (ClientConfig.voxyHide == 0) {
			ci.cancel();
			return;
		}
		StreamedBoundStore face = this.cubeworld$faceStore();
		if (face != null) {
			face.put(pos);
			ci.cancel();
		}
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

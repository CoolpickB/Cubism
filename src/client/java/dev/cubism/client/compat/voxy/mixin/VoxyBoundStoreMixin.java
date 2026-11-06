package dev.cubism.client.compat.voxy.mixin;

import dev.cubism.client.ClientConfig;
import dev.cubism.client.compat.voxy.VoxyFaces;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.bounding.StreamedBoundStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Voxy collects the sections that Sodium draws into one set, and empties it whenever a Sodium manager builds its list.
 * The cube has a manager per face, so each face gets its own set, filled and emptied by the manager of that face.
 */
@Mixin(value = StreamedBoundStore.class, remap = false)
public abstract class VoxyBoundStoreMixin {
	@Shadow
	private boolean didChange;

	@Inject(method = "reset", at = @At("HEAD"), cancellable = true)
	private void cubism$resetFace(CallbackInfo ci) {
		StreamedBoundStore face = this.cubism$faceStore();
		if (face != null) {
			face.reset();
			ci.cancel();
		}
	}

	@Inject(method = "put", at = @At("HEAD"), cancellable = true)
	private void cubism$putFace(long pos, CallbackInfo ci) {
		if (ClientConfig.voxyHide == 0) {
			ci.cancel();
			return;
		}
		// Voxy puts only right after a reset, which uploads the set. A chunk that loads between two resets puts too.
		this.didChange = true;
		StreamedBoundStore face = this.cubism$faceStore();
		if (face != null) {
			face.put(pos);
			ci.cancel();
		}
	}

	/** The set of the current face when this is the shared set of Voxy, else null. */
	private StreamedBoundStore cubism$faceStore() {
		VoxyRenderSystem system = IVoxyRenderSystemHolder.getNullable();
		if (system == null || system.visbleSectionStream != (Object) this || !VoxyFaces.active()) {
			return null;
		}
		return VoxyFaces.store(system);
	}
}

package dev.cubism.client.mixin.sky;

import com.mojang.blaze3d.platform.Lighting;
import dev.cubism.client.SeamClient;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla lights entities and held items from two directions fixed in the level, both mostly from above. A crossing
 * turns the frame of the level by 90 degrees under the camera, which flipped that shading at once. On the cube the
 * lights turn with the up that the camera shows, see {@link SeamClient#lightFrame}.
 */
@Mixin(Lighting.class)
public abstract class LightingMixin {
	@Shadow @Final private static Vector3fc DIFFUSE_LIGHT_0;
	@Shadow @Final private static Vector3fc DIFFUSE_LIGHT_1;

	@Shadow
	private void updateBuffer(Lighting.Entry entry, Vector3fc light0, Vector3fc light1) {}

	@Inject(method = "setupFor", at = @At("HEAD"))
	private void cubism$turnLevelLights(Lighting.Entry entry, CallbackInfo ci) {
		if (entry == Lighting.Entry.LEVEL && SeamClient.layout != null) {
			this.updateBuffer(entry, SeamClient.lightFrame.transform(DIFFUSE_LIGHT_0, new Vector3f()), SeamClient.lightFrame.transform(DIFFUSE_LIGHT_1, new Vector3f()));
		}
	}
}

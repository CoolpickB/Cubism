package dev.cubism.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubism.client.SeamClient;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.worldgen.SeamLayout;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A sound of another face region plays where that region shows from the camera. The stored position is hundreds of
 * blocks away, out of hearing. A sound that moves, such as a minecart, is moved again on each tick.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
	@Shadow @Final private Map<SoundInstance, ChannelAccess.ChannelHandle> instanceToChannel;
	@Unique private int cubism$cameraFace = -1;

	@ModifyExpressionValue(method = "play", at = @At(value = "NEW", target = "(DDD)Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 cubism$playAcrossSeam(Vec3 position, @Local(argsOnly = true) SoundInstance instance) {
		return cubism$heardAt(position, instance);
	}

	@ModifyExpressionValue(method = "tickInGameSound", at = @At(value = "NEW", target = "(DDD)Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 cubism$tickAcrossSeam(Vec3 position, @Local TickableSoundInstance instance) {
		return cubism$heardAt(position, instance);
	}

	/**
	 * A sound that does not tick, such as a jukebox, is placed once when it starts. When the camera crosses into
	 * another face region, the old placement is hundreds of blocks away, so every sound is placed again.
	 */
	@Inject(method = "updateSource", at = @At("TAIL"))
	private void cubism$replaceOnCrossing(Camera camera, CallbackInfo ci) {
		SeamLayout layout = SeamClient.layout;
		int face = layout == null || !camera.isInitialized() ? -1 : layout.faceIndexAt(Mth.floor(camera.position().x));
		if (face == this.cubism$cameraFace) {
			return;
		}
		this.cubism$cameraFace = face;
		this.instanceToChannel.forEach((instance, handle) -> {
			Vec3 position = cubism$heardAt(new Vec3(instance.getX(), instance.getY(), instance.getZ()), instance);
			handle.execute(channel -> channel.setSelfPosition(position));
		});
	}

	@Unique
	private static Vec3 cubism$heardAt(Vec3 position, SoundInstance instance) {
		// A relative sound is placed relative to the listener, not in a region.
		int[] m = instance.isRelative() ? null : SeamClient.motionToCamera(position.x, position.z);
		return m == null ? position : SeamInteraction.move(m, position);
	}
}

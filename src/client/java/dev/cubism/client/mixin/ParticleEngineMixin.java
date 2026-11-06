package dev.cubism.client.mixin;

import dev.cubism.client.SeamClient;
import dev.cubism.client.SeamParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A particle of another face region, such as from a block broken across a seam, starts where that region shows from
 * the camera. Particles that follow an entity, such as an item that a player picks up, still take the stored position
 * of the entity.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
	@Inject(method = "add", at = @At("HEAD"))
	private void cubism$showAcrossSeam(Particle particle, CallbackInfo ci) {
		SeamParticle seamParticle = (SeamParticle) particle;
		int[] m = SeamClient.motionToCamera(seamParticle.cubism$x(), seamParticle.cubism$z());
		if (m != null) {
			seamParticle.cubism$move(m);
		}
	}
}

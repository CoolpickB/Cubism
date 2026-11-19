package dev.cubism.client.mixin.player;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.client.CubeFlight;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.ElytraAnimationState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The wings spread by how steep the glide is. In the face frame a level glide over an edge dives before the diagonal
 * and climbs after it, so the wings would snap open at the crossing. The local player measures the glide in the air
 * frame instead.
 */
@Mixin(ElytraAnimationState.class)
public abstract class ElytraAnimationStateMixin {
	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getDeltaMovement()Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 cubism$glideInAirFrame(LivingEntity entity, Operation<Vec3> original) {
		Vec3 movement = original.call(entity);
		return entity instanceof LocalPlayer player ? CubeFlight.inAirFrame(player, movement) : movement;
	}
}

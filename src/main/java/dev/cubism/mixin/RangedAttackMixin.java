package dev.cubism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.seam.SeamInteraction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.CrossbowAttack;
import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedCrossbowAttackGoal;
import net.minecraft.world.entity.monster.RangedAttackMob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Skeletons, pillagers, witches, snow golems and piglins aim at a target across a seam where the target shows. The
 * projectile crosses the seam in flight.
 */
@Mixin({RangedAttackGoal.class, RangedBowAttackGoal.class, RangedCrossbowAttackGoal.class, CrossbowAttack.class})
public abstract class RangedAttackMixin {
	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/RangedAttackMob;performRangedAttack(Lnet/minecraft/world/entity/LivingEntity;F)V"))
	private void cubism$aimAcrossSeam(RangedAttackMob mob, LivingEntity target, float power, Operation<Void> original) {
		SeamInteraction.withTargetShown((Entity) mob, target, () -> original.call(mob, target, power));
	}
}

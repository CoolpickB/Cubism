package dev.cubism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.cubism.seam.SeamInteraction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Blaze;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * A blaze aims, chases and hits a target across a seam where the target shows. The whole attack tick runs with the
 * target shown, because the fireball direction, the move target and the melee hit all read its position.
 */
@Mixin(targets = "net.minecraft.world.entity.monster.Blaze$BlazeAttackGoal")
public abstract class BlazeAttackGoalMixin {
	@Shadow @Final private Blaze blaze;

	@WrapMethod(method = "tick")
	private void cubism$targetShown(Operation<Void> original) {
		LivingEntity target = this.blaze.getTarget();
		if (target != null) {
			// The sensing keeps this result for the tick. It must come from the real position, so that the ray tests
			// the blocks of both faces.
			this.blaze.getSensing().hasLineOfSight(target);
		}
		SeamInteraction.withTargetShown(this.blaze, target, original::call);
	}
}

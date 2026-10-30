package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.seam.SeamInteraction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Ghast;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** A ghast shoots at a target across a seam where the target shows. The fireball crosses the seam in flight. */
@Mixin(targets = "net.minecraft.world.entity.monster.Ghast$GhastShootFireballGoal")
public abstract class GhastShootFireballGoalMixin {
	@Shadow @Final private Ghast ghast;
	@Unique private boolean cubeworld$seesTarget;

	@WrapMethod(method = "tick")
	private void cubeworld$targetShown(Operation<Void> original) {
		LivingEntity target = this.ghast.getTarget();
		// The ray must start from the real position, so that it tests the blocks of both faces.
		this.cubeworld$seesTarget = target != null && this.ghast.hasLineOfSight(target);
		SeamInteraction.withTargetShown(this.ghast, target, original::call);
	}

	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/Ghast;hasLineOfSight(Lnet/minecraft/world/entity/Entity;)Z"))
	private boolean cubeworld$seesTarget(Ghast ghast, Entity target, Operation<Boolean> original) {
		return this.cubeworld$seesTarget;
	}
}

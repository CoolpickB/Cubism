package dev.cubism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.function.Predicate;

/**
 * A mob finds a target that is not a player across a seam, as a zombie finds a villager. Players need no change: the
 * search for players reads all players and measures with the distance across seams.
 */
@Mixin(NearestAttackableTargetGoal.class)
public abstract class TargetSearchMixin extends TargetGoal {
	private TargetSearchMixin(Mob mob) {
		super(mob, false);
	}

	@WrapOperation(method = "findTarget", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
	private <T> List<T> cubism$searchAcrossSeams(Level level, Class<T> type, AABB box, Predicate<? super T> filter, Operation<List<T>> original) {
		SeamLayout layout = SeamLayout.of(level);
		return layout == null ? original.call(level, type, box, filter)
			: SeamInteraction.acrossSeams(layout, this.mob.getX(), this.mob.getZ(), box, area -> original.call(level, type, area, filter));
	}
}

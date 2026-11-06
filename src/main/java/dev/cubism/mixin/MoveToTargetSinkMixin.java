package dev.cubism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import net.minecraft.world.entity.ai.behavior.PositionTracker;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A brain-based mob walks to a walk target across a seam where the target shows, as goal-based mobs do in
 * {@link PathNavigationMixin}. The mob crosses the seam on the way.
 */
@Mixin(MoveToTargetSink.class)
public abstract class MoveToTargetSinkMixin {
	@WrapOperation(
		method = {"checkExtraStartConditions", "tick", "tryComputePath", "reachedTarget"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/behavior/PositionTracker;currentBlockPosition()Lnet/minecraft/core/BlockPos;")
	)
	private BlockPos cubism$targetAcrossSeam(PositionTracker target, Operation<BlockPos> original, @Local(argsOnly = true) Mob body) {
		BlockPos pos = original.call(target);
		SeamLayout layout = SeamLayout.of(body.level());
		return layout == null ? pos : BlockPos.containing(SeamInteraction.intoRegionOf(layout, Vec3.atCenterOf(pos), body.getX(), body.getZ()));
	}
}

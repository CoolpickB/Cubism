package dev.cubism.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A path to an entity across a seam leads to where the entity shows, past the diagonal of the mob's own wedge. The
 * mob crosses the seam on the way, and its next path starts in the region of the target. Each override of
 * {@code createPath(Entity, int)} reads the target position itself.
 */
@Mixin({PathNavigation.class, GroundPathNavigation.class, FlyingPathNavigation.class, WallClimberNavigation.class})
public abstract class PathNavigationMixin {
	@ModifyExpressionValue(
		method = "createPath(Lnet/minecraft/world/entity/Entity;I)Lnet/minecraft/world/level/pathfinder/Path;",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;blockPosition()Lnet/minecraft/core/BlockPos;")
	)
	private BlockPos cubism$targetAcrossSeam(BlockPos pos, @Local(argsOnly = true) Entity target) {
		Mob mob = ((PathNavigationAccessor) this).cubism$getMob();
		SeamLayout layout = SeamLayout.of(mob.level());
		return layout == null ? pos : BlockPos.containing(SeamInteraction.intoRegionOf(layout, target.position(), mob.getX(), mob.getZ()));
	}
}

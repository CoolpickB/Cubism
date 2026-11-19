package dev.cubism.mixin.nether;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubism.cube.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla scales the storage position by 8 between the overworld and the Nether. Each cube stores its faces side by
 * side at its own spacing, so a scaled storage position lands on the wrong face. Between two cubes, a portal leads to
 * the same face at half or twice the distance from the face center.
 */
@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalBlockMixin {
	@WrapOperation(
		method = "getPortalDestination",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/border/WorldBorder;clampToBounds(DDD)Lnet/minecraft/core/BlockPos;")
	)
	private BlockPos cubism$sameFace(
		WorldBorder border, double x, double y, double z, Operation<BlockPos> original,
		@Local(argsOnly = true) ServerLevel currentLevel, @Local(ordinal = 1) ServerLevel newLevel,
		@Local(argsOnly = true) Entity entity
	) {
		SeamLayout from = SeamLayout.of(currentLevel);
		SeamLayout to = SeamLayout.of(newLevel);
		if (from == null || to == null || from.nether == to.nether) {
			return original.call(border, x, y, z);
		}
		BlockPos exit = to.portalExit(from, entity.getX(), entity.getY(), entity.getZ());
		return border.clampToBounds(exit.getX(), exit.getY(), exit.getZ());
	}
}

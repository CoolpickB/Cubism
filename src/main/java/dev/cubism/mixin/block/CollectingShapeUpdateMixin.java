package dev.cubism.mixin.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.seam.SeamUpdates;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.world.level.redstone.CollectingNeighborUpdater$ShapeUpdate")
public abstract class CollectingShapeUpdateMixin {
	@WrapOperation(
		method = "runNext",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/redstone/NeighborUpdater;executeShapeUpdate(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)V")
	)
	private void cubism$shapeAcrossSeam(
		LevelAccessor level, Direction direction, BlockPos pos, BlockPos neighborPos, BlockState neighborState, int flags, int limit, Operation<Void> original
	) {
		SeamUpdates.shapeUpdate(level, direction, pos, neighborPos, neighborState, flags, limit, original);
	}
}

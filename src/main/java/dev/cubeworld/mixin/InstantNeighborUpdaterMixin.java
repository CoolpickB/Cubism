package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.seam.SeamUpdates;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.InstantNeighborUpdater;
import net.minecraft.world.level.redstone.Orientation;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(InstantNeighborUpdater.class)
public abstract class InstantNeighborUpdaterMixin {
	@WrapOperation(
		method = "neighborChanged(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;Z)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/redstone/NeighborUpdater;executeUpdate(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;Z)V")
	)
	private void cubeworld$updateAcrossSeam(
		Level level, BlockState state, BlockPos pos, Block changed, @Nullable Orientation orientation, boolean movedByPiston, Operation<Void> original
	) {
		SeamUpdates.neighborChanged(level, state, pos, changed, orientation, movedByPiston, original);
	}

	@WrapOperation(
		method = "shapeUpdate",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/redstone/NeighborUpdater;executeShapeUpdate(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)V")
	)
	private void cubeworld$shapeAcrossSeam(
		LevelAccessor level, Direction direction, BlockPos pos, BlockPos neighborPos, BlockState neighborState, int flags, int limit, Operation<Void> original
	) {
		SeamUpdates.shapeUpdate(level, direction, pos, neighborPos, neighborState, flags, limit, original);
	}
}

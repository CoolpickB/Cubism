package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.seam.SeamUpdates;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = {
	"net.minecraft.world.level.redstone.CollectingNeighborUpdater$FullNeighborUpdate",
	"net.minecraft.world.level.redstone.CollectingNeighborUpdater$MultiNeighborUpdate",
	"net.minecraft.world.level.redstone.CollectingNeighborUpdater$SimpleNeighborUpdate"
})
public abstract class CollectingNeighborUpdaterMixin {
	@WrapOperation(
		method = "runNext",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/redstone/NeighborUpdater;executeUpdate(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;Z)V")
	)
	private void cubeworld$updateAcrossSeam(
		Level level, BlockState state, BlockPos pos, Block changed, @Nullable Orientation orientation, boolean movedByPiston, Operation<Void> original
	) {
		SeamUpdates.neighborChanged(level, state, pos, changed, orientation, movedByPiston, original);
	}
}

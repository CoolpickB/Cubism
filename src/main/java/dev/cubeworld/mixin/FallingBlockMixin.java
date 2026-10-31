package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The wedge narrows downward, so below each block on the diagonal lies cleared space that belongs to the next face.
 * Gravel and sand there read the block of the next face as their support, and stay in place.
 */
@Mixin(FallingBlock.class)
public abstract class FallingBlockMixin {
	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
	private BlockState cubeworld$supportAcrossSeam(ServerLevel level, BlockPos pos, Operation<BlockState> original) {
		SeamLayout layout = SeamLayout.of(level);
		return layout == null ? original.call(level, pos) : SeamInteraction.stateAcrossSeam(level, layout, pos);
	}
}

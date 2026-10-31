package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.LongJumpToRandomPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Past the diagonal of its wedge, a frog or goat sees only the cleared air of storage under a landing spot, so it never
 * jumped to the next face. The ground there is read from the face that owns it, as collision does.
 */
@Mixin(LongJumpToRandomPos.class)
public abstract class LongJumpToRandomPosMixin {
	@WrapOperation(
		method = "defaultAcceptableLandingSpot",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;")
	)
	private static BlockState cubeworld$groundAcrossSeam(Level level, BlockPos below, Operation<BlockState> original) {
		SeamLayout layout = level instanceof ServerLevel serverLevel ? SeamLayout.of(serverLevel) : null;
		return layout == null ? original.call(level, below) : SeamInteraction.stateAcrossSeam((ServerLevel) level, layout, below);
	}
}

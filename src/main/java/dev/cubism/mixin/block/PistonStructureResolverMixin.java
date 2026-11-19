package dev.cubism.mixin.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.seam.SeamStates;
import dev.cubism.cube.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A block that a piston would move across a seam must be able to stand on the next face. A block that cannot turn
 * 90 degrees, or a next face that is not loaded, would lose the block, so the piston treats it as immovable.
 */
@Mixin(PistonStructureResolver.class)
public abstract class PistonStructureResolverMixin {
	@WrapOperation(
		method = "*",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/piston/PistonBaseBlock;isPushable(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;ZLnet/minecraft/core/Direction;)Z")
	)
	private boolean cubism$staysWholeAcrossSeam(
		BlockState state, Level level, BlockPos pos, Direction direction, boolean allowDestroyable, Direction connectionDirection, Operation<Boolean> original
	) {
		boolean pushable = original.call(state, level, pos, direction, allowDestroyable, connectionDirection);
		// A block that pops when pushed does not move.
		if (!pushable || state.isAir() || allowDestroyable && state.getPistonPushReaction() == PushReaction.POPPED
			|| !(level instanceof ServerLevel serverLevel)) {
			return pushable;
		}
		SeamLayout layout = SeamLayout.of(serverLevel);
		if (layout == null) {
			return true;
		}
		BlockPos destination = pos.relative(direction);
		BlockPos owner = SeamInteraction.loadedOwner(serverLevel, layout, destination);
		if (owner == destination) {
			return true;
		}
		return owner != null && !SeamStates.unturnable(layout, state, layout.faceIndexAt(destination.getX()), layout.faceIndexAt(owner.getX()));
	}
}

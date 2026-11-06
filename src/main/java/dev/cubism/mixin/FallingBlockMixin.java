package dev.cubism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The wedge narrows downward, so below each block on the diagonal lies cleared space that belongs to the next face.
 * Gravel and sand there read the block of the next face as their support, and stay in place.
 */
@Mixin(FallingBlock.class)
public abstract class FallingBlockMixin {
	/**
	 * Block ticks run one chunk further out than entities. Sand that starts to fall there turns into an entity that the
	 * server never moves, while the client lets it fall on its own: into the void, or into the cleared air past a
	 * diagonal. The seam chunks have such an edge all along the seam. The block waits until its chunk runs entities.
	 */
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void cubism$waitForEntityTicking(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
		if (SeamLayout.of(level) != null && !level.isPositionEntityTicking(pos)) {
			level.scheduleTick(pos, (Block) (Object) this, 20);
			ci.cancel();
		}
	}

	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
	private BlockState cubism$supportAcrossSeam(ServerLevel level, BlockPos pos, Operation<BlockState> original) {
		SeamLayout layout = SeamLayout.of(level);
		return layout == null ? original.call(level, pos) : SeamInteraction.stateAcrossSeam(level, layout, pos);
	}
}

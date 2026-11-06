package dev.cubism.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.client.SeamClient;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A section draws only the blocks inside its wedge. The blocks outside it belong to another face, which draws them
 * itself, and leftover feature blocks there, such as tree tops that grew past the diagonal, would float in its sky.
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
	@WrapOperation(
		method = "compile",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
		)
	)
	private BlockState cubism$skipOutsideWedge(RenderSectionRegion region, BlockPos pos, Operation<BlockState> original) {
		SeamLayout layout = SeamClient.layout;
		if (layout != null && !layout.insideWedge(pos.getX(), pos.getY(), pos.getZ())) {
			return Blocks.AIR.defaultBlockState();
		}
		return original.call(region, pos);
	}
}

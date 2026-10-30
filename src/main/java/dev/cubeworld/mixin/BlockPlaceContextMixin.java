package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Next to a seam, the side of a block can face the cleared air outside the wedge. A block placed there goes to the
 * owner position, where the seam rendering shows it.
 */
@Mixin(BlockPlaceContext.class)
public abstract class BlockPlaceContextMixin {
	@ModifyExpressionValue(
		method = "<init>(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/phys/BlockHitResult;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;relative(Lnet/minecraft/core/Direction;)Lnet/minecraft/core/BlockPos;")
	)
	private BlockPos cubeworld$placeAcrossSeam(BlockPos pos, @Local(argsOnly = true) Level level) {
		SeamLayout layout = SeamLayout.of(level);
		return layout == null ? pos : SeamInteraction.owner(layout, pos);
	}
}

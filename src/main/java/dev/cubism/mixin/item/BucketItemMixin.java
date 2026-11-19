package dev.cubism.mixin.item;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.cube.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The block beside a clicked face across a seam can be outside the wedge of the clicked block. The fluid goes to the
 * owner position, as a placed block does in {@code BlockPlaceContextMixin}. Outside the wedge, the client refuses the
 * fluid, so it played no sound, and the server leaves the sound of the placer to the client.
 */
@Mixin(BucketItem.class)
public abstract class BucketItemMixin {
	@ModifyExpressionValue(method = "use", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;relative(Lnet/minecraft/core/Direction;)Lnet/minecraft/core/BlockPos;"))
	private BlockPos cubism$besideAcrossSeam(BlockPos pos, @Local(argsOnly = true) Level level) {
		SeamLayout layout = SeamLayout.of(level);
		return layout == null ? pos : SeamInteraction.owner(layout, pos);
	}
}

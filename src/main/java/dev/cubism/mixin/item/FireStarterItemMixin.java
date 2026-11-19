package dev.cubism.mixin.item;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.cube.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The fire goes to the owner position of the block beside the clicked face, as the fluid of a bucket does in
 * {@code BucketItemMixin}. Outside the wedge, the client found no place for fire, so it played no sound.
 */
@Mixin({FlintAndSteelItem.class, FireChargeItem.class})
public abstract class FireStarterItemMixin {
	@ModifyExpressionValue(method = "useOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;relative(Lnet/minecraft/core/Direction;)Lnet/minecraft/core/BlockPos;"))
	private BlockPos cubism$besideAcrossSeam(BlockPos pos, @Local(argsOnly = true) UseOnContext context) {
		SeamLayout layout = SeamLayout.of(context.getLevel());
		return layout == null ? pos : SeamInteraction.owner(layout, pos);
	}
}

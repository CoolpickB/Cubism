package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Next to a seam, the side of a block can face the cleared air outside the wedge. A block placed there goes to the
 * owner position, where the seam rendering shows it. The look of the player then turns into the frame of that face.
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

	// The vanilla order comes from the look of the player. Each direction turns into the frame of the clicked position.
	@ModifyExpressionValue(
		method = {"getNearestLookingDirection", "getNearestLookingDirections"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Direction;orderedByNearest(Lnet/minecraft/world/entity/Entity;)[Lnet/minecraft/core/Direction;")
	)
	private Direction[] cubeworld$lookOrderAcrossSeam(Direction[] directions) {
		int[] m = this.cubeworld$playerMotion();
		if (m != null) {
			for (int i = 0; i < directions.length; i++) {
				directions[i] = SeamInteraction.rotate(m, directions[i]);
			}
		}
		return directions;
	}

	@ModifyReturnValue(method = "getNearestLookingVerticalDirection", at = @At("RETURN"))
	private Direction cubeworld$verticalLookAcrossSeam(Direction direction) {
		int[] m = this.cubeworld$playerMotion();
		if (m == null) {
			return direction;
		}
		Vec3 view = SeamInteraction.rotate(m, ((BlockPlaceContext) (Object) this).getPlayer().getViewVector(1.0F));
		return view.y > 0.0 ? Direction.UP : Direction.DOWN;
	}

	private int @Nullable [] cubeworld$playerMotion() {
		BlockPlaceContext self = (BlockPlaceContext) (Object) this;
		SeamLayout layout = SeamLayout.of(self.getLevel());
		Player player = self.getPlayer();
		BlockPos pos = self.getClickedPos();
		return layout == null || player == null ? null : SeamInteraction.regionMotion(layout, player.getX(), player.getZ(), pos.getX() + 0.5, pos.getZ() + 0.5);
	}
}

package dev.cubism.mixin.item;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubism.seam.SeamCrossing;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.cube.SeamLayout;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Buckets, boats and lily pads aim with their own ray. Vanilla builds it from the angles of the player in world axes,
 * so on another face it pointed away from the crosshair. The ray now follows the view of the player in its frame and
 * reads across seams, as the crosshair does.
 */
@Mixin(Item.class)
public abstract class ItemMixin {
	@WrapOperation(method = "getPlayerPOVHitResult", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;calculateViewVector(FF)Lnet/minecraft/world/phys/Vec3;"))
	private static Vec3 cubism$viewInFrame(float xRot, float yRot, Operation<Vec3> original, @Local(argsOnly = true) Player player) {
		return player.getViewVector(1.0F);
	}

	@WrapOperation(method = "getPlayerPOVHitResult", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getEyePosition()Lnet/minecraft/world/phys/Vec3;"))
	private static Vec3 cubism$shownEye(Player player, Operation<Vec3> original) {
		return SeamCrossing.shownEye(player, original.call(player));
	}

	@WrapOperation(method = "getPlayerPOVHitResult", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
	private static BlockHitResult cubism$clipAcrossSeams(Level level, ClipContext context, Operation<BlockHitResult> original) {
		SeamLayout layout = SeamLayout.of(level);
		// A block of another face goes to its owner position, with its face in the owner frame, as the crosshair does.
		// The item puts its fluid or boat beside that face, and beside the stored position it went to the wrong block.
		return layout == null ? original.call(level, context) : (BlockHitResult) SeamInteraction.toOwner(layout, SeamInteraction.clip(level, layout, context));
	}
}

package dev.cubism.client.mixin.player;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.cube.SeamLayout;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
	// The server reads the hit as an offset from the entity. A hit across a seam shows outside the region of the entity.
	@ModifyExpressionValue(method = "interact", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/EntityHitResult;getLocation()Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 cubism$hitInEntityRegion(Vec3 location, @Local(argsOnly = true) Entity entity) {
		SeamLayout layout = SeamLayout.of(entity.level());
		return layout == null ? location : SeamInteraction.intoRegionOf(layout, location, entity.getX(), entity.getZ());
	}

	/**
	 * The client predicts a use with the same block logic as the server. Next to a seam that logic reads blocks outside
	 * the wedge, such as the block under a comparator or around fire, so the prediction reads across the seam as the
	 * server does. See {@code LevelMixin}.
	 */
	@WrapMethod(method = "useItemOn")
	private InteractionResult cubism$predictUseOnAcrossSeams(LocalPlayer player, InteractionHand hand, BlockHitResult hit, Operation<InteractionResult> original) {
		return SeamInteraction.predict(() -> original.call(player, hand, hit));
	}

	@WrapMethod(method = "useItem")
	private InteractionResult cubism$predictUseAcrossSeams(Player player, InteractionHand hand, Operation<InteractionResult> original) {
		return SeamInteraction.predict(() -> original.call(player, hand));
	}
}

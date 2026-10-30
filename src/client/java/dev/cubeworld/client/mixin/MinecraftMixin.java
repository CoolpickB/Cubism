package dev.cubeworld.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.cubeworld.client.SeamClient;
import dev.cubeworld.seam.SeamInteraction;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The crosshair target moves to the owner position after the pick, because the pick compares the block hit with
 * entity hits by distance in the region of the player.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@ModifyExpressionValue(method = "pick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;raycastHitResult(FLnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/phys/HitResult;"))
	private HitResult cubeworld$targetOwnerBlock(HitResult hit) {
		return SeamClient.layout == null ? hit : SeamInteraction.toOwner(SeamClient.layout, hit);
	}
}

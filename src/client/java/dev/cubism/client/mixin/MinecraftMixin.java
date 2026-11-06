package dev.cubism.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.cubism.client.LoadTraceClient;
import dev.cubism.client.SeamClient;
import dev.cubism.debug.LoadTrace;
import dev.cubism.seam.SeamInteraction;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The crosshair target moves to the owner position after the pick, because the pick compares the block hit with
 * entity hits by distance in the region of the player.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@ModifyExpressionValue(method = "pick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;raycastHitResult(FLnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/phys/HitResult;"))
	private HitResult cubism$targetOwnerBlock(HitResult hit) {
		return SeamClient.layout == null ? hit : SeamInteraction.toOwner(SeamClient.layout, hit);
	}

	@Inject(method = "doWorldLoad", at = @At("HEAD"))
	private void cubism$traceWorldLoad(CallbackInfo ci) {
		LoadTraceClient.beginIfIdle("singleplayer world opened");
		LoadTrace.event("client: doWorldLoad, starting the integrated server");
	}

	@Inject(method = "doWorldLoad", at = @At("TAIL"))
	private void cubism$traceServerReady(CallbackInfo ci) {
		LoadTrace.event("client: integrated server ready, connecting");
	}

	@Inject(method = "renderFrame", at = @At("HEAD"))
	private void cubism$traceFrame(CallbackInfo ci) {
		LoadTraceClient.frame((Minecraft) (Object) this);
	}
}

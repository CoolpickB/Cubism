package dev.cubism.client.mixin.gui;

import dev.cubism.client.debug.LoadTraceClient;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {
	@Inject(method = "onCreate", at = @At("HEAD"))
	private void cubism$beginTrace(CallbackInfo ci) {
		LoadTraceClient.begin("Create World clicked");
	}
}

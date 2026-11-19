package dev.cubism.client.mixin.gui;

import dev.cubism.gravity.GravityLock;
import net.minecraft.client.gui.screens.inventory.EffectsInInventory;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A level of Steadfast that forces a face shows the face, "Steadfast (Up)", not a level number. */
@Mixin(EffectsInInventory.class)
public abstract class EffectsInInventoryMixin {
	@Inject(method = "getEffectName", at = @At("HEAD"), cancellable = true)
	private void cubism$steadfastFace(MobEffectInstance effect, CallbackInfoReturnable<Component> cir) {
		int amplifier = effect.getAmplifier();
		if (effect.is(GravityLock.STEADFAST) && amplifier >= 1 && amplifier <= 6) {
			cir.setReturnValue(Component.translatable("effect.cubism.steadfast." + Direction.from3DDataValue(amplifier - 1).getSerializedName()));
		}
	}
}

package dev.cubism.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.cubism.client.ClientConfig;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Arrays;

/** Adds the Edge Camera Tilt button to the Accessibility options. It is saved in the Cubism client options file. */
@Mixin(AccessibilityOptionsScreen.class)
public abstract class AccessibilityOptionsScreenMixin {
	@ModifyReturnValue(method = "options", at = @At("RETURN"))
	private static OptionInstance<?>[] cubism$addEdgeTilt(OptionInstance<?>[] options) {
		OptionInstance<Boolean> edgeTilt = OptionInstance.createBoolean(
			"options.cubism.edgeCameraTilt",
			OptionInstance.cachedConstantTooltip(Component.translatable("options.cubism.edgeCameraTilt.tooltip")),
			ClientConfig.edgeCameraTilt(),
			ClientConfig::setEdgeCameraTilt
		);
		OptionInstance<?>[] result = Arrays.copyOf(options, options.length + 1);
		result[options.length] = edgeTilt;
		return result;
	}
}

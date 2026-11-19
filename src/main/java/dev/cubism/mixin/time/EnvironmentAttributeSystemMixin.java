package dev.cubism.mixin.time;

import dev.cubism.time.CubeTime;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EnvironmentAttributeSystem.class)
public abstract class EnvironmentAttributeSystemMixin {
	@Inject(method = "addDynamicLayers", at = @At("HEAD"), cancellable = true)
	private static void cubism$localTime(EnvironmentAttributeSystem.Builder builder, Level level, CallbackInfo ci) {
		if (CubeTime.addDynamicLayers(builder, level)) {
			ci.cancel();
		}
	}
}

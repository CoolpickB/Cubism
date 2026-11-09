package dev.cubism.client.compat.xaerominimap.mixin;

import dev.cubism.client.compat.XaeroSpace;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pins the local player in space to {@link XaeroSpace} on the map. */
@Mixin(targets = "xaero.hud.entity.EntityUtils", remap = false)
public abstract class EntityUtilsMixin {
	@Inject(method = "getEntityX", at = @At("HEAD"), cancellable = true)
	private static void cubism$pinX(Entity entity, float partialTicks, CallbackInfoReturnable<Double> cir) {
		if (XaeroSpace.pinned(entity)) {
			cir.setReturnValue(XaeroSpace.x());
		}
	}

	@Inject(method = "getEntityZ", at = @At("HEAD"), cancellable = true)
	private static void cubism$pinZ(Entity entity, float partialTicks, CallbackInfoReturnable<Double> cir) {
		if (XaeroSpace.pinned(entity)) {
			cir.setReturnValue(XaeroSpace.z());
		}
	}

	@Inject(method = "getEntityPos", at = @At("HEAD"), cancellable = true)
	private static void cubism$pinPos(Entity entity, float partialTicks, CallbackInfoReturnable<Vec3> cir) {
		if (XaeroSpace.pinned(entity)) {
			cir.setReturnValue(new Vec3(XaeroSpace.x(), entity.getPosition(partialTicks).y, XaeroSpace.z()));
		}
	}
}

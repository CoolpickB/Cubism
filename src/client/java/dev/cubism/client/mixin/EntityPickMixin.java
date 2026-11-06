package dev.cubism.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.client.CubeFlight;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The block pick of the local player starts at the eye that the camera shows. See {@link CubeFlight#eyePosition}. */
@Mixin(Entity.class)
public abstract class EntityPickMixin {
	@WrapOperation(method = "pick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 cubism$pickFromShownEye(Entity entity, float partialTicks, Operation<Vec3> original) {
		return CubeFlight.eyePosition(entity, partialTicks, original.call(entity, partialTicks));
	}
}

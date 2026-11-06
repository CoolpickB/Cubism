package dev.cubism.client.compat.entityculling.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubism.client.SeamClient;
import dev.cubism.client.render.SeamRenderer;
import dev.tr7zw.entityculling.CullTask;
import dev.tr7zw.entityculling.access.Cullable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Entity Culling traces from the camera to each entity through the stored blocks. An entity of another face region is
 * stored far from where it shows, so the trace says nothing about whether it is hidden. Such an entity is not culled.
 */
@Mixin(value = CullTask.class, remap = false)
public abstract class CullTaskMixin {
	@WrapOperation(method = "cullEntities", at = @At(value = "INVOKE", target = "Ldev/tr7zw/entityculling/access/Cullable;setCulled(Z)V"))
	private void cubism$keepOtherFaces(Cullable cullable, boolean culled, Operation<Void> original, @Local(argsOnly = true) Vec3 camera) {
		boolean otherFace = culled && SeamClient.layout != null && cullable instanceof Entity entity && SeamRenderer.inOtherRegion(entity, camera.x, camera.z);
		original.call(cullable, culled && !otherFace);
	}
}

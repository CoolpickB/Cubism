package dev.cubeworld.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubeworld.client.SeamClient;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
	// Name tags show only near the camera. An entity of another face region shows next to it, not where it is stored.
	@ModifyReturnValue(method = "distanceToSqr(Lnet/minecraft/world/entity/Entity;)D", at = @At("RETURN"))
	private double cubeworld$distanceAcrossSeam(double distance, @Local(argsOnly = true) Entity entity) {
		SeamLayout layout = SeamClient.layout;
		if (layout == null) {
			return distance;
		}
		Vec3 camera = ((EntityRenderDispatcher) (Object) this).camera.position();
		return camera.distanceToSqr(SeamInteraction.intoRegionOf(layout, entity.position(), camera.x, camera.z));
	}
}

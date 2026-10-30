package dev.cubeworld.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.cubeworld.client.SeamClient;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import dev.cubeworld.client.GravityRenderStateAccess;
import dev.cubeworld.gravity.GravityEntityAccess;
import dev.cubeworld.math.CubePlanetMath;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
	@Inject(method = "extractEntity", at = @At("RETURN"))
	private <E extends Entity> void cubeworld$copyEntityGravityToState(
		E entity,
		float partialTicks,
		CallbackInfoReturnable<EntityRenderState> cir
	) {
		EntityRenderState state = cir.getReturnValue();
		if (state instanceof GravityRenderStateAccess access && entity instanceof GravityEntityAccess entityAccess) {
			access.cubeworld$setRenderGravity(entityAccess.cubeworld$getFrame());
		}
	}

	@Inject(
		method = "submit",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V",
			shift = At.Shift.AFTER
		)
	)
	private <S extends EntityRenderState> void cubeworld$rotateEntityPose(
		S state,
		CameraRenderState cameraState,
		double x,
		double y,
		double z,
		PoseStack poseStack,
		SubmitNodeCollector collector,
		CallbackInfo ci
	) {
		if (state instanceof GravityRenderStateAccess access) {
			Direction dir = access.cubeworld$getRenderGravity();
			if (dir != null && dir != Direction.DOWN) {
				poseStack.mulPose(new Matrix4f().rotation(CubePlanetMath.getCameraRotation(dir)));
			}
		}
	}

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

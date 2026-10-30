package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.gravity.GravityEntityAccess;
import dev.cubeworld.math.CubePlanetMath;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin extends LivingEntity {
	protected PlayerMixin(EntityType<? extends LivingEntity> type, Level level) {
		super(type, level);
	}

	// Sneak edge stop. The deltas are local because the movement is local.
	@Inject(method = "canFallAtLeast", at = @At("HEAD"), cancellable = true)
	private void cubeworld$canFallAtLeastOriented(double deltaX, double deltaZ, double minHeight, CallbackInfoReturnable<Boolean> cir) {
		Direction frame = ((GravityEntityAccess) this).cubeworld$getFrame();
		if (frame == Direction.DOWN) {
			return;
		}
		AABB box = CubePlanetMath.worldToLocal(this.getBoundingBox(), frame);
		AABB below = new AABB(
			box.minX + 1.0E-7 + deltaX,
			box.minY - minHeight - 1.0E-7,
			box.minZ + 1.0E-7 + deltaZ,
			box.maxX - 1.0E-7 + deltaX,
			box.minY,
			box.maxZ - 1.0E-7 + deltaZ
		);
		cir.setReturnValue(this.level().noCollision(this, CubePlanetMath.localToWorld(below, frame)));
	}

	@WrapOperation(method = "canPlayerFitWithinBlocksAndEntitiesWhen", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/EntityDimensions;makeBoundingBox(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/AABB;"))
	private AABB cubeworld$orientedPoseBox(EntityDimensions dimensions, Vec3 pos, Operation<AABB> original) {
		Direction frame = ((GravityEntityAccess) this).cubeworld$getFrame();
		return frame == Direction.DOWN ? original.call(dimensions, pos) : CubePlanetMath.localToWorld(dimensions.makeBoundingBox(Vec3.ZERO), frame).move(pos);
	}

	// Swimming steers the local vertical velocity with the look direction.
	@ModifyExpressionValue(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getLookAngle()Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 cubeworld$localLookForSwimming(Vec3 look) {
		return CubePlanetMath.worldToLocal(look, ((GravityEntityAccess) this).cubeworld$getFrame());
	}
}

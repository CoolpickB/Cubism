package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.gravity.GravityEntityAccess;
import dev.cubeworld.math.CubePlanetMath;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends Entity {
	public LivingEntityMixin(EntityType<?> type, Level level) {
		super(type, level);
	}

	// Knockback comes as a world direction from the source of the damage.
	@WrapOperation(
		method = "knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V",
		at = @At(value = "NEW", target = "(DDD)Lnet/minecraft/world/phys/Vec3;")
	)
	private Vec3 cubeworld$knockbackInLocalFrame(double xd, double yd, double zd, Operation<Vec3> original) {
		Vec3 local = CubePlanetMath.worldToLocal(new Vec3(xd, yd, zd), ((GravityEntityAccess) this).cubeworld$getFrame());
		return original.call(local.x, 0.0, local.z);
	}

	// Fluid travel compares heights with getY(). In the local frame the height is the coordinate along local up.
	@WrapOperation(method = {"travelInFluid", "jumpOutOfFluid"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getY()D"))
	private double cubeworld$heightAlongLocalUp(LivingEntity self, Operation<Double> original) {
		Direction frame = ((GravityEntityAccess) this).cubeworld$getFrame();
		return frame == Direction.DOWN ? original.call(self) : self.position().dot(CubePlanetMath.up(frame));
	}

	@WrapOperation(method = "wouldNotSuffocateAtTargetPose", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/EntityDimensions;makeBoundingBox(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/AABB;"))
	private AABB cubeworld$orientedPoseBox(EntityDimensions dimensions, Vec3 pos, Operation<AABB> original) {
		Direction frame = ((GravityEntityAccess) this).cubeworld$getFrame();
		return frame == Direction.DOWN ? original.call(dimensions, pos) : CubePlanetMath.localToWorld(dimensions.makeBoundingBox(Vec3.ZERO), frame).move(pos);
	}

	// A dropped item leaves from the hand in the dropper's frame. Its velocity is already in that frame.
	@Inject(method = "createItemStackToDrop", at = @At("RETURN"))
	private void cubeworld$dropInDropperFrame(CallbackInfoReturnable<ItemEntity> cir) {
		ItemEntity item = cir.getReturnValue();
		Direction frame = ((GravityEntityAccess) this).cubeworld$getFrame();
		if (item == null || frame == Direction.DOWN) {
			return;
		}
		((GravityEntityAccess) item).cubeworld$forceGravityDirection(frame);
		Vec3 hand = this.position().add(CubePlanetMath.up(frame).scale(this.getEyeHeight() - 0.3F));
		item.setPos(hand.x, hand.y, hand.z);
	}
}

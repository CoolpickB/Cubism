package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.gravity.GravityEntityAccess;
import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.core.Direction;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends Entity {
	/** Edge gravity starts only this far from the ground along the pull, in blocks. */
	@Unique private static final double EDGE_GRAVITY_GROUND_GAP = 3.0;
	/** The face region where the entity last stood or flew, or -1 before the first air tick. */
	@Unique private int cubeworld$fallStartFace = -1;

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

	/** A mob sees a target across a seam along the ray that the seam rendering shows, through the blocks of both faces. */
	@Inject(
		method = "hasLineOfSight(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/ClipContext$Block;Lnet/minecraft/world/level/ClipContext$Fluid;D)Z",
		at = @At("HEAD"),
		cancellable = true
	)
	private void cubeworld$lineOfSightAcrossSeams(Entity target, ClipContext.Block block, ClipContext.Fluid fluid, double eyeY, CallbackInfoReturnable<Boolean> cir) {
		SeamLayout layout = target.level() == this.level() ? SeamLayout.of(this.level()) : null;
		int[] m = layout == null ? null : SeamInteraction.regionMotion(layout, target.getX(), target.getZ(), this.getX(), this.getZ());
		if (m == null) {
			return;
		}
		Vec3 from = new Vec3(this.getX(), this.getEyeY(), this.getZ());
		Vec3 to = SeamInteraction.move(m, new Vec3(target.getX(), eyeY, target.getZ()));
		cir.setReturnValue(
			to.distanceTo(from) <= 128.0 && SeamInteraction.clip(this.level(), layout, new ClipContext(from, to, block, fluid, this)).getType() == HitResult.Type.MISS
		);
	}

	// The knockback points away from where the attacker shows, also when it is stored in another face region.
	@ModifyExpressionValue(method = "dealDefaultKnockback", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/damagesource/DamageSource;getSourcePosition()Lnet/minecraft/world/phys/Vec3;"))
	private @Nullable Vec3 cubeworld$sourceInOwnRegion(@Nullable Vec3 source) {
		SeamLayout layout = source == null ? null : SeamLayout.of(this.level());
		return layout == null ? source : SeamInteraction.intoRegionOf(layout, source, this.getX(), this.getZ());
	}

	// The extra knockback follows the look of the attacker, a direction of its own region.
	@WrapOperation(method = "causeExtraKnockback", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V"))
	private void cubeworld$extraKnockbackAcrossSeam(
		LivingEntity target, double power, double xd, double zd, DamageSource source, float damage, boolean comesFromEffect, Operation<Void> original
	) {
		SeamLayout layout = SeamLayout.of(this.level());
		Vec3 direction = layout == null ? new Vec3(xd, 0.0, zd)
			: SeamInteraction.turnIntoRegionOf(layout, new Vec3(xd, 0.0, zd), this.getX(), this.getZ(), target.getX(), target.getZ());
		original.call(target, power, direction.x, direction.z, source, damage, comesFromEffect);
	}

	/**
	 * Vanilla pulls straight down in the frame of the face. Over an edge the pull turns to {@link SeamLayout#airGravity},
	 * applied with the same drag as the vanilla pull. Only with no ground close along the pull: the rounded edge is a
	 * stair of blocks, and a pull during each short drop from one step to the next would slide the entity down the
	 * stair. Past the diagonal, the ground of the next face lies along the pull, not straight down. A fall that stays
	 * on the face it started from keeps the vanilla pull, also near an edge.
	 */
	@Inject(method = "travelInAir", at = @At("TAIL"))
	private void cubeworld$edgeGravity(Vec3 input, CallbackInfo ci) {
		SeamLayout layout = SeamLayout.of(this.level());
		if (layout == null) {
			return;
		}
		int face = SeamInteraction.regionFace(layout, this.getX(), this.getZ());
		if (this.onGround() || ((LivingEntity) (Object) this).hasEffect(MobEffects.LEVITATION)
			|| (Object) this instanceof Player player && player.getAbilities().flying || this.cubeworld$fallStartFace < 0) {
			this.cubeworld$fallStartFace = face;
			return;
		}
		if (face == this.cubeworld$fallStartFace) {
			return;
		}
		Vec3 center = this.getBoundingBox().getCenter();
		Vec3 direction = layout.airGravity(center.x, center.y, center.z);
		if (direction == null || !this.level().noCollision(this, this.getBoundingBox().expandTowards(direction.scale(EDGE_GRAVITY_GROUND_GAP)))) {
			return;
		}
		double gravity = this.getEffectiveGravity();
		this.setDeltaMovement(this.getDeltaMovement().add(gravity * direction.x * 0.91, gravity * (direction.y + 1.0) * 0.98, gravity * direction.z * 0.91));
	}
}

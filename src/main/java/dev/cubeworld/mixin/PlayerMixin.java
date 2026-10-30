package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.gravity.GravityEntityAccess;
import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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

	/** A block across a seam is stored in another face region, but the player reaches it where it shows. */
	@Inject(method = "isWithinBlockInteractionRange", at = @At("RETURN"), cancellable = true)
	private void cubeworld$reachAcrossSeams(BlockPos pos, double buffer, CallbackInfoReturnable<Boolean> cir) {
		SeamLayout layout = cir.getReturnValueZ() ? null : SeamLayout.of(this.level());
		BlockPos shown = layout == null ? null : SeamInteraction.inRegionOf(layout, pos, this.getBlockX());
		if (shown != null) {
			double maxRange = ((Player) (Object) this).blockInteractionRange() + buffer;
			cir.setReturnValue(new AABB(shown).distanceToSqr(this.getEyePosition()) < maxRange * maxRange);
		}
	}

	/**
	 * An entity across a seam is stored in another face region, but the player reaches it where it shows. The call
	 * again with the shown box runs this check once more, where the box is already in the region of the player.
	 */
	@Inject(method = "isWithinEntityInteractionRange(Lnet/minecraft/world/phys/AABB;D)Z", at = @At("RETURN"), cancellable = true)
	private void cubeworld$entityReachAcrossSeams(AABB box, double buffer, CallbackInfoReturnable<Boolean> cir) {
		AABB shown = cir.getReturnValueZ() ? null : this.cubeworld$shownBox(box);
		if (shown != null) {
			cir.setReturnValue(((Player) (Object) this).isWithinEntityInteractionRange(shown, buffer));
		}
	}

	@Inject(method = "isWithinAttackRange", at = @At("RETURN"), cancellable = true)
	private void cubeworld$attackReachAcrossSeams(ItemStack weapon, AABB box, double buffer, CallbackInfoReturnable<Boolean> cir) {
		AABB shown = cir.getReturnValueZ() ? null : this.cubeworld$shownBox(box);
		if (shown != null) {
			cir.setReturnValue(((Player) (Object) this).isWithinAttackRange(weapon, shown, buffer));
		}
	}

	/** The box moved into the region of the player, or null when it is there already. */
	@Unique
	private @Nullable AABB cubeworld$shownBox(AABB box) {
		SeamLayout layout = SeamLayout.of(this.level());
		AABB shown = layout == null ? box : SeamInteraction.intoRegionOf(layout, box, this.getX(), this.getZ());
		return shown == box ? null : shown;
	}

	@WrapOperation(method = "causeExtraKnockback", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V"))
	private void cubeworld$extraKnockbackAcrossSeam(
		LivingEntity target, double power, double xd, double zd, DamageSource source, float damage, boolean comesFromEffect, Operation<Void> original
	) {
		SeamLayout layout = SeamLayout.of(this.level());
		Vec3 direction = layout == null ? new Vec3(xd, 0.0, zd)
			: SeamInteraction.turnIntoRegionOf(layout, new Vec3(xd, 0.0, zd), this.getX(), this.getZ(), target.getX(), target.getZ());
		original.call(target, power, direction.x, direction.z, source, damage, comesFromEffect);
	}
}

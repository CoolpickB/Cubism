package dev.cubism.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.gravity.GravityLock;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.cube.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(Player.class)
public abstract class PlayerMixin extends LivingEntity {
	protected PlayerMixin(EntityType<? extends LivingEntity> type, Level level) {
		super(type, level);
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void cubism$bootsTick(CallbackInfo ci) {
		GravityLock.tickBoots((Player) (Object) this);
	}

	/** A block across a seam is stored in another face region, but the player reaches it where it shows. */
	@Inject(method = "isWithinBlockInteractionRange", at = @At("RETURN"), cancellable = true)
	private void cubism$reachAcrossSeams(BlockPos pos, double buffer, CallbackInfoReturnable<Boolean> cir) {
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
	private void cubism$entityReachAcrossSeams(AABB box, double buffer, CallbackInfoReturnable<Boolean> cir) {
		AABB shown = cir.getReturnValueZ() ? null : this.cubism$shownBox(box);
		if (shown != null) {
			cir.setReturnValue(((Player) (Object) this).isWithinEntityInteractionRange(shown, buffer));
		}
	}

	@Inject(method = "isWithinAttackRange", at = @At("RETURN"), cancellable = true)
	private void cubism$attackReachAcrossSeams(ItemStack weapon, AABB box, double buffer, CallbackInfoReturnable<Boolean> cir) {
		AABB shown = cir.getReturnValueZ() ? null : this.cubism$shownBox(box);
		if (shown != null) {
			cir.setReturnValue(((Player) (Object) this).isWithinAttackRange(weapon, shown, buffer));
		}
	}

	/** The box moved into the region of the player, or null when it is there already. */
	@Unique
	private @Nullable AABB cubism$shownBox(AABB box) {
		SeamLayout layout = SeamLayout.of(this.level());
		AABB shown = layout == null ? box : SeamInteraction.intoRegionOf(layout, box, this.getX(), this.getZ());
		return shown == box ? null : shown;
	}

	@WrapOperation(method = "causeExtraKnockback", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V"))
	private void cubism$extraKnockbackAcrossSeam(
		LivingEntity target, double power, double xd, double zd, DamageSource source, float damage, boolean comesFromEffect, Operation<Void> original
	) {
		SeamLayout layout = SeamLayout.of(this.level());
		Vec3 direction = layout == null ? new Vec3(xd, 0.0, zd)
			: SeamInteraction.turnIntoRegionOf(layout, new Vec3(xd, 0.0, zd), this.getX(), this.getZ(), target.getX(), target.getZ());
		original.call(target, power, direction.x, direction.z, source, damage, comesFromEffect);
	}

	// A sweep also hits the entities that show next to the target across a seam.
	@WrapOperation(method = "doSweepAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
	private <T> List<T> cubism$sweepAcrossSeams(Level level, Class<T> type, AABB box, Operation<List<T>> original) {
		SeamLayout layout = SeamLayout.of(level);
		Vec3 center = box.getCenter();
		return layout == null ? original.call(level, type, box) : SeamInteraction.acrossSeams(layout, center.x, center.z, box, area -> original.call(level, type, area));
	}

	// The sweep pushes along the look of the player, a direction of the player region.
	@WrapOperation(method = "doSweepAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;knockback(DDDLnet/minecraft/world/damagesource/DamageSource;F)V"))
	private void cubism$sweepKnockbackAcrossSeam(LivingEntity target, double power, double xd, double zd, DamageSource source, float damage, Operation<Void> original) {
		SeamLayout layout = SeamLayout.of(this.level());
		Vec3 direction = layout == null ? new Vec3(xd, 0.0, zd)
			: SeamInteraction.turnIntoRegionOf(layout, new Vec3(xd, 0.0, zd), this.getX(), this.getZ(), target.getX(), target.getZ());
		original.call(target, power, direction.x, direction.z, source, damage);
	}
}

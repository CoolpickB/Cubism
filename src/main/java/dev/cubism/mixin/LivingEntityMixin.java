package dev.cubism.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.gravity.GravityLock;
import dev.cubism.seam.SeamCrossing;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends Entity {
	/** Edge gravity starts only this far from the ground along the pull, in blocks. */
	@Unique private static final double EDGE_GRAVITY_GROUND_GAP = 3.0;
	/** Vertical push per tick of jump or sneak in zero pull, as in water. */
	@Unique private static final double SPACE_SWIM_RISE = 0.04;
	/** The face region where the entity last stood or flew, or -1 before the first air tick. */
	@Unique private int cubism$fallStartFace = -1;

	@Shadow protected boolean jumping;

	public LivingEntityMixin(EntityType<?> type, Level level) {
		super(type, level);
	}

	/** A mob sees a target across a seam along the ray that the seam rendering shows, through the blocks of both faces. */
	@Inject(
		method = "hasLineOfSight(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/ClipContext$Block;Lnet/minecraft/world/level/ClipContext$Fluid;D)Z",
		at = @At("HEAD"),
		cancellable = true
	)
	private void cubism$lineOfSightAcrossSeams(Entity target, ClipContext.Block block, ClipContext.Fluid fluid, double eyeY, CallbackInfoReturnable<Boolean> cir) {
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
	private @Nullable Vec3 cubism$sourceInOwnRegion(@Nullable Vec3 source) {
		SeamLayout layout = source == null ? null : SeamLayout.of(this.level());
		return layout == null ? source : SeamInteraction.intoRegionOf(layout, source, this.getX(), this.getZ());
	}

	// The extra knockback follows the look of the attacker, a direction of its own region.
	@WrapOperation(method = "causeExtraKnockback", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V"))
	private void cubism$extraKnockbackAcrossSeam(
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
	private void cubism$edgeGravity(Vec3 input, CallbackInfo ci) {
		SeamLayout layout = SeamLayout.of(this.level());
		if (layout == null) {
			return;
		}
		// In space the frame of the region is the cube frame, and the vanilla pull goes to the bottom of the UP face.
		// It is replaced with the pull of space, which is zero past the exosphere, so a body floats there.
		Vec3 space = SeamCrossing.spacePull(layout, this);
		if (space != null) {
			if (!this.isNoGravity() && !((LivingEntity) (Object) this).hasEffect(MobEffects.LEVITATION) && !((Object) this instanceof Player player && player.getAbilities().flying)) {
				double gravity = this.getEffectiveGravity();
				this.setDeltaMovement(this.getDeltaMovement().add(gravity * space.x * 0.91, gravity * (space.y + 1.0) * 0.98, gravity * space.z * 0.91));
			}
			// With no pull left a body has nothing to push against, so jump rises and sneak sinks, as when swimming. Up is
			// where the view shows up: away from the cube center, as the camera frame of the planet.
			// The push grows as the pull fades, so there is no switch between falling and swimming.
			double authority = 1.0 - Math.min(1.0, space.length());
			if (authority > 0.02 && (Object) this instanceof Player swimmer && !swimmer.getAbilities().flying && this.jumping != swimmer.isShiftKeyDown()) {
				Vec3 center = this.getBoundingBox().getCenter();
				Vec3 up = layout.cubeOffset(SeamLayout.SPACE, center.x, center.y, center.z);
				if (up.lengthSqr() > 1.0E-6) {
					this.setDeltaMovement(this.getDeltaMovement().add(up.normalize().scale(authority * (this.jumping ? SPACE_SWIM_RISE : -SPACE_SWIM_RISE))));
				}
			}
			return;
		}
		int face = SeamInteraction.regionFace(layout, this.getX(), this.getZ());
		if (this.onGround() || ((LivingEntity) (Object) this).hasEffect(MobEffects.LEVITATION) || GravityLock.locked(this)
			|| (Object) this instanceof Player player && player.getAbilities().flying || this.cubism$fallStartFace < 0) {
			this.cubism$fallStartFace = face;
			return;
		}
		if (face == this.cubism$fallStartFace) {
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

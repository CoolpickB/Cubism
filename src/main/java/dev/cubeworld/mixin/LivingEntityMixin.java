package dev.cubeworld.mixin;

import dev.cubeworld.CubeWorldMod;
import dev.cubeworld.gravity.GravityEntityAccess;
import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.worldgen.CubeChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends Entity {
	public LivingEntityMixin(EntityType<?> type, Level level) {
		super(type, level);
	}

	@Shadow protected abstract float getJumpPower();
	@Shadow public abstract double getAttributeValue(Holder<Attribute> attribute);
	@Shadow public abstract MobEffectInstance getEffect(Holder<MobEffect> effect);
	@Shadow public abstract double getEffectiveGravity();
	@Shadow public abstract boolean shouldDiscardFriction();
	@Shadow protected abstract Vec3 handleRelativeFrictionAndCalculateMovement(Vec3 input, float friction);

	@Unique
	private boolean cubeworld$isCubeLevel() {
		if (this.level() instanceof ServerLevel serverLevel) {
			return serverLevel.getChunkSource().getGenerator() instanceof CubeChunkGenerator;
		}
		return this.level().dimensionTypeRegistration().is(CubeWorldMod.CUBE_OVERWORLD_TYPE)
			|| (CubePlanetMath.isClientCubeActive() && this.level().dimension() == Level.OVERWORLD);
	}

	@Unique
	private int cubeworld$getCenterY() {
		if (this.level() instanceof ServerLevel serverLevel
			&& serverLevel.getChunkSource().getGenerator() instanceof CubeChunkGenerator cubeGen) {
			return cubeGen.centerY();
		}
		return CubePlanetMath.getClientCenterY();
	}

	@Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true)
	private void cubeworld$jumpAlongContinuousUp(CallbackInfo ci) {
		if (!this.cubeworld$isCubeLevel()) {
			return;
		}
		float jumpPower = this.getJumpPower();
		if (jumpPower <= 1.0E-5F) {
			ci.cancel();
			return;
		}

		Direction dir = ((GravityEntityAccess) this).cubeworld$getGravityDirection();
		double halfH = this.getBbHeight() * 0.5;
		Vec3 contUp = CubePlanetMath.getContinuousUpVector(
			this.getX() - dir.getStepX() * halfH,
			this.getY() - dir.getStepY() * halfH,
			this.getZ() - dir.getStepZ() * halfH,
			this.cubeworld$getCenterY()
		);

		Vec3 vel = this.getDeltaMovement();
		double currentAlongUp = vel.dot(contUp);
		Vec3 tangentialVel = vel.subtract(contUp.scale(currentAlongUp));
		Vec3 newVel = tangentialVel.add(contUp.scale(Math.max(jumpPower, currentAlongUp)));

		if (this.isSprinting()) {
			float rad = this.getYRot() * ((float) Math.PI / 180F);
			Vec3 localSprintBoost = new Vec3(-Math.sin(rad) * 0.2, 0.0, Math.cos(rad) * 0.2);
			newVel = newVel.add(CubePlanetMath.localToWorld(localSprintBoost, dir));
		}

		this.setDeltaMovement(newVel);
		this.needsSync = true;
		ci.cancel();
	}

	@Inject(method = "travelInAir", at = @At("HEAD"), cancellable = true)
	private void cubeworld$travelInAirOriented(Vec3 input, CallbackInfo ci) {
		if (!this.cubeworld$isCubeLevel()) {
			return;
		}
		Direction dir = ((GravityEntityAccess) this).cubeworld$getGravityDirection();

		BlockPos posBelow = this.getBlockPosBelowThatAffectsMyMovement();
		float blockFriction = this.onGround()
			? this.level().getBlockState(posBelow).getBlock().getFriction()
			: 1.0f;

		Vec3 afterMove = this.handleRelativeFrictionAndCalculateMovement(input, blockFriction);
		double g = this.getEffectiveGravity();

		MobEffectInstance levitation = this.getEffect(MobEffects.LEVITATION);
		boolean isFlyingPlayer = (Object) this instanceof Player player && player.getAbilities().flying;
		if (levitation != null && !isFlyingPlayer) {
			Vec3 localVel = CubePlanetMath.worldToLocal(afterMove, dir);
			double vy = localVel.y + (0.05 * (levitation.getAmplifier() + 1) - localVel.y) * 0.2;
			afterMove = CubePlanetMath.localToWorld(new Vec3(localVel.x, vy, localVel.z), dir);
		} else if (!isFlyingPlayer && !this.isNoGravity() && (!this.level().isClientSide() || this.level().hasChunkAt(posBelow))) {
			// Grounded: pull strictly along discrete face normal so standing near an edge never causes sliding.
			// Airborne: pull along the continuous closest-point gravity vector!
			if (this.onGround()) {
				afterMove = afterMove.add(dir.getStepX() * g, dir.getStepY() * g, dir.getStepZ() * g);
			} else {
				double halfH = this.getBbHeight() * 0.5;
				Vec3 contUp = CubePlanetMath.getContinuousUpVector(
					this.getX() - dir.getStepX() * halfH,
					this.getY() - dir.getStepY() * halfH,
					this.getZ() - dir.getStepZ() * halfH,
					this.cubeworld$getCenterY()
				);
				afterMove = afterMove.subtract(contUp.scale(g));
			}
		}

		if (this.shouldDiscardFriction()) {
			this.setDeltaMovement(afterMove);
		} else {
			Vec3 localVel = CubePlanetMath.worldToLocal(afterMove, dir);
			float horizDrag = blockFriction * 0.91f;
			float vertDrag = 0.98f;
			Vec3 draggedLocal = new Vec3(localVel.x * horizDrag, localVel.y * vertDrag, localVel.z * horizDrag);
			this.setDeltaMovement(CubePlanetMath.localToWorld(draggedLocal, dir));
		}

		ci.cancel();
	}
}

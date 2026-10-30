package dev.cubeworld.mixin;

import dev.cubeworld.CubeWorldMod;
import dev.cubeworld.gravity.GravityEntityAccess;
import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.worldgen.CubeChunkGenerator;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerMixin extends LivingEntity {
	@Unique
	private Vec3 cubeworld$preTravelVelocity = Vec3.ZERO;

	protected PlayerMixin(EntityType<? extends LivingEntity> type, Level level) {
		super(type, level);
	}

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

	@Inject(method = "travel", at = @At("HEAD"))
	private void cubeworld$capturePreTravelVelocity(Vec3 input, CallbackInfo ci) {
		this.cubeworld$preTravelVelocity = this.getDeltaMovement();
	}

	@Redirect(
		method = "travel",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/phys/Vec3;with(Lnet/minecraft/core/Direction$Axis;D)Lnet/minecraft/world/phys/Vec3;"
		)
	)
	private Vec3 cubeworld$dampFlyingVelocityAlongContinuousUp(Vec3 afterVel, Direction.Axis axis, double vanillaY) {
		if (!this.cubeworld$isCubeLevel()) {
			return afterVel.with(axis, vanillaY);
		}
		Direction dir = ((GravityEntityAccess) this).cubeworld$getGravityDirection();
		double halfH = this.getBbHeight() * 0.5;
		Vec3 contUp = CubePlanetMath.getContinuousUpVector(
			this.getX() - dir.getStepX() * halfH,
			this.getY() - dir.getStepY() * halfH,
			this.getZ() - dir.getStepZ() * halfH,
			this.cubeworld$getCenterY()
		);
		double preUp = this.cubeworld$preTravelVelocity.dot(contUp);
		double curUp = afterVel.dot(contUp);
		return afterVel.add(contUp.scale(preUp * 0.6 - curUp));
	}
}

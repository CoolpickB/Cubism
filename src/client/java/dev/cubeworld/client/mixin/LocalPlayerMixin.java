package dev.cubeworld.client.mixin;

import dev.cubeworld.CubeWorldMod;
import dev.cubeworld.gravity.GravityEntityAccess;
import dev.cubeworld.math.CubePlanetMath;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin extends LivingEntity {
	protected LocalPlayerMixin(EntityType<? extends LivingEntity> type, Level level) {
		super(type, level);
	}

	@Redirect(
		method = "aiStep",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/phys/Vec3;add(DDD)Lnet/minecraft/world/phys/Vec3;"
		)
	)
	private Vec3 cubeworld$orientFlyingVerticalImpulse(Vec3 velocity, double dx, double dy, double dz) {
		if (dx == 0.0 && dz == 0.0 && dy != 0.0) {
			boolean isCube = this.level().dimensionTypeRegistration().is(CubeWorldMod.CUBE_OVERWORLD_TYPE)
				|| (CubePlanetMath.isClientCubeActive() && this.level().dimension() == Level.OVERWORLD);
			if (isCube) {
				Direction dir = ((GravityEntityAccess) this).cubeworld$getGravityDirection();
				double halfH = this.getBbHeight() * 0.5;
				Vec3 contUp = CubePlanetMath.getContinuousUpVector(
					this.getX() - dir.getStepX() * halfH,
					this.getY() - dir.getStepY() * halfH,
					this.getZ() - dir.getStepZ() * halfH,
					CubePlanetMath.getClientCenterY()
				);
				return velocity.add(contUp.scale(dy));
			}
		}
		return velocity.add(dx, dy, dz);
	}
}

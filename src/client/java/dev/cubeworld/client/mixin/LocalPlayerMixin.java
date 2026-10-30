package dev.cubeworld.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.cubeworld.gravity.GravityEntityAccess;
import dev.cubeworld.math.CubePlanetMath;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin extends LivingEntity {
	@Unique private Vec3 cubeworld$positionBeforeMove = Vec3.ZERO;

	protected LocalPlayerMixin(EntityType<? extends LivingEntity> type, Level level) {
		super(type, level);
	}

	@Inject(method = "move", at = @At("HEAD"))
	private void cubeworld$rememberPosition(MoverType moverType, Vec3 delta, CallbackInfo ci) {
		this.cubeworld$positionBeforeMove = this.position();
	}

	// View bobbing and step sounds use the walked distance, which must be horizontal in the local frame.
	@ModifyExpressionValue(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;length(FF)F"))
	private float cubeworld$localWalkedDistance(float worldLength) {
		Direction frame = ((GravityEntityAccess) this).cubeworld$getFrame();
		if (frame == Direction.DOWN) {
			return worldLength;
		}
		Vec3 moved = CubePlanetMath.worldToLocal(this.position().subtract(this.cubeworld$positionBeforeMove), frame);
		return (float) moved.horizontalDistance();
	}

	// Auto-jump probes the world with Y-up boxes.
	@Inject(method = "canAutoJump", at = @At("HEAD"), cancellable = true)
	private void cubeworld$noAutoJumpWhenTurned(CallbackInfoReturnable<Boolean> cir) {
		if (((GravityEntityAccess) this).cubeworld$getFrame() != Direction.DOWN) {
			cir.setReturnValue(false);
		}
	}
}

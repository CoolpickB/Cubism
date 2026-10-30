package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.cubeworld.gravity.GravityEntityAccess;
import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The movement check treats world Y as the vertical axis: it ignores errors on Y and reads Y for jumps.
 * These handlers move both rules to the local vertical axis of the player.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Shadow public ServerPlayer player;
	@Shadow private double lastGoodX;
	@Shadow private double lastGoodY;
	@Shadow private double lastGoodZ;

	@ModifyVariable(method = "handlePlayerPositionChange", at = @At(value = "STORE", ordinal = 0), name = "movedUpwards")
	private boolean cubeworld$movedUpwardsLocal(boolean movedUpwards,
		@Local(argsOnly = true, ordinal = 0) double requestedX, @Local(argsOnly = true, ordinal = 1) double requestedY,
		@Local(argsOnly = true, ordinal = 2) double requestedZ) {
		Direction frame = ((GravityEntityAccess) this.player).cubeworld$getFrame();
		if (frame == Direction.DOWN) {
			return movedUpwards;
		}
		Vec3 moved = new Vec3(requestedX - this.lastGoodX, requestedY - this.lastGoodY, requestedZ - this.lastGoodZ);
		return moved.dot(CubePlanetMath.up(frame)) > 0.0;
	}

	@ModifyVariable(method = "handlePlayerPositionChange", at = @At(value = "STORE", ordinal = 1), name = "movedDist")
	private double cubeworld$errorWithoutLocalVertical(double movedDist,
		@Local(argsOnly = true, ordinal = 0) double requestedX, @Local(argsOnly = true, ordinal = 1) double requestedY,
		@Local(argsOnly = true, ordinal = 2) double requestedZ) {
		Direction frame = ((GravityEntityAccess) this.player).cubeworld$getFrame();
		if (frame == Direction.DOWN) {
			return movedDist;
		}
		Vec3 error = CubePlanetMath.worldToLocal(
			new Vec3(requestedX - this.player.getX(), requestedY - this.player.getY(), requestedZ - this.player.getZ()), frame
		);
		return error.x * error.x + error.z * error.z;
	}

	@Inject(method = "noBlocksAround", at = @At("HEAD"), cancellable = true)
	private void cubeworld$noBlocksAroundOriented(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		Direction frame = ((GravityEntityAccess) entity).cubeworld$getFrame();
		if (frame == Direction.DOWN) {
			return;
		}
		AABB box = entity.getBoundingBox().inflate(0.0625).expandTowards(CubePlanetMath.up(frame).scale(-0.55));
		cir.setReturnValue(entity.level().findBlocksIn(box).filterState(state -> state.isAir()).allMatched());
	}

	/**
	 * Near a diagonal, the blocks under a player can belong to the next face, where the storage holds only cleared
	 * air. Collision reads those blocks from their owner, so this check must count them too.
	 */
	@Inject(method = "noBlocksAround", at = @At("RETURN"), cancellable = true)
	private void cubeworld$blocksAcrossSeam(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && SeamLayout.of(entity.level()) != null) {
			AABB box = entity.getBoundingBox().inflate(0.0625).expandTowards(0.0, -0.55, 0.0);
			cir.setReturnValue(entity.level().noBlockCollision(entity, box));
		}
	}

	// The floating check reads this as the vertical movement of the packet.
	@ModifyVariable(method = "handlePlayerPositionChange", at = @At(value = "STORE", ordinal = 0), name = "oyDist")
	private double cubeworld$verticalMoveLocal(double oyDist,
		@Local(argsOnly = true, ordinal = 0) double requestedX, @Local(argsOnly = true, ordinal = 1) double requestedY,
		@Local(argsOnly = true, ordinal = 2) double requestedZ) {
		Direction frame = ((GravityEntityAccess) this.player).cubeworld$getFrame();
		if (frame == Direction.DOWN) {
			return oyDist;
		}
		Vec3 moved = new Vec3(requestedX - this.lastGoodX, requestedY - this.lastGoodY, requestedZ - this.lastGoodZ);
		return moved.dot(CubePlanetMath.up(frame));
	}
}

package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.cubeworld.gravity.GravityEntityAccess;
import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.seam.SeamCrossing;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * The movement check treats world Y as the vertical axis: it ignores errors on Y and reads Y for jumps.
 * These handlers move both rules to the local vertical axis of the player.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	/** A crossing on the client lands within this many blocks of where the server turns the last position, in blocks. */
	@Unique private static final double MAX_CROSSING_MOVE = 32.0;
	@Unique private static final Logger CUBEWORLD$LOGGER = LoggerFactory.getLogger("cubeworld");

	@Shadow public ServerPlayer player;
	@Shadow private double lastGoodX;
	@Shadow private double lastGoodY;
	@Shadow private double lastGoodZ;
	@Shadow private double firstGoodX;
	@Shadow private double firstGoodY;
	@Shadow private double firstGoodZ;

	/**
	 * A player crosses a diagonal on its own client. The first movement in the new face region makes the same crossing
	 * here, so the rest of the check sees a short move.
	 */
	@Inject(method = "handlePlayerPositionChange", at = @At("HEAD"))
	private void cubeworld$followClientCrossing(
		double requestedX, double requestedY, double requestedZ, float requestedYRot, float requestedXRot, boolean isOnGround,
		boolean horizontalCollision, CallbackInfo ci
	) {
		SeamLayout layout = SeamLayout.of(this.player.level());
		if (layout == null || this.player.isPassenger()) {
			return;
		}
		int face = SeamInteraction.regionFace(layout, this.player.getX(), this.player.getZ());
		int toFace = SeamInteraction.regionFace(layout, requestedX, requestedZ);
		if (face < 0 || toFace < 0 || face == toFace) {
			return;
		}
		SeamCrossing.Turned turned = SeamCrossing.turned(layout, this.player, toFace);
		if (turned.position().distanceToSqr(requestedX, requestedY, requestedZ) > MAX_CROSSING_MOVE * MAX_CROSSING_MOVE) {
			return;
		}
		Vec3 position = turned.position();
		this.player.absSnapTo(position.x, position.y, position.z, turned.yRot(), turned.xRot());
		this.player.setDeltaMovement(turned.velocity());
		this.firstGoodX = this.lastGoodX = position.x;
		this.firstGoodY = this.lastGoodY = position.y;
		this.firstGoodZ = this.lastGoodZ = position.z;
	}

	/** Logs every teleport of a player in a seam world, to find a silent setback at a diagonal. */
	@Inject(method = "teleport(Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;)V", at = @At("HEAD"))
	private void cubeworld$logTeleport(PositionMoveRotation destination, Set<Relative> relatives, CallbackInfo ci) {
		if (SeamLayout.of(this.player.level()) != null) {
			StackTraceElement[] stack = new Throwable().getStackTrace();
			CUBEWORLD$LOGGER.info("Teleport of {} from {} to {} by {}", this.player.getPlainTextName(), this.player.position(),
				destination.position(), stack.length > 2 ? stack[2] : "unknown");
		}
	}

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

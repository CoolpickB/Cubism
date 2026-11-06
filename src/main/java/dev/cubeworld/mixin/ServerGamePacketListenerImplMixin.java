package dev.cubeworld.mixin;

import dev.cubeworld.seam.SeamCrossing;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


/**
 * The movement check treats world Y as the vertical axis: it ignores errors on Y and reads Y for jumps.
 * These handlers move both rules to the local vertical axis of the player.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	/** A crossing on the client lands within this many blocks of where the server turns the last position, in blocks. */
	@Unique private static final double MAX_CROSSING_MOVE = 32.0;

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

	@Shadow private double vehicleFirstGoodX;
	@Shadow private double vehicleFirstGoodY;
	@Shadow private double vehicleFirstGoodZ;
	@Shadow private double vehicleLastGoodX;
	@Shadow private double vehicleLastGoodY;
	@Shadow private double vehicleLastGoodZ;

	/** The vehicle that a player steers crosses on that client too, and the server follows the same way. */
	@Inject(method = "handleMoveVehicle", at = @At("HEAD"))
	private void cubeworld$followClientVehicleCrossing(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
		SeamLayout layout = SeamLayout.of(this.player.level());
		Entity vehicle = this.player.getRootVehicle();
		if (layout == null || vehicle == this.player || SeamCrossing.ridden(vehicle) != this.player) {
			return;
		}
		Vec3 requested = packet.movingTo().position();
		int face = SeamInteraction.regionFace(layout, vehicle.getX(), vehicle.getZ());
		int toFace = SeamInteraction.regionFace(layout, requested.x, requested.z);
		if (face < 0 || toFace < 0 || face == toFace) {
			return;
		}
		SeamCrossing.Turned turned = SeamCrossing.turned(layout, vehicle, toFace);
		if (turned.position().distanceToSqr(requested) > MAX_CROSSING_MOVE * MAX_CROSSING_MOVE) {
			return;
		}
		Vec3 position = turned.position();
		vehicle.absSnapTo(position.x, position.y, position.z, turned.yRot(), turned.xRot());
		vehicle.setDeltaMovement(turned.velocity());
		this.vehicleFirstGoodX = this.vehicleLastGoodX = position.x;
		this.vehicleFirstGoodY = this.vehicleLastGoodY = position.y;
		this.vehicleFirstGoodZ = this.vehicleLastGoodZ = position.z;
		vehicle.positionRider(this.player);
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
}

package dev.cubeworld.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.client.LoadTraceClient;
import dev.cubeworld.debug.LoadTrace;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A move across a seam is too far to interpolate, so vanilla puts the old position on the new one. The entity then
 * stands still until the next tick, a skip of a frame or two. Here the old position goes through the same face
 * motion, and the entity moves on smoothly in the new region.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	/** A seam crossing moves the entity less than this far from where the face motion puts it, in blocks. */
	@Unique private static final double MAX_CROSSING_JUMP = 8.0;
	/** The carried old position, from the {@code setPos} call to the {@code setOldPosAndRot} call that follows it. */
	@Unique private static @Nullable Vec3 cubeworld$crossed;

	@Inject(method = "handleLogin", at = @At("TAIL"))
	private void cubeworld$traceLogin(CallbackInfo ci) {
		LoadTrace.event("client: login packet handled");
	}

	/** Vanilla runs the handler again on the main thread. The first run, on the network thread, does not reach the end. */
	@Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
	private void cubeworld$countChunk(CallbackInfo ci) {
		LoadTraceClient.CHUNK_PACKETS.incrementAndGet();
	}

	@WrapOperation(method = "setValuesFromPositionPacket", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setPos(Lnet/minecraft/world/phys/Vec3;)V"))
	private static void cubeworld$carryOldPosition(Entity entity, Vec3 pos, Operation<Void> original) {
		Vec3 before = entity.position();
		Vec3 oldBefore = entity.oldPosition();
		original.call(entity, pos);
		cubeworld$crossed = cubeworld$carried(entity, before, oldBefore);
	}

	@WrapOperation(method = "setValuesFromPositionPacket", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setOldPosAndRot(Lnet/minecraft/world/phys/Vec3;FF)V"))
	private static void cubeworld$setCarriedOldPosition(Entity entity, Vec3 oldPos, float yRot, float xRot, Operation<Void> original) {
		original.call(entity, cubeworld$crossed == null ? oldPos : cubeworld$crossed, yRot, xRot);
		cubeworld$crossed = null;
	}

	@WrapOperation(method = "handleEntityPositionSync", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;snapTo(Lnet/minecraft/world/phys/Vec3;FF)V"))
	private void cubeworld$carryOldPositionOnSync(Entity entity, Vec3 pos, float yRot, float xRot, Operation<Void> original) {
		Vec3 before = entity.position();
		Vec3 oldBefore = entity.oldPosition();
		original.call(entity, pos, yRot, xRot);
		Vec3 carried = cubeworld$carried(entity, before, oldBefore);
		if (carried != null) {
			entity.setOldPosAndRot(carried, yRot, xRot);
		}
	}

	/** The old position carried into the new region, or null when the move from {@code before} is no seam crossing. */
	@Unique
	private static @Nullable Vec3 cubeworld$carried(Entity entity, Vec3 before, Vec3 oldBefore) {
		SeamLayout layout = SeamLayout.of(entity.level());
		int[] m = layout == null ? null : SeamInteraction.regionMotion(layout, before.x, before.z, entity.getX(), entity.getZ());
		double maxSqr = MAX_CROSSING_JUMP * MAX_CROSSING_JUMP;
		// A new entity has no old position yet: it is at the origin.
		if (m == null || SeamInteraction.move(m, before).distanceToSqr(entity.position()) > maxSqr || oldBefore.distanceToSqr(before) > maxSqr) {
			return null;
		}
		return SeamInteraction.move(m, oldBefore);
	}
}

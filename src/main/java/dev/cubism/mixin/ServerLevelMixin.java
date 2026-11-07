package dev.cubism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubism.seam.CubeTime;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.ClockTimeMarker;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
	/** The generator of a cube level has its layout from the chunk map, which the level makes in its constructor. */
	@Inject(method = "<init>", at = @At("TAIL"))
	private void cubism$registerCube(CallbackInfo ci) {
		SeamLayout.registerServerLevel((ServerLevel) (Object) this);
	}

	/** An entity reads the darkness of its own face in its goals, such as a zombie that burns or a spider that turns calm. */
	@WrapMethod(method = "tickNonPassenger")
	private void cubism$tickAtLocalTime(Entity entity, Operation<Void> original) {
		CubeTime.at((ServerLevel) (Object) this, entity.getX(), entity.getY(), entity.getZ(), () -> original.call(entity));
	}

	@WrapMethod(method = "tickChunk")
	private void cubism$tickChunkAtLocalTime(LevelChunk chunk, int tickSpeed, Operation<Void> original) {
		CubeTime.at((ServerLevel) (Object) this, chunk.getPos().getMiddleBlockX(), chunk.getMaxY(), chunk.getPos().getMiddleBlockZ(), () -> original.call(chunk, tickSpeed));
	}

	/** Sleepers wake at the dawn of their own face. With sleepers on more than one face, the first one decides. */
	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/clock/ServerClockManager;moveToTimeMarker(Lnet/minecraft/core/Holder;Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/world/clock/ServerClockManager$MoveResult;"))
	private ServerClockManager.MoveResult cubism$wakeAtLocalDawn(
		ServerClockManager clocks, Holder<WorldClock> clock, ResourceKey<ClockTimeMarker> marker, Operation<ServerClockManager.MoveResult> original
	) {
		ServerLevel level = (ServerLevel) (Object) this;
		SeamLayout layout = SeamLayout.of(level);
		ServerPlayer sleeper = level.players().stream().filter(ServerPlayer::isSleeping).findFirst().orElse(null);
		if (layout == null || sleeper == null) {
			return original.call(clocks, clock, marker);
		}
		int ticks = CubeTime.ticksToMorning(layout, clocks.getInstance(clock).totalTicks(), sleeper.position());
		if (ticks == 0) {
			return ServerClockManager.MoveResult.NOT_MOVED;
		}
		clocks.addTicks(clock, ticks);
		return ServerClockManager.MoveResult.MOVED;
	}

	// Players see the crack of a block across a seam, so the distance check uses the player moved into the region of the block.
	@WrapOperation(method = "destroyBlockProgress", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getX()D"))
	private double cubism$crackViewerX(ServerPlayer player, Operation<Double> original, @Local(argsOnly = true) BlockPos pos) {
		return this.cubism$viewer(player, pos).x;
	}

	@WrapOperation(method = "destroyBlockProgress", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getY()D"))
	private double cubism$crackViewerY(ServerPlayer player, Operation<Double> original, @Local(argsOnly = true) BlockPos pos) {
		return this.cubism$viewer(player, pos).y;
	}

	@WrapOperation(method = "destroyBlockProgress", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getZ()D"))
	private double cubism$crackViewerZ(ServerPlayer player, Operation<Double> original, @Local(argsOnly = true) BlockPos pos) {
		return this.cubism$viewer(player, pos).z;
	}

	// Particles and explosions reach players across a seam in the same way.
	@WrapOperation(
		method = "sendParticles(Lnet/minecraft/server/level/ServerPlayer;ZDDDLnet/minecraft/network/protocol/Packet;)Z",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;blockPosition()Lnet/minecraft/core/BlockPos;")
	)
	private BlockPos cubism$particleViewer(
		ServerPlayer player, Operation<BlockPos> original, @Local(argsOnly = true, ordinal = 0) double x, @Local(argsOnly = true, ordinal = 2) double z
	) {
		return BlockPos.containing(this.cubism$viewer(player, x, z));
	}

	@WrapOperation(method = "explode", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;distanceToSqr(Lnet/minecraft/world/phys/Vec3;)D"))
	private double cubism$explosionViewer(ServerPlayer player, Vec3 center, Operation<Double> original) {
		return this.cubism$viewer(player, center.x, center.z).distanceToSqr(center);
	}

	private Vec3 cubism$viewer(ServerPlayer player, BlockPos pos) {
		return this.cubism$viewer(player, pos.getX() + 0.5, pos.getZ() + 0.5);
	}

	private Vec3 cubism$viewer(ServerPlayer player, double x, double z) {
		SeamLayout layout = SeamLayout.of((ServerLevel) (Object) this);
		return layout == null ? player.position() : SeamInteraction.intoRegionOf(layout, player.position(), x, z);
	}
}

package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubeworld.seam.CubeTime;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
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

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
	/** An entity reads the darkness of its own face in its goals, such as a zombie that burns or a spider that turns calm. */
	@WrapMethod(method = "tickNonPassenger")
	private void cubeworld$tickAtLocalTime(Entity entity, Operation<Void> original) {
		CubeTime.at((ServerLevel) (Object) this, entity.getX(), entity.getY(), entity.getZ(), () -> original.call(entity));
	}

	@WrapMethod(method = "tickChunk")
	private void cubeworld$tickChunkAtLocalTime(LevelChunk chunk, int tickSpeed, Operation<Void> original) {
		CubeTime.at((ServerLevel) (Object) this, chunk.getPos().getMiddleBlockX(), chunk.getMaxY(), chunk.getPos().getMiddleBlockZ(), () -> original.call(chunk, tickSpeed));
	}

	/** Sleepers wake at the dawn of their own face. With sleepers on more than one face, the first one decides. */
	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/clock/ServerClockManager;moveToTimeMarker(Lnet/minecraft/core/Holder;Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/world/clock/ServerClockManager$MoveResult;"))
	private ServerClockManager.MoveResult cubeworld$wakeAtLocalDawn(
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
	private double cubeworld$crackViewerX(ServerPlayer player, Operation<Double> original, @Local(argsOnly = true) BlockPos pos) {
		return this.cubeworld$viewer(player, pos).x;
	}

	@WrapOperation(method = "destroyBlockProgress", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getY()D"))
	private double cubeworld$crackViewerY(ServerPlayer player, Operation<Double> original, @Local(argsOnly = true) BlockPos pos) {
		return this.cubeworld$viewer(player, pos).y;
	}

	@WrapOperation(method = "destroyBlockProgress", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getZ()D"))
	private double cubeworld$crackViewerZ(ServerPlayer player, Operation<Double> original, @Local(argsOnly = true) BlockPos pos) {
		return this.cubeworld$viewer(player, pos).z;
	}

	private Vec3 cubeworld$viewer(ServerPlayer player, BlockPos pos) {
		SeamLayout layout = SeamLayout.of((ServerLevel) (Object) this);
		return layout == null ? player.position() : SeamInteraction.intoRegionOf(layout, player.position(), pos.getX() + 0.5, pos.getZ() + 0.5);
	}
}

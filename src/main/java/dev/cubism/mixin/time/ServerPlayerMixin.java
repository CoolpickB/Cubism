package dev.cubism.mixin.time;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.util.Either;
import dev.cubism.time.CubeTime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A player ticks from its connection, not with the other entities of the level, and goes to bed from a packet. Both
 * read the darkness of the local time of the player, for the bed rule and for waking at dawn.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
	@WrapMethod(method = "doTick")
	private void cubism$tickAtLocalTime(Operation<Void> original) {
		ServerPlayer player = (ServerPlayer) (Object) this;
		CubeTime.at(player.level(), player.getX(), player.getY(), player.getZ(), () -> original.call());
	}

	@WrapMethod(method = "startSleepInBed")
	private Either<Player.BedSleepingProblem, Unit> cubism$sleepAtLocalTime(
		AbstractBedBlock bed, BlockState state, BedRule rule, BlockPos pos, Operation<Either<Player.BedSleepingProblem, Unit>> original
	) {
		ServerPlayer player = (ServerPlayer) (Object) this;
		return CubeTime.at(player.level(), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, () -> original.call(bed, state, rule, pos));
	}
}

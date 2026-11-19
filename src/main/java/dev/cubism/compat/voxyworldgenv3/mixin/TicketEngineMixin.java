package dev.cubism.compat.voxyworldgenv3.mixin;

import com.ethan.voxyworldgenv3.engine.Target;
import com.ethan.voxyworldgenv3.engine.ticket.TicketEngine;
import com.ethan.voxyworldgenv3.ledger.Ledger;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.compat.voxyworldgenv3.CubeScan;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The ticket engine walks every face of a cube level. Its walk is a circle around the player in storage, so it reaches
 * the whole face rectangle, and the chunks outside the rectangle count as done, so that each one costs one step of the
 * scan budget and no generation.
 */
@Mixin(value = TicketEngine.class, remap = false)
public abstract class TicketEngineMixin {
	/** The level of the target that {@code dispatch} is walking for. {@code nextCandidate} gets only its private Dim. */
	@Unique
	private @Nullable ServerLevel cubism$level;

	@ModifyExpressionValue(method = "dispatch", at = @At(value = "INVOKE", target = "Lcom/ethan/voxyworldgenv3/engine/Target;level()Lnet/minecraft/server/level/ServerLevel;"))
	private ServerLevel cubism$walkLevel(ServerLevel level) {
		this.cubism$level = level;
		return level;
	}

	@ModifyExpressionValue(method = "nextCandidate", at = @At(value = "FIELD", target = "Lcom/ethan/voxyworldgenv3/config/ConfigData;radius:I"))
	private int cubism$walkRadius(int radius) {
		return CubeScan.radius(this.cubism$level, radius);
	}

	@WrapOperation(method = "nextCandidate", at = @At(value = "INVOKE", target = "Lcom/ethan/voxyworldgenv3/ledger/Ledger;contains(J)Z"))
	private boolean cubism$skipOutside(Ledger ledger, long chunk, Operation<Boolean> original) {
		return CubeScan.outside(this.cubism$level, ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) || original.call(ledger, chunk);
	}

	@ModifyExpressionValue(method = "countRemaining", at = @At(value = "FIELD", target = "Lcom/ethan/voxyworldgenv3/config/ConfigData;radius:I"))
	private int cubism$countRadius(int radius, Target target) {
		return CubeScan.radius(target.level(), radius);
	}
}

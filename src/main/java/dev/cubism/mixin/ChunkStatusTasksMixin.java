package dev.cubism.mixin;

import dev.cubism.debug.LoadTrace;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Counts the generation steps for the {@link LoadTrace}. */
@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusTasksMixin {
	@Inject(method = "buildTerrain", at = @At("HEAD"))
	private static void cubism$countTerrain(CallbackInfoReturnable<?> cir) {
		LoadTrace.TERRAIN.incrementAndGet();
	}

	@Inject(method = "generateFeatures", at = @At("HEAD"))
	private static void cubism$countFeatures(CallbackInfoReturnable<?> cir) {
		LoadTrace.FEATURES.incrementAndGet();
	}

	@Inject(method = "light", at = @At("HEAD"))
	private static void cubism$countLight(CallbackInfoReturnable<?> cir) {
		LoadTrace.LIGHT.incrementAndGet();
	}

	@Inject(method = "full", at = @At("HEAD"))
	private static void cubism$countFull(CallbackInfoReturnable<?> cir) {
		LoadTrace.FULL.incrementAndGet();
	}
}

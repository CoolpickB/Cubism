package dev.cubism.mixin;

import dev.cubism.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkGenerator;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Outside its wedge, a face region holds cleared air, but the server reads the water and ground of the owner face
 * there. Mobs spawned in that air, and the next tick moved to the owner face. The client showed them for that tick in
 * the cleared air, which is the sky past the edge. The owner face spawns its own mobs.
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
	@Inject(method = "isValidSpawnPostitionForType", at = @At("HEAD"), cancellable = true)
	private static void cubism$onlyInsideWedge(
		ServerLevel level, MobCategory category, StructureManager structures, ChunkGenerator generator, MobSpawnSettings.SpawnerData data,
		BlockPos.MutableBlockPos pos, double nearestPlayerDistanceSqr, CallbackInfoReturnable<Boolean> cir
	) {
		SeamLayout layout = SeamLayout.of(level);
		if (layout != null && layout.inRegion(pos.getX(), pos.getZ()) && !layout.insideWedge(pos.getX(), pos.getY(), pos.getZ())) {
			cir.setReturnValue(false);
		}
	}

	/** Mobs placed while a chunk generates take the same rule. */
	@WrapOperation(method = "spawnMobsForChunkGeneration", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/SpawnPlacements;isSpawnPositionOk(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"))
	private static boolean cubism$generatedOnlyInsideWedge(EntityType<?> type, LevelReader reader, BlockPos pos, Operation<Boolean> original, @Local(argsOnly = true) ServerLevelAccessor level) {
		SeamLayout layout = SeamLayout.of(level.getLevel());
		if (layout != null && layout.inRegion(pos.getX(), pos.getZ()) && !layout.insideWedge(pos.getX(), pos.getY(), pos.getZ())) {
			return false;
		}
		return original.call(type, reader, pos);
	}
}

package dev.cubeworld.mixin;

import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkGenerator;
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
	private static void cubeworld$onlyInsideWedge(
		ServerLevel level, MobCategory category, StructureManager structures, ChunkGenerator generator, MobSpawnSettings.SpawnerData data,
		BlockPos.MutableBlockPos pos, double nearestPlayerDistanceSqr, CallbackInfoReturnable<Boolean> cir
	) {
		SeamLayout layout = SeamLayout.of(level);
		if (layout != null && layout.inRegion(pos.getX(), pos.getZ()) && !layout.insideWedge(pos.getX(), pos.getY(), pos.getZ())) {
			cir.setReturnValue(false);
		}
	}
}

package dev.cubism.mixin;

import dev.cubism.worldgen.CubePregen;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** See {@link CubePregen#unsyncedWrites}. */
@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin {
	@ModifyArg(method = "getRegionFile", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/storage/RegionFile;<init>(Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Ljava/nio/file/Path;Ljava/nio/file/Path;Z)V"), index = 3)
	private boolean cubism$skipSyncDuringPregen(boolean sync) {
		return sync && !CubePregen.unsyncedWrites;
	}
}

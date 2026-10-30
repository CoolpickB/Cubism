package dev.cubeworld.mixin;

import dev.cubeworld.CubeWorldMod;
import dev.cubeworld.worldgen.CubeChunkGenerator;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(WorldPresets.class)
public class WorldPresetsMixin {
	@Inject(method = "fromSettings", at = @At("HEAD"), cancellable = true)
	private static void cubeworld$fromSettings(
		WorldDimensions dimensions,
		CallbackInfoReturnable<Optional<ResourceKey<WorldPreset>>> cir
	) {
		Optional<LevelStem> overworld = dimensions.get(LevelStem.OVERWORLD);
		if (overworld.isPresent() && overworld.get().generator() instanceof CubeChunkGenerator) {
			cir.setReturnValue(Optional.of(CubeWorldMod.CUBE_PRESET));
		}
	}
}

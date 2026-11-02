package dev.cubeworld.mixin;

import dev.cubeworld.CubeWorldMod;
import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.worldgen.CubeDensityFunctions;
import dev.cubeworld.worldgen.CubeSettings;
import dev.cubeworld.worldgen.SeamLayout;
import dev.cubeworld.worldgen.SeamRandomState;
import dev.cubeworld.worldgen.FoldedStarts;
import dev.cubeworld.worldgen.StructureMargins;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@link net.minecraft.server.level.ChunkMap} calls this right after it makes the RandomState of a level. */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {
	@Inject(method = "createState", at = @At("HEAD"))
	private void cubeworld$enableSeams(
		HolderLookup<StructureSet> structureSets,
		RandomState randomState,
		long legacyLevelSeed,
		CallbackInfoReturnable<ChunkGeneratorStructureState> cir
	) {
		if ((Object) this instanceof NoiseBasedChunkGenerator generator && generator.generatorSettings().is(CubeWorldMod.SEAM_SETTINGS)) {
			CubeSettings settings = CubeSettings.of(generator);
			CubePlanetMath.setEdgeRounding(settings.edgeRounding());
			SeamLayout layout = new SeamLayout(settings.diameterChunks());
			SeamLayout.setActive(layout);
			((SeamRandomState) (Object) randomState).cubeworld$enableSeams(layout);
			if (settings.shiftsClimate()) {
				RandomStateAccessor router = (RandomStateAccessor) (Object) randomState;
				router.cubeworld$setRouter(CubeDensityFunctions.withClimateShift(router.cubeworld$getRouter(), settings.temperature(), settings.humidity()));
			}
		}
	}

	@Inject(method = "createState", at = @At("RETURN"))
	private void cubeworld$registerMargins(
		HolderLookup<StructureSet> structureSets,
		RandomState randomState,
		long legacyLevelSeed,
		CallbackInfoReturnable<ChunkGeneratorStructureState> cir
	) {
		if ((Object) this instanceof NoiseBasedChunkGenerator generator && generator.generatorSettings().is(CubeWorldMod.SEAM_SETTINGS)) {
			StructureMargins.register(cir.getReturnValue());
		}
	}

	/** After the starts of a chunk, the starts of other faces that fold over an edge into it. */
	@Inject(method = "createStructures", at = @At("TAIL"))
	private void cubeworld$foldStarts(
		RegistryAccess registries,
		ChunkGeneratorStructureState state,
		StructureManager structureManager,
		ChunkAccess centerChunk,
		StructureTemplateManager templates,
		ResourceKey<Level> level,
		CallbackInfo ci
	) {
		if ((Object) this instanceof NoiseBasedChunkGenerator generator && generator.generatorSettings().is(CubeWorldMod.SEAM_SETTINGS)) {
			FoldedStarts.addTo(generator, registries, state, structureManager, centerChunk, templates, level);
		}
	}
}

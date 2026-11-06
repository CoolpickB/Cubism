package dev.cubism.mixin;

import dev.cubism.CubismMod;
import dev.cubism.math.CubePlanetMath;
import dev.cubism.worldgen.CubeDensityFunctions;
import dev.cubism.worldgen.CubeSettings;
import dev.cubism.worldgen.SeamLayout;
import dev.cubism.worldgen.SeamRandomState;
import dev.cubism.worldgen.FoldedStarts;
import dev.cubism.worldgen.StructureMargins;
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
	private void cubism$enableSeams(
		HolderLookup<StructureSet> structureSets,
		RandomState randomState,
		long legacyLevelSeed,
		CallbackInfoReturnable<ChunkGeneratorStructureState> cir
	) {
		if ((Object) this instanceof NoiseBasedChunkGenerator generator && generator.generatorSettings().is(CubismMod.SEAM_SETTINGS)) {
			CubeSettings settings = CubeSettings.of(generator);
			CubePlanetMath.setEdgeRounding(settings.edgeRounding());
			SeamLayout layout = new SeamLayout(settings.diameterChunks());
			SeamLayout.setActive(layout);
			((SeamRandomState) (Object) randomState).cubism$enableSeams(layout);
			if (settings.shiftsClimate()) {
				RandomStateAccessor router = (RandomStateAccessor) (Object) randomState;
				router.cubism$setRouter(CubeDensityFunctions.withClimateShift(router.cubism$getRouter(), settings.temperature(), settings.humidity()));
			}
		}
	}

	@Inject(method = "createState", at = @At("RETURN"))
	private void cubism$registerMargins(
		HolderLookup<StructureSet> structureSets,
		RandomState randomState,
		long legacyLevelSeed,
		CallbackInfoReturnable<ChunkGeneratorStructureState> cir
	) {
		if ((Object) this instanceof NoiseBasedChunkGenerator generator && generator.generatorSettings().is(CubismMod.SEAM_SETTINGS)) {
			StructureMargins.register(cir.getReturnValue());
		}
	}

	/** After the starts of a chunk, the starts of other faces that fold over an edge into it. */
	@Inject(method = "createStructures", at = @At("TAIL"))
	private void cubism$foldStarts(
		RegistryAccess registries,
		ChunkGeneratorStructureState state,
		StructureManager structureManager,
		ChunkAccess centerChunk,
		StructureTemplateManager templates,
		ResourceKey<Level> level,
		CallbackInfo ci
	) {
		if ((Object) this instanceof NoiseBasedChunkGenerator generator && generator.generatorSettings().is(CubismMod.SEAM_SETTINGS)) {
			FoldedStarts.addTo(generator, registries, state, structureManager, centerChunk, templates, level);
		}
	}
}

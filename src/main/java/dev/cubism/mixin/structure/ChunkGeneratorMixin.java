package dev.cubism.mixin.structure;

import dev.cubism.CubismMod;
import dev.cubism.cube.CubePlanetMath;
import dev.cubism.cube.SeamLayout;
import dev.cubism.mixin.worldgen.RandomStateAccessor;
import dev.cubism.worldgen.CubeDensityFunctions;
import dev.cubism.worldgen.CubeSettings;
import dev.cubism.worldgen.SeamRandomState;
import dev.cubism.worldgen.structure.FoldedStarts;
import dev.cubism.worldgen.structure.StructureMargins;
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
		if ((Object) this instanceof NoiseBasedChunkGenerator generator && SeamLayout.isNether(generator)) {
			// The server makes the overworld first. A cube Nether beside a world without a cube gets the smallest cube.
			SeamLayout overworld = SeamLayout.ofServerLevel(Level.OVERWORLD);
			SeamLayout layout = SeamLayout.nether(overworld != null ? overworld : new SeamLayout(CubeSettings.DEFAULT.diameterChunks()));
			((CubeSettings.Holder) (Object) generator).cubism$setLayout(layout);
			((SeamRandomState) (Object) randomState).cubism$enableSeams(layout);
		} else if ((Object) this instanceof NoiseBasedChunkGenerator generator && generator.generatorSettings().is(CubismMod.SEAM_SETTINGS)) {
			CubeSettings settings = CubeSettings.of(generator);
			CubePlanetMath.setEdgeRounding(settings.edgeRounding());
			SeamLayout layout = new SeamLayout(settings.diameterChunks());
			((CubeSettings.Holder) (Object) generator).cubism$setLayout(layout);
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
		if ((Object) this instanceof NoiseBasedChunkGenerator generator && SeamLayout.isSeam(generator)) {
			StructureMargins.register(cir.getReturnValue(), SeamLayout.of(generator));
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
		if ((Object) this instanceof NoiseBasedChunkGenerator generator && SeamLayout.isSeam(generator)) {
			FoldedStarts.addTo(generator, registries, state, structureManager, centerChunk, templates, level);
		}
	}
}

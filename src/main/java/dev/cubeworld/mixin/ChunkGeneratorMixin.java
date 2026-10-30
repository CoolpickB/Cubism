package dev.cubeworld.mixin;

import dev.cubeworld.CubeWorldMod;
import dev.cubeworld.worldgen.SeamLayout;
import dev.cubeworld.worldgen.SeamRandomState;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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
			SeamLayout layout = new SeamLayout(CubeWorldMod.SEAM_DIAMETER_CHUNKS);
			SeamLayout.setActive(layout);
			((SeamRandomState) (Object) randomState).cubeworld$enableSeams(layout);
		}
	}
}

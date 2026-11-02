package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubeworld.CubeWorldMod;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The ancient city starts where its biome is. Vanilla puts the start at one height, and the deep dark is there. In a cube
 * world the deep dark begins much lower, so the city would find its biome almost nowhere. The start moves down to the
 * first height where the biome fits, and a city whose biome is nowhere in the column does not generate.
 */
@Mixin(JigsawStructure.class)
public abstract class JigsawStructureMixin {
	/** Room for the center piece below its start: it reaches 24 blocks down, and the lowest 8 are kept free. */
	private static final int FLOOR_MARGIN = 32;
	private static final int STEP = 4;

	@WrapOperation(
		method = "findGenerationPoint",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/heightproviders/HeightProvider;sample(Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/WorldGenerationContext;)I")
	)
	private int cubeworld$startInBiome(
		HeightProvider provider, RandomSource random, WorldGenerationContext heights, Operation<Integer> original,
		@Local(argsOnly = true) Structure.GenerationContext context
	) {
		int height = original.call(provider, random, heights);
		if (SeamLayout.active() == null || !(context.chunkGenerator() instanceof NoiseBasedChunkGenerator generator)
			|| !generator.generatorSettings().is(CubeWorldMod.SEAM_SETTINGS)
			|| !context.registryAccess().lookupOrThrow(Registries.STRUCTURE).getResourceKey((Structure) (Object) this).filter(BuiltinStructures.ANCIENT_CITY::equals).isPresent()) {
			return height;
		}
		int x = QuartPos.fromBlock(context.chunkPos().getMinBlockX());
		int z = QuartPos.fromBlock(context.chunkPos().getMinBlockZ());
		for (int y = height; y >= context.heightAccessor().getMinY() + FLOOR_MARGIN; y -= STEP) {
			if (context.validBiome().test(context.biomeResolver().getNoiseBiome(x, QuartPos.fromBlock(y), z))) {
				return y;
			}
		}
		return height;
	}
}

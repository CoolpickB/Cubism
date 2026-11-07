package dev.cubism.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.cubism.CubismMod;
import dev.cubism.worldgen.SeamLayout;
import dev.cubism.worldgen.StructureMargins;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructureType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * A face region is wider than its wedge. A structure that starts in the region outside the wedge finds no ground
 * there and would generate in space that belongs to no face, so it does not generate.
 */
@Mixin(Structure.class)
public abstract class StructureMixin {
	@ModifyReturnValue(method = "findValidGenerationPoint", at = @At("RETURN"))
	private Optional<Structure.GenerationStub> cubism$startInsideWedge(Optional<Structure.GenerationStub> stub, Structure.GenerationContext context) {
		SeamLayout layout = SeamLayout.of(context.chunkGenerator());
		if (stub.isEmpty() || layout == null) {
			return stub;
		}
		BlockPos start = stub.get().position();
		// A mineshaft is lifted into its wedge when it is generated (see StructureMargins.settle).
		if (((Structure) (Object) this).type() == StructureType.MINESHAFT) {
			return stub;
		}
		return layout.insideWedge(start.getX(), start.getY(), start.getZ()) ? stub : Optional.empty();
	}

	/**
	 * The start chunk is squeezed inward by a margin (see {@link StructureMargins}), and this is the last check: a
	 * structure that does not fit its face and wedge tries again up to {@link StructureMargins#MAX_INWARD} chunks further
	 * inward, and does not generate when no try fits. An ancient city whose center is not whole in its wedge does not
	 * generate. A mineshaft is lifted into its wedge.
	 */
	@WrapMethod(method = "generate")
	private StructureStart cubism$startFits(
		Holder<Structure> selected, ResourceKey<Level> dimension, RegistryAccess registryAccess, ChunkGenerator chunkGenerator,
		BiomeSource biomeSource, Climate.Sampler climateSampler, RandomState randomState, StructureTemplateManager templates, long seed,
		ChunkPos chunk, int references, LevelHeightAccessor heightAccessor, Predicate<Holder<Biome>> validBiome, Operation<StructureStart> original
	) {
		StructureStart start = original.call(
			selected, dimension, registryAccess, chunkGenerator, biomeSource, climateSampler, randomState, templates, seed, chunk, references, heightAccessor, validBiome);
		SeamLayout layout = SeamLayout.of(chunkGenerator);
		if (!start.isValid() || layout == null) {
			return start;
		}
		if (StructureMargins.settle(layout, selected, start)) {
			return start;
		}
		if (StructureMargins.of(selected) == StructureMargins.NONE) {
			return StructureStart.INVALID_START;
		}
		BoundingBox box = start.getBoundingBox();
		for (int steps = 1; steps <= StructureMargins.MAX_INWARD; steps++) {
			ChunkPos moved = StructureMargins.inward(layout, box, chunk, steps);
			if (moved.equals(chunk)) {
				break;
			}
			StructureStart retry = original.call(
				selected, dimension, registryAccess, chunkGenerator, biomeSource, climateSampler, randomState, templates, seed, moved, references, heightAccessor, validBiome);
			if (retry.isValid() && StructureMargins.settle(layout, selected, retry)) {
				// The start stays in the chunk of its placement, which is where vanilla looks for it.
				return new StructureStart(selected.value(), chunk, references, new PiecesContainer(retry.getPieces()));
			}
		}
		return StructureStart.INVALID_START;
	}
}

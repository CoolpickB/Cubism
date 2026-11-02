package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubeworld.CubeWorldMod;
import dev.cubeworld.worldgen.SeamLayout;
import dev.cubeworld.worldgen.StructureMargins;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructureType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * A face region is wider than its wedge. A structure that starts in the region outside the wedge finds no ground
 * there and would generate in space that belongs to no face, so it does not generate.
 */
@Mixin(Structure.class)
public abstract class StructureMixin {
	@ModifyReturnValue(method = "findValidGenerationPoint", at = @At("RETURN"))
	private Optional<Structure.GenerationStub> cubeworld$startInsideWedge(Optional<Structure.GenerationStub> stub, Structure.GenerationContext context) {
		SeamLayout layout = SeamLayout.active();
		if (stub.isEmpty() || layout == null || !(context.chunkGenerator() instanceof NoiseBasedChunkGenerator generator)
			|| !generator.generatorSettings().is(CubeWorldMod.SEAM_SETTINGS)) {
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
	 * The start chunk moves inward by a margin (see {@link StructureMargins}), and this is the last check: a structure
	 * that is larger than its margin, or an ancient city whose center is not whole in its wedge, does not generate. A
	 * mineshaft is lifted into its wedge.
	 */
	@ModifyReturnValue(method = "generate", at = @At("RETURN"))
	private StructureStart cubeworld$startFits(
		StructureStart start, @Local(argsOnly = true) Holder<Structure> selected, @Local(argsOnly = true) ChunkGenerator chunkGenerator
	) {
		SeamLayout layout = SeamLayout.active();
		if (!start.isValid() || layout == null || !(chunkGenerator instanceof NoiseBasedChunkGenerator generator)
			|| !generator.generatorSettings().is(CubeWorldMod.SEAM_SETTINGS)) {
			return start;
		}
		return StructureMargins.settle(layout, selected, start) ? start : StructureStart.INVALID_START;
	}
}

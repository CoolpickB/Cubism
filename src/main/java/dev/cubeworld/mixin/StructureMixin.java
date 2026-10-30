package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.cubeworld.CubeWorldMod;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
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
		return layout.insideWedge(start.getX(), start.getY(), start.getZ()) ? stub : Optional.empty();
	}
}

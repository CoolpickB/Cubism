package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.cubeworld.worldgen.SeamLayout;
import dev.cubeworld.worldgen.StructureMargins;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A structure that cannot cross an edge starts at least a margin from it (see {@link StructureMargins}). The start
 * chunk of a cell moves inward when the cell of vanilla puts it nearer to an edge, and the chunk that a cell picks is
 * the chunk that generates, so a chunk is a placement chunk when it is the moved chunk of a cell near it.
 */
@Mixin(RandomSpreadStructurePlacement.class)
public abstract class RandomSpreadStructurePlacementMixin {
	@ModifyReturnValue(method = "getPotentialStructureChunk", at = @At("RETURN"))
	private ChunkPos cubeworld$moveInward(ChunkPos chunk) {
		SeamLayout layout = StructureMargins.layout();
		int margin = layout == null ? StructureMargins.NONE : StructureMargins.of((RandomSpreadStructurePlacement) (Object) this);
		return margin == StructureMargins.NONE ? chunk : StructureMargins.clamp(layout, margin, chunk);
	}

	@WrapMethod(method = "isPlacementChunk")
	private boolean cubeworld$isMovedChunk(ChunkGeneratorStructureState state, int sourceX, int sourceZ, Operation<Boolean> original) {
		if (!StructureMargins.isCube(state)) {
			StructureMargins.enterOtherGenerator();
			try {
				return original.call(state, sourceX, sourceZ);
			} finally {
				StructureMargins.leaveOtherGenerator();
			}
		}
		SeamLayout layout = StructureMargins.layout();
		RandomSpreadStructurePlacement placement = (RandomSpreadStructurePlacement) (Object) this;
		int margin = layout == null ? StructureMargins.NONE : StructureMargins.of(placement);
		if (margin == StructureMargins.NONE) {
			return original.call(state, sourceX, sourceZ);
		}
		int spacing = placement.spacing();
		int cellX = Math.floorDiv(sourceX, spacing);
		int cellZ = Math.floorDiv(sourceZ, spacing);
		int reach = Math.ceilDiv(margin / 16 + 2, spacing);
		for (int dx = -reach; dx <= reach; dx++) {
			for (int dz = -reach; dz <= reach; dz++) {
				ChunkPos chunk = placement.getPotentialStructureChunk(state.getLevelSeed(), (cellX + dx) * spacing, (cellZ + dz) * spacing);
				if (chunk.x() == sourceX && chunk.z() == sourceZ) {
					return true;
				}
			}
		}
		return false;
	}
}

package dev.cubism.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubism.worldgen.SeamLayout;
import dev.cubism.worldgen.StrongholdPositions;
import dev.cubism.worldgen.StructureMargins;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** The strongholds of a cube world are one on each of a few faces (see {@link StrongholdPositions}). The eye of ender reads the same positions. */
@Mixin(ChunkGeneratorStructureState.class)
public abstract class ChunkGeneratorStructureStateMixin {
	@ModifyReturnValue(method = "generateRingPositions", at = @At("RETURN"))
	private CompletableFuture<List<ChunkPos>> cubism$onePerFace(
		CompletableFuture<List<ChunkPos>> rings, @Local(argsOnly = true) Holder<StructureSet> structureSet
	) {
		SeamLayout layout = StructureMargins.layout();
		ChunkGeneratorStructureState state = (ChunkGeneratorStructureState) (Object) this;
		if (layout == null || !structureSet.is(BuiltinStructureSets.STRONGHOLDS) || !StructureMargins.isCube(state)) {
			return rings;
		}
		return CompletableFuture.completedFuture(StrongholdPositions.positions(layout, state.getLevelSeed()));
	}
}

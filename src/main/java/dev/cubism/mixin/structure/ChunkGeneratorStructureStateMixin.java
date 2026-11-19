package dev.cubism.mixin.structure;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubism.worldgen.structure.NetherComplexes;
import dev.cubism.cube.SeamLayout;
import dev.cubism.worldgen.structure.StrongholdPositions;
import dev.cubism.worldgen.structure.StructureMargins;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The strongholds of a cube world are one on each of a few faces (see {@link StrongholdPositions}). The eye of ender reads
 * the same positions. Fortresses and bastions of a cube Nether are placed the same way.
 */
@Mixin(ChunkGeneratorStructureState.class)
public abstract class ChunkGeneratorStructureStateMixin {
	/** Made once, so that each split set keeps one placement, which is the key of its ring positions. */
	@Shadow
	@Final
	private BiomeSource biomeSource;

	@Unique
	private volatile @Nullable List<Holder<StructureSet>> cubism$netherSets;

	@ModifyReturnValue(method = "generateRingPositions", at = @At("RETURN"))
	private CompletableFuture<List<ChunkPos>> cubism$onePerFace(
		CompletableFuture<List<ChunkPos>> rings, @Local(argsOnly = true) Holder<StructureSet> structureSet
	) {
		ChunkGeneratorStructureState state = (ChunkGeneratorStructureState) (Object) this;
		SeamLayout layout = StructureMargins.layout(state);
		if (layout != null && layout.nether && NetherComplexes.isComplex(structureSet.value())) {
			return CompletableFuture.completedFuture(
				NetherComplexes.positions(layout, state.getLevelSeed(), structureSet.value(), this.biomeSource.createResolver(
					state.randomState().createClimateSampler(SamplerContext.builder().enableCaches().build()))));
		}
		if (layout == null || !structureSet.is(BuiltinStructureSets.STRONGHOLDS)) {
			return rings;
		}
		return CompletableFuture.completedFuture(StrongholdPositions.positions(layout, state.getLevelSeed()));
	}

	/** The structure sets of a cube Nether, with fortresses and bastions placed per face, see {@link NetherComplexes}. */
	@ModifyReturnValue(method = "possibleStructureSets", at = @At("RETURN"))
	private List<Holder<StructureSet>> cubism$netherComplexes(List<Holder<StructureSet>> sets) {
		List<Holder<StructureSet>> split = this.cubism$netherSets;
		if (split == null) {
			SeamLayout layout = StructureMargins.layout((ChunkGeneratorStructureState) (Object) this);
			if (layout == null || !layout.nether) {
				return sets;
			}
			split = NetherComplexes.split(sets);
			this.cubism$netherSets = split;
		}
		return split;
	}
}

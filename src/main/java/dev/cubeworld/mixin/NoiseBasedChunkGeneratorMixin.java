package dev.cubeworld.mixin;

import com.google.common.base.Suppliers;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.CubeWorldMod;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.SpawnTargetPoint;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Outside its wedge, a face region is first filled with the real cube terrain, so that surface rules, aquifers and
 * carvers near the wedge wall see the same neighbors as in the cube. When the terrain step is done, that space is
 * cleared, because it belongs to other faces.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
	@Shadow
	@Final
	@Mutable
	private Supplier<Aquifer.FluidPicker> globalFluidPicker;

	/**
	 * The vanilla sea is flat in each face frame. Next to an edge the rounded ground sinks under it, and the flat sea
	 * stops at the diagonal as a wall of water. In a seam world, the sea level of a column follows the rounded cube.
	 * The settings and the layout are known only when the first chunk generates, so the choice is made then.
	 */
	@Inject(method = "<init>", at = @At("TAIL"))
	private void cubeworld$roundedSea(CallbackInfo ci) {
		Supplier<Aquifer.FluidPicker> vanilla = this.globalFluidPicker;
		this.globalFluidPicker = Suppliers.memoize(() -> {
			Aquifer.FluidPicker picker = vanilla.get();
			NoiseBasedChunkGenerator generator = (NoiseBasedChunkGenerator) (Object) this;
			SeamLayout layout = SeamLayout.active();
			if (layout == null || !generator.generatorSettings().is(CubeWorldMod.SEAM_SETTINGS)) {
				return picker;
			}
			int seaLevel = generator.generatorSettings().value().seaLevel();
			BlockState fluid = generator.generatorSettings().value().defaultFluid();
			return (x, y, z) -> {
				Aquifer.FluidStatus status = picker.computeFluid(x, y, z);
				return status.fluidType().is(Blocks.LAVA) ? status : new Aquifer.FluidStatus(layout.seaLevelAt(x, z, seaLevel), fluid);
			};
		});
	}

	/**
	 * Vanilla searches up to 2048 blocks from the origin for land, which leads into other face regions and the space
	 * between wedges. Here the search stays near the center of the UP face, so that the player spawns on top of the
	 * cube, away from the mountain rims of the edges. The fitness is the vanilla one.
	 */
	@WrapOperation(
		method = "getOrigin",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseSpawnFinder;findSpawnPosition(Ljava/util/List;Lnet/minecraft/world/level/levelgen/densityfunction/DensitySamplerSet;)Lnet/minecraft/core/BlockPos;")
	)
	private BlockPos cubeworld$spawnOnTopFace(List<SpawnTargetPoint> targets, DensitySamplerSet samplers, Operation<BlockPos> original) {
		SeamLayout layout = SeamLayout.active();
		if (layout == null || !((NoiseBasedChunkGenerator) (Object) this).generatorSettings().is(CubeWorldMod.SEAM_SETTINGS)) {
			return original.call(targets, samplers);
		}
		int limit = layout.radius / 2;
		BlockPos best = BlockPos.ZERO;
		long bestFitness = Long.MAX_VALUE;
		for (int x = -limit; x <= limit; x += 16) {
			for (int z = -limit; z <= limit; z += 16) {
				long fitness = Long.MAX_VALUE;
				for (SpawnTargetPoint target : targets) {
					fitness = Math.min(fitness, target.sampleFitness(samplers, x, 0, z));
				}
				fitness = fitness * 2048L * 2048L + (long) x * x + (long) z * z;
				if (fitness < bestFitness) {
					bestFitness = fitness;
					best = new BlockPos(x, 0, z);
				}
			}
		}
		return best;
	}

	/**
	 * Structures take their height from this column of the terrain density, before the space outside the wedge is
	 * cleared. Past an edge that space holds the next face, which stands as a wall up to the top of the cube, and a
	 * village placed on it floats in the sky of that face. The column reads air there, as the generated chunk does.
	 */
	@WrapOperation(
		method = "iterateNoiseColumn",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/Aquifer;computeSubstance(IIID)Lnet/minecraft/world/level/block/state/BlockState;")
	)
	private @Nullable BlockState cubeworld$airOutsideWedge(Aquifer aquifer, int x, int y, int z, double density, Operation<BlockState> original) {
		SeamLayout layout = SeamLayout.active();
		if (layout != null && ((NoiseBasedChunkGenerator) (Object) this).generatorSettings().is(CubeWorldMod.SEAM_SETTINGS)
			&& !layout.insideWedge(x, y, z)) {
			return Blocks.AIR.defaultBlockState();
		}
		return original.call(aquifer, x, y, z, density);
	}

	@ModifyReturnValue(method = "buildTerrain", at = @At("RETURN"))
	private CompletableFuture<ChunkAccess> cubeworld$clearOutsideWedge(CompletableFuture<ChunkAccess> future) {
		SeamLayout layout = SeamLayout.active();
		if (layout == null || !((NoiseBasedChunkGenerator) (Object) this).generatorSettings().is(CubeWorldMod.SEAM_SETTINGS)) {
			return future;
		}
		return future.thenApply(chunk -> {
			clearOutsideWedge(chunk, layout);
			return chunk;
		});
	}

	private static void clearOutsideWedge(ChunkAccess chunk, SeamLayout layout) {
		BlockState air = Blocks.AIR.defaultBlockState();
		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();
		boolean changed = false;
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				// The wedge widens upward, so each column is outside up to some height and inside above it.
				for (int y = chunk.getMinY(); y < chunk.getMaxY() && !layout.insideWedge(minX + x, y, minZ + z); y++) {
					LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
					if (!section.getBlockState(x, y & 15, z).isAir()) {
						section.setBlockState(x, y & 15, z, air, false);
						changed = true;
					}
				}
			}
		}
		if (changed) {
			EnumSet<Heightmap.Types> types = EnumSet.noneOf(Heightmap.Types.class);
			for (var entry : chunk.getHeightmaps()) {
				types.add(entry.getKey());
				chunk.setHeightmap(entry.getKey(), new long[entry.getValue().getRawData().length]);
			}
			Heightmap.primeHeightmaps(chunk, types);
		}
	}
}

package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.cubeworld.CubeWorldMod;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;

/**
 * Outside its wedge, a face region is first filled with the real cube terrain, so that surface rules, aquifers and
 * carvers near the wedge wall see the same neighbors as in the cube. When the terrain step is done, that space is
 * cleared, because it belongs to other faces.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
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

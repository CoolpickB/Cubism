package dev.cubism.mixin.worldgen;

import dev.cubism.cube.SeamLayout;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * The chunks of the space region stay empty: the void biome, no structures, no features. The terrain and the mobs
 * of the noise generator are skipped in {@code NoiseBasedChunkGeneratorMixin}.
 */
@Mixin(ChunkGenerator.class)
public abstract class SpaceChunksMixin {
	@Inject(method = "createBiomes", at = @At("HEAD"), cancellable = true)
	private void cubism$voidInSpace(
		RandomState randomState, Blender blender, StructureManager structureManager, ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
	) {
		if (this.cubism$inSpace(chunk.getPos())) {
			Holder<Biome> nothing = structureManager.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.THE_VOID);
			chunk.fillBiomesFromNoise((x, y, z) -> nothing);
			cir.setReturnValue(CompletableFuture.completedFuture(chunk));
		}
	}

	@Inject(method = "applyBiomeDecoration", at = @At("HEAD"), cancellable = true)
	private void cubism$noFeaturesInSpace(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager, CallbackInfo ci) {
		if (this.cubism$inSpace(chunk.getPos())) {
			ci.cancel();
		}
	}

	@Inject(method = "createStructures", at = @At("HEAD"), cancellable = true)
	private void cubism$noStructuresInSpace(
		RegistryAccess registryAccess, ChunkGeneratorStructureState state, StructureManager structureManager, ChunkAccess chunk,
		StructureTemplateManager templates, ResourceKey<Level> level, CallbackInfo ci
	) {
		if (this.cubism$inSpace(chunk.getPos())) {
			ci.cancel();
		}
	}

	@Inject(method = "createReferences", at = @At("HEAD"), cancellable = true)
	private void cubism$noReferencesInSpace(WorldGenLevel level, StructureManager structureManager, ChunkAccess chunk, CallbackInfo ci) {
		if (this.cubism$inSpace(chunk.getPos())) {
			ci.cancel();
		}
	}

	private boolean cubism$inSpace(ChunkPos pos) {
		SeamLayout layout = SeamLayout.of((ChunkGenerator) (Object) this);
		return layout != null && layout.inSpace(pos.getMiddleBlockX(), pos.getMiddleBlockZ());
	}
}

package dev.cubeworld.client.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.client.sodiumseam.VoxyFaces;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Voxy keeps only the blocks of a chunk that are inside the wedge of its face, before it builds the levels of detail. */
@Mixin(value = VoxelIngestService.class, remap = false)
public abstract class VoxyIngestMixin {
	@WrapOperation(
		method = "processJob",
		at = @At(
			value = "INVOKE",
			target = "Lme/cortex/voxy/common/voxelization/WorldConversionFactory;convert(Lme/cortex/voxy/common/voxelization/VoxelizedSection;Lme/cortex/voxy/common/world/other/Mapper;Lnet/minecraft/world/level/chunk/PalettedContainer;Lnet/minecraft/world/level/chunk/PalettedContainerRO;Lme/cortex/voxy/common/voxelization/ILightingSupplier;)Lme/cortex/voxy/common/voxelization/VoxelizedSection;"
		)
	)
	@SuppressWarnings("rawtypes")
	private VoxelizedSection cubeworld$cutToWedge(VoxelizedSection section, Mapper mapper, PalettedContainer blocks,
		PalettedContainerRO biomes, ILightingSupplier light, Operation<VoxelizedSection> original) {
		return VoxyFaces.cutToWedge(original.call(section, mapper, blocks, biomes, light));
	}
}

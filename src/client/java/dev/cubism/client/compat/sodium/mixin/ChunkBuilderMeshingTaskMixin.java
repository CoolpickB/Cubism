package dev.cubism.client.compat.sodium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.client.SeamClient;
import dev.cubism.client.compat.sodium.ShadeSlice;
import dev.cubism.worldgen.SeamLayout;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A section draws only the blocks inside its wedge, see {@code SectionCompilerMixin}, and tells the slice which block
 * it renders, for the shade of its faces.
 */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public abstract class ChunkBuilderMeshingTaskMixin {
	@WrapOperation(
		method = "execute",
		at = @At(
			value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/world/LevelSlice;getBlockState(III)Lnet/minecraft/world/level/block/state/BlockState;"
		)
	)
	private BlockState cubism$skipOutsideWedge(LevelSlice slice, int x, int y, int z, Operation<BlockState> original) {
		SeamLayout layout = SeamClient.layout;
		if (layout == null) {
			return original.call(slice, x, y, z);
		}
		if (!layout.insideWedge(x, y, z)) {
			return Blocks.AIR.defaultBlockState();
		}
		((ShadeSlice) (Object) slice).cubism$setShadePos(x, y, z);
		return original.call(slice, x, y, z);
	}
}

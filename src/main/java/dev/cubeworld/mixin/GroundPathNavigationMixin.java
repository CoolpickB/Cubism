package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GroundPathNavigation.class)
public abstract class GroundPathNavigationMixin {
	/**
	 * A target across a seam lies outside the wedge of the mob, where storage is cleared air. The vanilla search for
	 * the surface finds no block in that column and returns the top of the world, so the mob climbs as high as it can.
	 * The target is an entity position, which is already at the surface.
	 */
	@WrapOperation(method = "createPath(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/navigation/GroundPathNavigation;findSurfacePosition(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/core/BlockPos;"))
	private BlockPos cubeworld$keepTargetAcrossSeam(GroundPathNavigation navigation, LevelChunk chunk, BlockPos pos, int reachRange, Operation<BlockPos> original) {
		SeamLayout layout = SeamLayout.of(chunk.getLevel());
		return layout != null && !layout.insideWedge(pos.getX(), pos.getY(), pos.getZ()) ? pos : original.call(navigation, chunk, pos, reachRange);
	}
}

package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.CubeWorldMod;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A jigsaw structure is laid out on the flat plane of its face, and the part past an edge is folded onto the next face
 * (see {@link dev.cubeworld.worldgen.FoldedStarts}). The fold opens a gap above an edge, so a house on both sides of it
 * would show a cut. The placer does not take such a piece and tries another one for the connector. A flat piece, such
 * as a road or a field, bends over the edge without a cut and stays.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement$Placer")
public abstract class JigsawPlacerMixin {
	/** The tallest piece, in blocks, that the fold bends without a visible cut. */
	private static final int FLAT_HEIGHT = 3;

	@Shadow
	@Final
	private ChunkGenerator chunkGenerator;

	@WrapOperation(
		method = "tryPlacingChildren",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/shapes/Shapes;joinIsNotEmpty(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Z")
	)
	private boolean cubeworld$refuseSplitPiece(VoxelShape free, VoxelShape piece, BooleanOp op, Operation<Boolean> original) {
		SeamLayout layout = SeamLayout.active();
		if (layout != null && this.chunkGenerator instanceof NoiseBasedChunkGenerator generator && generator.generatorSettings().is(CubeWorldMod.SEAM_SETTINGS)) {
			// The shape is the box of the piece shrunk by a quarter of a block on each side.
			var bounds = piece.bounds();
			BoundingBox box = new BoundingBox(
				(int) Math.floor(bounds.minX), (int) Math.floor(bounds.minY), (int) Math.floor(bounds.minZ),
				(int) Math.ceil(bounds.maxX) - 1, (int) Math.ceil(bounds.maxY) - 1, (int) Math.ceil(bounds.maxZ) - 1
			);
			if (layout.splitsAtEdge(box, FLAT_HEIGHT)) {
				return true;
			}
		}
		return original.call(free, piece, op);
	}
}

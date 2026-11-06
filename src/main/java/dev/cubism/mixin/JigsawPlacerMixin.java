package dev.cubism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.CubismMod;
import dev.cubism.worldgen.SeamLayout;
import dev.cubism.worldgen.WholeRooms;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * A jigsaw structure is laid out on the flat plane of its face, and the part past an edge is folded onto the next face
 * (see {@link dev.cubism.worldgen.FoldedStarts}). The fold opens a gap above an edge, so a house on both sides of it
 * would show a cut. The placer does not take such a piece and tries another one for the connector. A flat piece, such
 * as a road or a field, bends over the edge without a cut and stays. An underground structure folds about the diagonal
 * at the depth of its first piece (see {@link dev.cubism.worldgen.SeamFold.Fold#atDepth}). Its passages cross the
 * diagonal, and a room must lie wholly on one face (see {@link WholeRooms}). The corner is refused for every piece.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement$Placer")
public abstract class JigsawPlacerMixin {
	/** The tallest piece, in blocks, that the fold bends without a visible cut. */
	private static final int FLAT_HEIGHT = 3;

	@Shadow
	@Final
	private ChunkGenerator chunkGenerator;

	@Shadow
	@Final
	private List<? super PoolElementStructurePiece> pieces;

	@WrapOperation(
		method = "tryPlacingChildren",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/shapes/Shapes;joinIsNotEmpty(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Z")
	)
	private boolean cubism$refuseSplitPiece(
		VoxelShape free, VoxelShape piece, BooleanOp op, Operation<Boolean> original,
		@Local(ordinal = 2) BoundingBox template, @Local(ordinal = 3) BoundingBox placed, @Local(ordinal = 1) StructurePoolElement element
	) {
		SeamLayout layout = SeamLayout.active();
		if (layout != null && this.chunkGenerator instanceof NoiseBasedChunkGenerator generator && generator.generatorSettings().is(CubismMod.SEAM_SETTINGS)) {
			// The height is that of the template. The box in the placer is taller for a piece with connectors of its own,
			// which the expansion hack makes room for, so a road would pass as tall.
			if (layout.splitsAtEdge(placed, template.getYSpan() > FLAT_HEIGHT)) {
				return true;
			}
			int hinge = layout.hingeDepth(((StructurePiece) this.pieces.getFirst()).getBoundingBox().getCenter().getY());
			if (hinge > 0 && WholeRooms.isRoom(element, placed) && !WholeRooms.onOneFace(layout, placed, hinge)) {
				return true;
			}
		}
		return original.call(free, piece, op);
	}
}

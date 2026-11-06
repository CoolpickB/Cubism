package dev.cubism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Entities next to a seam are stored in the region of their own face, so the vanilla check does not find them at a
 * block position of the next face. The block shape also goes to the regions of the neighbor faces, where it shows
 * next to those entities.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
	@WrapOperation(
		method = "canPlace",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isUnobstructed(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Z")
	)
	private boolean cubism$unobstructedAcrossSeam(Level level, BlockState state, BlockPos pos, CollisionContext context, Operation<Boolean> original) {
		if (!original.call(level, state, pos, context)) {
			return false;
		}
		SeamLayout layout = SeamLayout.of(level);
		if (layout == null || !layout.inRegion(pos.getX(), pos.getZ())) {
			return true;
		}
		VoxelShape shape = state.getCollisionShape(level, pos, context);
		if (shape.isEmpty()) {
			return true;
		}
		int face = layout.faceIndexAt(pos.getX());
		for (int other = 0; other < SeamLayout.FACES.length; other++) {
			int[] m = layout.motion(face, other);
			if (other == face || m[4] == -1) {
				continue;
			}
			VoxelShape moved = Shapes.empty();
			for (AABB box : shape.move(pos).toAabbs()) {
				moved = Shapes.or(moved, Shapes.create(new AABB(SeamInteraction.move(m, box.getMinPosition()), SeamInteraction.move(m, box.getMaxPosition()))));
			}
			if (!level.isUnobstructed(null, moved)) {
				return false;
			}
		}
		return true;
	}
}

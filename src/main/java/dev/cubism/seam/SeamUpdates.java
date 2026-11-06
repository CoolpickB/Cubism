package dev.cubism.seam;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import org.jspecify.annotations.Nullable;

/**
 * Neighbor updates that reach a position outside its wedge go to the block of the face that owns it, in the frame
 * of that face. The block there reacts as it does in vanilla, and reads its neighbors across the seam through the
 * turned states of {@link SeamStates}.
 */
public final class SeamUpdates {
	private SeamUpdates() {}

	public static void neighborChanged(
		Level level, BlockState state, BlockPos pos, Block changed, @Nullable Orientation orientation, boolean movedByPiston,
		Operation<Void> original
	) {
		SeamLayout layout = level instanceof ServerLevel serverLevel ? SeamLayout.of(serverLevel) : null;
		BlockPos owner = layout == null ? pos : SeamInteraction.owner(layout, pos);
		if (owner == pos) {
			original.call(level, state, pos, changed, orientation, movedByPiston);
			return;
		}
		if (SeamInteraction.loadedOwner((ServerLevel) level, layout, pos) != null) {
			// The orientation of experimental redstone is in the frame of the source face, so the owner gets none.
			original.call(level, level.getBlockState(owner), owner, changed, null, movedByPiston);
		}
	}

	public static void shapeUpdate(
		LevelAccessor level, Direction direction, BlockPos pos, BlockPos neighborPos, BlockState neighborState, int flags, int limit,
		Operation<Void> original
	) {
		SeamLayout layout = level instanceof ServerLevel serverLevel ? SeamLayout.of(serverLevel) : null;
		BlockPos owner = layout == null ? pos : SeamInteraction.owner(layout, pos);
		if (owner == pos) {
			original.call(level, direction, pos, neighborPos, neighborState, flags, limit);
			return;
		}
		if (SeamInteraction.loadedOwner((ServerLevel) level, layout, pos) == null) {
			return;
		}
		int from = layout.faceIndexAt(pos.getX());
		int to = layout.faceIndexAt(owner.getX());
		Direction turned = SeamInteraction.rotate(layout.motion(from, to), direction);
		original.call(level, turned, owner, owner.relative(turned), SeamStates.turn(layout, neighborState, from, to), flags, limit);
	}
}

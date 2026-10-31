package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.CubeChunkGenerator;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
	@Unique
	private static final Direction[] WAIT_DIRECTIONS = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

	@Shadow
	protected abstract FluidState getNewLiquid(ServerLevel level, BlockPos pos, BlockState state);

	@Shadow
	protected abstract void spreadTo(LevelAccessor level, BlockPos pos, BlockState state, Direction direction, FluidState target);

	/**
	 * Next to the wedge, a fluid sees the blocks and fluids of the next face, so a waterfall that reaches the
	 * diagonal finds the ground of that face below it.
	 */
	@WrapOperation(
		method = {"spread", "spreadToSides", "getNewLiquid", "getSpread"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;")
	)
	private BlockState cubeworld$readAcrossSeam(ServerLevel level, BlockPos pos, Operation<BlockState> original) {
		SeamLayout layout = SeamLayout.of(level);
		return layout == null ? original.call(level, pos) : SeamInteraction.stateAcrossSeam(level, layout, pos);
	}

	/**
	 * A fluid that flows out of its wedge goes to the next face. The fluid there comes from the neighbors in the
	 * frame of that face: a waterfall that pours over an edge is a full fluid beside the block, and it becomes a stream
	 * that flows 7 blocks down the side, as a waterfall does when it lands. A flow that would climb the next face
	 * gives no fluid.
	 */
	@Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
	private void cubeworld$flowAcrossSeam(LevelAccessor level, BlockPos pos, BlockState state, Direction direction, FluidState target, CallbackInfo ci) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		SeamLayout layout = SeamLayout.of(serverLevel);
		if (layout == null) {
			return;
		}
		BlockPos owner = SeamInteraction.owner(layout, pos);
		if (owner == pos) {
			return;
		}
		ci.cancel();
		if (SeamInteraction.loadedOwner(serverLevel, layout, pos) == null) {
			return;
		}
		BlockState ownerState = serverLevel.getBlockState(owner);
		FluidState fluid = this.getNewLiquid(serverLevel, owner, ownerState);
		if (fluid.isEmpty() || fluid.equals(ownerState.getFluidState())) {
			return;
		}
		Direction turned = SeamInteraction.rotate(layout.motion(layout.faceIndexAt(pos.getX()), layout.faceIndexAt(owner.getX())), direction);
		this.spreadTo(level, owner, ownerState, turned, fluid);
	}

	@Inject(method = "spread", at = @At("HEAD"), cancellable = true)
	private void cubeworld$containSideAndBottomFaceFluids(
		ServerLevel level,
		BlockPos pos,
		BlockState blockState,
		FluidState fluidState,
		CallbackInfo ci
	) {
		// Each seam region is a whole world in the frame of its face, so fluids flow there as in vanilla. Only the
		// cleared storage outside the wedge stays dry.
		SeamLayout layout = SeamLayout.of(level);
		if (layout != null) {
			if (!layout.insideWedge(pos.getX(), pos.getY(), pos.getZ())) {
				ci.cancel();
			} else if (this.cubeworld$waitForNeighborFace(level, layout, pos, fluidState)) {
				ci.cancel();
			} else if (this.cubeworld$pourOverLip(level, layout, pos)) {
				ci.cancel();
			}
			return;
		}
		if (level.getChunkSource().getGenerator() instanceof CubeChunkGenerator cubeGen) {
			Direction outward = CubePlanetMath.getPyramidOutwardFace(pos.getX(), pos.getY(), pos.getZ(), cubeGen.centerY());
			int secondMax = CubePlanetMath.getSecondMaxCoord(pos.getX(), pos.getY(), pos.getZ(), cubeGen.centerY());
			if (outward != Direction.UP || secondMax >= cubeGen.radiusBlocks() - 2) {
				ci.cancel();
			}
		}
	}

	/** While the chunk of the next face is not loaded, a fluid next to the seam waits instead of meeting a wall. */
	@Unique
	private boolean cubeworld$waitForNeighborFace(ServerLevel level, SeamLayout layout, BlockPos pos, FluidState fluidState) {
		for (Direction direction : WAIT_DIRECTIONS) {
			if (SeamInteraction.loadedOwner(level, layout, pos.relative(direction)) == null) {
				level.scheduleTick(pos, fluidState.getType(), 20);
				return true;
			}
		}
		return false;
	}

	/**
	 * On the diagonal, the block below a fluid belongs to the next face. That face is turned, so its ground is no floor
	 * for this fluid: the fluid only pours over the edge, where the next face has room, and does not spread sideways
	 * over the slope of that face.
	 */
	@Unique
	private boolean cubeworld$pourOverLip(ServerLevel level, SeamLayout layout, BlockPos pos) {
		BlockPos below = pos.below();
		if (SeamInteraction.owner(layout, below) == below) {
			return false;
		}
		BlockState ownerState = SeamInteraction.stateAcrossSeam(level, layout, below);
		FlowingFluid self = (FlowingFluid) (Object) this;
		if (ownerState.isAir() || ownerState.getFluidState().getType().isSame(self) || ownerState.canBeReplaced(self)) {
			this.spreadTo(level, below, ownerState, Direction.DOWN, self.getFlowing(8, true));
		}
		return true;
	}
}

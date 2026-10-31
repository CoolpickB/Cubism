package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.CubeChunkGenerator;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
	@Unique
	private static final Direction[] WAIT_DIRECTIONS = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

	@Shadow
	protected abstract FluidState getNewLiquid(ServerLevel level, BlockPos pos, BlockState state);

	@Shadow
	protected abstract int getDropOff(LevelReader level);

	@Shadow
	protected abstract void spreadToSides(ServerLevel level, BlockPos pos, FluidState fluidState, BlockState state);

	@Shadow
	protected abstract boolean canMaybePassThrough(BlockGetter level, BlockPos sourcePos, BlockState sourceState, Direction direction, BlockPos testPos, BlockState testState, FluidState testFluidState);

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
	 * A fluid that flows out of its wedge goes to the next face. The fluid there comes from its own neighbors, by
	 * altitude (see cubeworld$pouredAcrossSeam), so a flow that would climb the next face gives no fluid.
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

	/**
	 * Across a seam, "above" and "beside" depend on the frame of the face, so the seam rules use altitude: the height
	 * of a block in the frame of the face that owns it. A neighbor at a higher altitude pours onto this block in full.
	 * A neighbor at the same altitude is beside it, also when it is the storage block below a diagonal block. Every
	 * flow then goes down in altitude, or goes sideways and loses a level, so all flows drain.
	 */
	@ModifyReturnValue(method = "getNewLiquid", at = @At("RETURN"))
	private FluidState cubeworld$pouredAcrossSeam(FluidState fluid, ServerLevel level, BlockPos pos, BlockState state) {
		if (fluid.isSource() || fluid.getAmount() == 8) {
			return fluid;
		}
		SeamLayout layout = SeamLayout.of(level);
		if (layout == null || SeamInteraction.owner(layout, pos) != pos) {
			return fluid;
		}
		FlowingFluid self = (FlowingFluid) (Object) this;
		int amount = fluid.getAmount();
		for (Direction direction : Direction.values()) {
			BlockPos neighbor = pos.relative(direction);
			BlockPos owner = SeamInteraction.loadedOwner(level, layout, neighbor);
			if (owner == null || owner == neighbor) {
				continue;
			}
			FluidState other = level.getFluidState(owner);
			if (!other.getType().isSame(self)) {
				continue;
			}
			if (owner.getY() > pos.getY()) {
				return self.getFlowing(8, true);
			}
			if (owner.getY() == pos.getY() && direction.getAxis() == Direction.Axis.Y) {
				amount = Math.max(amount, other.getAmount() - this.getDropOff(level));
			}
		}
		return amount == fluid.getAmount() ? fluid : amount <= 0 ? fluid : self.getFlowing(amount, false);
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
			} else if (this.cubeworld$spreadOnDiagonal(level, layout, pos, blockState, fluidState)) {
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
	 * The storage block below a diagonal block belongs to the next face at the same altitude, so it is beside the
	 * fluid. The fluid spreads to it and to its sides, as on flat ground.
	 */
	@Unique
	private boolean cubeworld$spreadOnDiagonal(ServerLevel level, SeamLayout layout, BlockPos pos, BlockState state, FluidState fluidState) {
		BlockPos below = pos.below();
		BlockPos owner = SeamInteraction.owner(layout, below);
		if (owner == below || owner.getY() != pos.getY()) {
			return false;
		}
		if (fluidState.isEmpty()) {
			return true;
		}
		this.spreadToSides(level, pos, fluidState, state);
		int amount = fluidState.getValue(FlowingFluid.FALLING) ? 7 : fluidState.getAmount() - this.getDropOff(level);
		BlockState belowState = SeamInteraction.stateAcrossSeam(level, layout, below);
		if (amount > 0 && this.canMaybePassThrough(level, pos, state, Direction.DOWN, below, belowState, belowState.getFluidState())) {
			this.spreadTo(level, below, belowState, Direction.DOWN, fluidState);
		}
		return true;
	}

	/** A block is a hole only when the block below it is lower in altitude. Below a diagonal block, it is not. */
	@Inject(method = "isWaterHole", at = @At("HEAD"), cancellable = true)
	private void cubeworld$holeByAltitude(BlockGetter level, BlockPos topPos, BlockState topState, BlockPos bottomPos, BlockState bottomState, CallbackInfoReturnable<Boolean> cir) {
		SeamLayout layout = level instanceof ServerLevel serverLevel ? SeamLayout.of(serverLevel) : null;
		if (layout != null && SeamInteraction.owner(layout, bottomPos).getY() >= SeamInteraction.owner(layout, topPos).getY()) {
			cir.setReturnValue(false);
		}
	}

	/**
	 * A sideways storage step into another face can go up in altitude, and no fluid flows there. Vanilla would choose
	 * such a step and then send the fluid nowhere, so these steps are left out before vanilla chooses. Steps at the
	 * same altitude stay, so a fluid on a side face can flow onto the diagonal of the next face.
	 */
	@WrapOperation(method = "getSpread", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FlowingFluid;canMaybePassThrough(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)Z"))
	private boolean cubeworld$spreadInFace(
		FlowingFluid self, BlockGetter level, BlockPos from, BlockState fromState, Direction direction, BlockPos to, BlockState toState, FluidState toFluid,
		Operation<Boolean> original
	) {
		return original.call(self, level, from, fromState, direction, to, toState, toFluid) && !this.cubeworld$climbs(level, from, to);
	}

	@WrapOperation(method = "getSlopeDistance", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FlowingFluid;canPassThrough(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/level/material/Fluid;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)Z"))
	private boolean cubeworld$searchInFace(
		FlowingFluid self, BlockGetter level, Fluid fluid, BlockPos from, BlockState fromState, Direction direction, BlockPos to, BlockState toState, FluidState toFluid,
		Operation<Boolean> original
	) {
		return original.call(self, level, fluid, from, fromState, direction, to, toState, toFluid) && !this.cubeworld$climbs(level, from, to);
	}

	@Unique
	private boolean cubeworld$climbs(BlockGetter level, BlockPos from, BlockPos to) {
		SeamLayout layout = level instanceof ServerLevel serverLevel ? SeamLayout.of(serverLevel) : null;
		return layout != null && SeamInteraction.owner(layout, to).getY() != SeamInteraction.owner(layout, from).getY();
	}
}

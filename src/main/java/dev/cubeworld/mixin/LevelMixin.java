package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.network.CubeNetworking;
import dev.cubeworld.seam.CubeTime;
import dev.cubeworld.seam.CubeTimeLevel;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.seam.SeamStates;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelMixin implements CubeTimeLevel, BlockAndLightGetter {
	@Shadow
	private int skyDarken;

	@Unique
	private CubeTime.@Nullable State cubeworld$cubeTime;

	@Override
	public CubeTime.@Nullable State cubeworld$cubeTime() {
		return this.cubeworld$cubeTime;
	}

	@Override
	public void cubeworld$setCubeTime(CubeTime.State state) {
		this.cubeworld$cubeTime = state;
	}

	@Override
	public int cubeworld$skyDarken() {
		return this.skyDarken;
	}

	@Override
	public void cubeworld$setSkyDarken(int skyDarken) {
		this.skyDarken = skyDarken;
	}

	/** A daylight detector reads the sky of its own face. */
	@WrapOperation(method = "tickBlockEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
	private void cubeworld$tickAtLocalTime(TickingBlockEntity ticker, Operation<Void> original) {
		BlockPos pos = ticker.getPos();
		CubeTime.at((Level) (Object) this, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, () -> original.call(ticker));
	}

	/**
	 * Outside its wedge, a face region holds cleared air with no real light. Particles, mobs and entity rendering
	 * near a seam read the light there, so a read outside the wedge goes to the face that owns the position.
	 */
	@Override
	public int getBrightness(LightLayer layer, BlockPos pos) {
		return this.getLightEngine().getLayerListener(layer).getLightValue(this.cubeworld$lightOwner(pos));
	}

	@Override
	public int getRawBrightness(BlockPos pos, int darkening) {
		return this.getLightEngine().getRawBrightness(this.cubeworld$lightOwner(pos), darkening);
	}

	@Unique
	private BlockPos cubeworld$lightOwner(BlockPos pos) {
		SeamLayout layout = SeamLayout.of((Level) (Object) this);
		if (layout == null || !layout.inRegion(pos.getX(), pos.getZ())) {
			return pos;
		}
		int up = pos.getY() - layout.centerY;
		// Above both diagonals of its face, a position is surely in its wedge. This skips the full test on most reads.
		if (up > Math.abs(pos.getX() - layout.offsetX(layout.faceIndexAt(pos.getX()))) && up > Math.abs(pos.getZ())) {
			return pos;
		}
		int[] owner = new int[3];
		layout.ownerStorage(pos.getX(), pos.getY(), pos.getZ(), owner);
		return owner[0] == pos.getX() && owner[1] == pos.getY() && owner[2] == pos.getZ() ? pos : new BlockPos(owner[0], owner[1], owner[2]);
	}

	@Unique
	private @Nullable SeamLayout cubeworld$serverSeams;
	@Unique
	private boolean cubeworld$seamsResolved;

	/**
	 * Outside its wedge, a face region holds cleared air. On the server, blocks there read as the blocks of the face
	 * that owns the position, turned into the frame of this region, so that block logic next to a seam sees the
	 * blocks across it. The client keeps the cleared air: its rendering, picking and collision read across the seam
	 * on their own. Only while the client predicts an item use does it read and write as the server does, or its
	 * prediction fails where the server succeeds, and the player gets no sound and no swing.
	 */
	@ModifyReturnValue(method = "getBlockState", at = @At("RETURN"))
	private BlockState cubeworld$blockAcrossSeam(BlockState state, @Local(argsOnly = true) BlockPos pos) {
		BlockPos owner = this.cubeworld$loadedOwner(pos);
		if (owner == null) {
			return state;
		}
		SeamLayout layout = this.cubeworld$seams();
		return SeamStates.turn(layout, this.getBlockState(owner), layout.faceIndexAt(owner.getX()), layout.faceIndexAt(pos.getX()));
	}

	@ModifyReturnValue(method = "getFluidState", at = @At("RETURN"))
	private FluidState cubeworld$fluidAcrossSeam(FluidState fluid, @Local(argsOnly = true) BlockPos pos) {
		return this.cubeworld$loadedOwner(pos) == null ? fluid : this.getBlockState(pos).getFluidState();
	}

	/**
	 * A block that block logic sets outside the wedge goes to the face that owns the position, turned into its frame.
	 * Where the turn is not possible, nothing changes.
	 */
	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"), cancellable = true)
	private void cubeworld$setAcrossSeam(BlockPos pos, BlockState state, int flags, int limit, CallbackInfoReturnable<Boolean> cir) {
		if (this.cubeworld$clientOutside(pos)) {
			cir.setReturnValue(false);
			return;
		}
		SeamLayout layout = this.cubeworld$seams();
		if (layout == null || cubeworld$surelyInside(layout, pos)) {
			return;
		}
		int[] owner = new int[3];
		if (!layout.ownerStorage(pos.getX(), pos.getY(), pos.getZ(), owner)
			|| owner[0] == pos.getX() && owner[1] == pos.getY() && owner[2] == pos.getZ()) {
			return;
		}
		BlockPos ownerPos = new BlockPos(owner[0], owner[1], owner[2]);
		int from = layout.faceIndexAt(pos.getX());
		int to = layout.faceIndexAt(ownerPos.getX());
		cir.setReturnValue(false);
		if (!this.cubeworld$ownerLoaded(ownerPos) || SeamStates.unturnable(layout, this.getBlockState(ownerPos), to, from)
			|| SeamStates.unturnable(layout, state, from, to)) {
			return;
		}
		cir.setReturnValue(((Level) (Object) this).setBlock(ownerPos, SeamStates.turn(layout, state, from, to), flags, limit));
	}

	/**
	 * A piston sets a moving block and then its block entity at the destination. Across a seam, the block went to
	 * the owner face, and without its block entity it stayed there as an invisible block that cannot be broken. The
	 * block entity follows the block, with its direction and moved block turned into the owner frame.
	 */
	@Inject(method = "setBlockEntity", at = @At("HEAD"), cancellable = true)
	private void cubeworld$movingBlockAcrossSeam(BlockEntity blockEntity, CallbackInfo ci) {
		if (this.cubeworld$clientOutside(blockEntity.getBlockPos())) {
			ci.cancel();
			return;
		}
		if (!(blockEntity instanceof PistonMovingBlockEntity moving)) {
			return;
		}
		BlockPos pos = moving.getBlockPos();
		BlockPos owner = this.cubeworld$loadedOwner(pos);
		if (owner == null) {
			if (this.cubeworld$serverSeams != null && this.cubeworld$nearSeam(pos)) {
				CubeNetworking.sendMovingBlock((ServerLevel) (Object) this, moving);
			}
			return;
		}
		ci.cancel();
		BlockState ownerState = this.getBlockState(owner);
		if (!ownerState.is(Blocks.MOVING_PISTON)) {
			return;
		}
		SeamLayout layout = this.cubeworld$seams();
		int from = layout.faceIndexAt(pos.getX());
		int to = layout.faceIndexAt(owner.getX());
		((Level) (Object) this).setBlockEntity(MovingPistonBlock.newMovingBlockEntity(
			owner, ownerState, SeamStates.turn(layout, moving.getMovedState(), from, to),
			SeamInteraction.rotate(layout.motion(from, to), moving.getDirection()), moving.isExtending(), moving.isSourcePiston()
		));
	}

	/**
	 * The client keeps cleared air outside the wedge, and the server never changes it. Block logic that the client runs
	 * itself, such as a piston move from a block event, would leave blocks there that show over the real ones.
	 */
	@Unique
	private boolean cubeworld$clientOutside(BlockPos pos) {
		Level level = (Level) (Object) this;
		if (!level.isClientSide() || SeamInteraction.predicting()) {
			return false;
		}
		SeamLayout layout = SeamLayout.of(level);
		return layout != null && layout.inRegion(pos.getX(), pos.getZ()) && !layout.insideWedge(pos.getX(), pos.getY(), pos.getZ());
	}

	/** The owner position of a position outside its wedge on the server, when its chunk is loaded, else null. */
	@Unique
	private @Nullable BlockPos cubeworld$loadedOwner(BlockPos pos) {
		SeamLayout layout = this.cubeworld$seams();
		if (layout == null || cubeworld$surelyInside(layout, pos)) {
			return null;
		}
		int[] owner = new int[3];
		if (!layout.ownerStorage(pos.getX(), pos.getY(), pos.getZ(), owner)
			|| owner[0] == pos.getX() && owner[1] == pos.getY() && owner[2] == pos.getZ()) {
			return null;
		}
		BlockPos ownerPos = new BlockPos(owner[0], owner[1], owner[2]);
		return this.cubeworld$ownerLoaded(ownerPos) ? ownerPos : null;
	}

	@Unique
	private boolean cubeworld$ownerLoaded(BlockPos pos) {
		return ((Level) (Object) this).getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())) != null;
	}

	/** True for positions in no region and for positions above both diagonals of their face, which skips most reads. */
	@Unique
	private static boolean cubeworld$surelyInside(SeamLayout layout, BlockPos pos) {
		if (!layout.inRegion(pos.getX(), pos.getZ())) {
			return true;
		}
		int up = pos.getY() - layout.centerY;
		return up > Math.abs(pos.getX() - layout.offsetX(layout.faceIndexAt(pos.getX()))) && up > Math.abs(pos.getZ());
	}

	/** True inside a wedge when a piston line of 12 blocks can reach across a seam. */
	@Unique
	private boolean cubeworld$nearSeam(BlockPos pos) {
		SeamLayout layout = this.cubeworld$serverSeams;
		if (!layout.inRegion(pos.getX(), pos.getZ())) {
			return false;
		}
		int up = pos.getY() - layout.centerY;
		return up - Math.max(Math.abs(pos.getX() - layout.offsetX(layout.faceIndexAt(pos.getX()))), Math.abs(pos.getZ())) <= 14;
	}

	@Unique
	private @Nullable SeamLayout cubeworld$seams() {
		// The layout is known once the first chunk generates. From then on, the answer for this level is final.
		if (!this.cubeworld$seamsResolved && (Object) this instanceof ServerLevel level && SeamLayout.active() != null) {
			this.cubeworld$serverSeams = SeamLayout.of(level);
			this.cubeworld$seamsResolved = true;
		}
		if (this.cubeworld$serverSeams == null && SeamInteraction.predicting() && ((Level) (Object) this).isClientSide()) {
			return SeamLayout.of((Level) (Object) this);
		}
		return this.cubeworld$serverSeams;
	}
}

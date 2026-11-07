package dev.cubism.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.network.CubeNetworking;
import dev.cubism.seam.CubeTime;
import dev.cubism.seam.CubeTimeLevel;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.seam.SeamStates;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
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
	private CubeTime.@Nullable State cubism$cubeTime;

	@Override
	public CubeTime.@Nullable State cubism$cubeTime() {
		return this.cubism$cubeTime;
	}

	@Override
	public void cubism$setCubeTime(CubeTime.State state) {
		this.cubism$cubeTime = state;
	}

	@Override
	public int cubism$skyDarken() {
		return this.skyDarken;
	}

	@Override
	public void cubism$setSkyDarken(int skyDarken) {
		this.skyDarken = skyDarken;
	}

	/** A daylight detector reads the sky of its own face. */
	@WrapOperation(method = "tickBlockEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
	private void cubism$tickAtLocalTime(TickingBlockEntity ticker, Operation<Void> original) {
		BlockPos pos = ticker.getPos();
		CubeTime.at((Level) (Object) this, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, () -> original.call(ticker));
	}

	/**
	 * Outside its wedge, a face region holds cleared air with no real light. Particles, mobs and entity rendering
	 * near a seam read the light there, so a read outside the wedge goes to the face that owns the position.
	 */
	@Override
	public int getBrightness(LightLayer layer, BlockPos pos) {
		return this.getLightEngine().getLayerListener(layer).getLightValue(this.cubism$lightOwner(pos));
	}

	@Override
	public int getRawBrightness(BlockPos pos, int darkening) {
		return this.getLightEngine().getRawBrightness(this.cubism$lightOwner(pos), darkening);
	}

	@Unique
	private BlockPos cubism$lightOwner(BlockPos pos) {
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
	private @Nullable SeamLayout cubism$serverSeams;
	@Unique
	private boolean cubism$seamsResolved;

	/**
	 * Outside its wedge, a face region holds cleared air. On the server, blocks there read as the blocks of the face
	 * that owns the position, turned into the frame of this region, so that block logic next to a seam sees the
	 * blocks across it. The client keeps the cleared air: its rendering, picking and collision read across the seam
	 * on their own. Only while the client predicts an item use does it read and write as the server does, or its
	 * prediction fails where the server succeeds, and the player gets no sound and no swing.
	 */
	@ModifyReturnValue(method = "getBlockState", at = @At("RETURN"))
	private BlockState cubism$blockAcrossSeam(BlockState state, @Local(argsOnly = true) BlockPos pos) {
		BlockPos owner = this.cubism$loadedOwner(pos);
		if (owner == null) {
			return state;
		}
		SeamLayout layout = this.cubism$seams();
		return SeamStates.turn(layout, this.getBlockState(owner), layout.faceIndexAt(owner.getX()), layout.faceIndexAt(pos.getX()));
	}

	/**
	 * Fluids read across the seam on the client too. The player moves and swims on its own client, and the camera fog
	 * and the water sounds come from there. With the cleared air, a player who swam over the diagonal left the water
	 * and fell through the seam.
	 */
	@ModifyReturnValue(method = "getFluidState", at = @At("RETURN"))
	private FluidState cubism$fluidAcrossSeam(FluidState fluid, @Local(argsOnly = true) BlockPos pos) {
		if (this.cubism$loadedOwner(pos) != null) {
			return this.getBlockState(pos).getFluidState();
		}
		BlockPos owner = this.cubism$clientOwner(pos);
		return owner == null ? fluid : this.getBlockState(owner).getFluidState();
	}

	/** The owner position of a position outside its wedge on the client, when its chunk is loaded, else null. */
	@Unique
	private @Nullable BlockPos cubism$clientOwner(BlockPos pos) {
		Level level = (Level) (Object) this;
		return level.isClientSide() ? this.cubism$loadedOwner(SeamLayout.of(level), pos) : null;
	}

	/**
	 * A block that block logic sets outside the wedge goes to the face that owns the position, turned into its frame.
	 * Where the turn is not possible, nothing changes.
	 */
	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"), cancellable = true)
	private void cubism$setAcrossSeam(BlockPos pos, BlockState state, int flags, int limit, CallbackInfoReturnable<Boolean> cir) {
		if (this.cubism$clientOutside(pos)) {
			cir.setReturnValue(false);
			return;
		}
		SeamLayout layout = this.cubism$seams();
		if (layout == null || cubism$surelyInside(layout, pos)) {
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
		if (!this.cubism$ownerLoaded(ownerPos) || SeamStates.unturnable(layout, this.getBlockState(ownerPos), to, from)
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
	private void cubism$movingBlockAcrossSeam(BlockEntity blockEntity, CallbackInfo ci) {
		if (this.cubism$clientOutside(blockEntity.getBlockPos())) {
			ci.cancel();
			return;
		}
		if (!(blockEntity instanceof PistonMovingBlockEntity moving)) {
			return;
		}
		BlockPos pos = moving.getBlockPos();
		BlockPos owner = this.cubism$loadedOwner(pos);
		if (owner == null) {
			if (this.cubism$serverSeams != null && this.cubism$nearSeam(pos)) {
				CubeNetworking.sendMovingBlock((ServerLevel) (Object) this, moving);
			}
			return;
		}
		ci.cancel();
		BlockState ownerState = this.getBlockState(owner);
		if (!ownerState.is(Blocks.MOVING_PISTON)) {
			return;
		}
		SeamLayout layout = this.cubism$seams();
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
	private boolean cubism$clientOutside(BlockPos pos) {
		Level level = (Level) (Object) this;
		if (!level.isClientSide() || SeamInteraction.predicting()) {
			return false;
		}
		SeamLayout layout = SeamLayout.of(level);
		return layout != null && layout.inRegion(pos.getX(), pos.getZ()) && !layout.insideWedge(pos.getX(), pos.getY(), pos.getZ());
	}

	/** The owner position of a position outside its wedge on the server, when its chunk is loaded, else null. */
	@Unique
	private @Nullable BlockPos cubism$loadedOwner(BlockPos pos) {
		return this.cubism$loadedOwner(this.cubism$seams(), pos);
	}

	@Unique
	private @Nullable BlockPos cubism$loadedOwner(@Nullable SeamLayout layout, BlockPos pos) {
		if (layout == null || cubism$surelyInside(layout, pos)) {
			return null;
		}
		int[] owner = new int[3];
		if (!layout.ownerStorage(pos.getX(), pos.getY(), pos.getZ(), owner)
			|| owner[0] == pos.getX() && owner[1] == pos.getY() && owner[2] == pos.getZ()) {
			return null;
		}
		BlockPos ownerPos = new BlockPos(owner[0], owner[1], owner[2]);
		return this.cubism$ownerLoaded(ownerPos) ? ownerPos : null;
	}

	@Unique
	private boolean cubism$ownerLoaded(BlockPos pos) {
		return ((Level) (Object) this).getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())) != null;
	}

	/** True for positions in no region and for positions above both diagonals of their face, which skips most reads. */
	@Unique
	private static boolean cubism$surelyInside(SeamLayout layout, BlockPos pos) {
		if (!layout.inRegion(pos.getX(), pos.getZ())) {
			return true;
		}
		int up = pos.getY() - layout.centerY;
		return up > Math.abs(pos.getX() - layout.offsetX(layout.faceIndexAt(pos.getX()))) && up > Math.abs(pos.getZ());
	}

	/** True inside a wedge when a piston line of 12 blocks can reach across a seam. */
	@Unique
	private boolean cubism$nearSeam(BlockPos pos) {
		SeamLayout layout = this.cubism$serverSeams;
		if (!layout.inRegion(pos.getX(), pos.getZ())) {
			return false;
		}
		int up = pos.getY() - layout.centerY;
		return up - Math.max(Math.abs(pos.getX() - layout.offsetX(layout.faceIndexAt(pos.getX()))), Math.abs(pos.getZ())) <= 14;
	}

	@Unique
	private @Nullable SeamLayout cubism$seams() {
		// The layout of a cube level is known once the level makes its random state. From then on, the answer is final.
		if (!this.cubism$seamsResolved && (Object) this instanceof ServerLevel level) {
			this.cubism$serverSeams = SeamLayout.of(level);
			this.cubism$seamsResolved = this.cubism$serverSeams != null
				|| !(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator && SeamLayout.isSeam(generator));
		}
		if (this.cubism$serverSeams == null && SeamInteraction.predicting() && ((Level) (Object) this).isClientSide()) {
			return SeamLayout.of((Level) (Object) this);
		}
		return this.cubism$serverSeams;
	}
}

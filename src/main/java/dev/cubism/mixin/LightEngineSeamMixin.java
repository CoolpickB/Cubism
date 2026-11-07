package dev.cubism.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.SkyLightEngine;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Light spreads across seams. A face region holds cleared air outside its wedge, so light that stepped there lit
 * nothing on the next face, and sky light that filled that air leaked back into caves next to the seam. A step
 * that leaves the wedge now goes to the block that the position stands for, in the region of the face that owns
 * it, and the cleared air spreads no light. Readers of light outside a wedge already read the owner, see
 * {@code LevelMixin}.
 * <p>
 * The queue entry of a step tells the next node which way it came from, so that it does not step back. Across a
 * seam that direction is in the frame of the other face, so the entry of a turned step lets the node spread in all
 * directions.
 */
@Mixin({BlockLightEngine.class, SkyLightEngine.class})
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class LightEngineSeamMixin extends LightEngine {
	/** The direction bits of a queue entry, see {@link LightEngine.QueueEntry}. */
	@Unique private static final long ALL_DIRECTIONS = 1008L;

	@Unique private @Nullable SeamLayout cubism$layout;
	@Unique private boolean cubism$layoutResolved;
	/** True while the current step of a propagation went across a seam. */
	@Unique private boolean cubism$turned;
	@Unique private final int[] cubism$owner = new int[3];

	private LightEngineSeamMixin() {
		super(null, null);
	}

	@Inject(method = "propagateIncrease", at = @At("HEAD"), cancellable = true)
	private void cubism$skipClearedIncrease(long fromNode, long increaseData, int fromLevel, CallbackInfo ci) {
		if (this.cubism$outside(fromNode)) {
			ci.cancel();
		}
	}

	@Inject(method = "propagateDecrease", at = @At("HEAD"), cancellable = true)
	private void cubism$skipClearedDecrease(long fromNode, long decreaseData, CallbackInfo ci) {
		if (this.cubism$outside(fromNode)) {
			ci.cancel();
		}
	}

	@WrapOperation(
		method = {"propagateIncrease", "propagateDecrease"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;offset(JLnet/minecraft/core/Direction;)J")
	)
	private long cubism$stepAcrossSeam(long node, Direction direction, Operation<Long> original) {
		long to = original.call(node, direction);
		this.cubism$turned = this.cubism$outside(to);
		return this.cubism$turned ? BlockPos.asLong(this.cubism$owner[0], this.cubism$owner[1], this.cubism$owner[2]) : to;
	}

	@ModifyExpressionValue(
		method = {"propagateIncrease", "propagateDecrease"},
		at = {
			@At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LightEngine$QueueEntry;increaseSkipOneDirection(IZLnet/minecraft/core/Direction;)J"),
			@At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LightEngine$QueueEntry;decreaseSkipOneDirection(ILnet/minecraft/core/Direction;)J"),
			@At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LightEngine$QueueEntry;increaseOnlyOneDirection(IZLnet/minecraft/core/Direction;)J")
		}
	)
	private long cubism$spreadAllWaysAfterSeam(long entry) {
		return this.cubism$turned ? entry | ALL_DIRECTIONS : entry;
	}

	/**
	 * The sky engine fills empty sections under a step at a section edge. Its test for the edge uses the direction
	 * of the step, which is wrong after a seam. The owner face fills its own empty sections when its light changes.
	 */
	@Inject(method = "propagateFromEmptySections", at = @At("HEAD"), cancellable = true, require = 0)
	private void cubism$noEmptySectionsAfterSeam(long toNode, Direction direction, int toLevel, boolean increase, int emptySectionsBelow, CallbackInfo ci) {
		if (this.cubism$turned) {
			ci.cancel();
		}
	}

	/** True when the node is outside the wedge of its region, with the node it stands for in {@code cubism$owner}. */
	@Unique
	private boolean cubism$outside(long node) {
		SeamLayout layout = this.cubism$layout();
		if (layout == null) {
			return false;
		}
		int x = BlockPos.getX(node);
		int y = BlockPos.getY(node);
		int z = BlockPos.getZ(node);
		if (!layout.inRegion(x, z)) {
			return false;
		}
		int up = y - layout.centerY;
		// Above both diagonals of its face, a node is surely in its wedge. This skips the full test on most nodes.
		if (up > Math.abs(x - layout.offsetX(layout.faceIndexAt(x))) && up > Math.abs(z)) {
			return false;
		}
		int[] owner = this.cubism$owner;
		layout.ownerStorage(x, y, z, owner);
		return owner[0] != x || owner[1] != y || owner[2] != z;
	}

	@Unique
	private @Nullable SeamLayout cubism$layout() {
		if (!this.cubism$layoutResolved && this.chunkSource.getLevel() instanceof Level level) {
			this.cubism$layout = SeamLayout.of(level);
			// The server layout is set when the cube world loads, and the client layout on join. Either can come after
			// the level exists.
			this.cubism$layoutResolved = level instanceof ServerLevel serverLevel
				? this.cubism$layout != null
					|| !(serverLevel.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator && SeamLayout.isSeam(generator))
				: this.cubism$layout != null || level.dimension() != Level.OVERWORLD && level.dimension() != Level.NETHER;
		}
		return this.cubism$layout;
	}
}

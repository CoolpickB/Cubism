package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
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

	@Unique private @Nullable SeamLayout cubeworld$layout;
	@Unique private boolean cubeworld$layoutResolved;
	/** True while the current step of a propagation went across a seam. */
	@Unique private boolean cubeworld$turned;
	@Unique private final int[] cubeworld$owner = new int[3];

	private LightEngineSeamMixin() {
		super(null, null);
	}

	@Inject(method = "propagateIncrease", at = @At("HEAD"), cancellable = true)
	private void cubeworld$skipClearedIncrease(long fromNode, long increaseData, int fromLevel, CallbackInfo ci) {
		if (this.cubeworld$outside(fromNode)) {
			ci.cancel();
		}
	}

	@Inject(method = "propagateDecrease", at = @At("HEAD"), cancellable = true)
	private void cubeworld$skipClearedDecrease(long fromNode, long decreaseData, CallbackInfo ci) {
		if (this.cubeworld$outside(fromNode)) {
			ci.cancel();
		}
	}

	@WrapOperation(
		method = {"propagateIncrease", "propagateDecrease"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;offset(JLnet/minecraft/core/Direction;)J")
	)
	private long cubeworld$stepAcrossSeam(long node, Direction direction, Operation<Long> original) {
		long to = original.call(node, direction);
		this.cubeworld$turned = this.cubeworld$outside(to);
		return this.cubeworld$turned ? BlockPos.asLong(this.cubeworld$owner[0], this.cubeworld$owner[1], this.cubeworld$owner[2]) : to;
	}

	@ModifyExpressionValue(
		method = {"propagateIncrease", "propagateDecrease"},
		at = {
			@At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LightEngine$QueueEntry;increaseSkipOneDirection(IZLnet/minecraft/core/Direction;)J"),
			@At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LightEngine$QueueEntry;decreaseSkipOneDirection(ILnet/minecraft/core/Direction;)J"),
			@At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LightEngine$QueueEntry;increaseOnlyOneDirection(IZLnet/minecraft/core/Direction;)J")
		}
	)
	private long cubeworld$spreadAllWaysAfterSeam(long entry) {
		return this.cubeworld$turned ? entry | ALL_DIRECTIONS : entry;
	}

	/**
	 * The sky engine fills empty sections under a step at a section edge. Its test for the edge uses the direction
	 * of the step, which is wrong after a seam. The owner face fills its own empty sections when its light changes.
	 */
	@Inject(method = "propagateFromEmptySections", at = @At("HEAD"), cancellable = true, require = 0)
	private void cubeworld$noEmptySectionsAfterSeam(long toNode, Direction direction, int toLevel, boolean increase, int emptySectionsBelow, CallbackInfo ci) {
		if (this.cubeworld$turned) {
			ci.cancel();
		}
	}

	/** True when the node is outside the wedge of its region, with the node it stands for in {@code cubeworld$owner}. */
	@Unique
	private boolean cubeworld$outside(long node) {
		SeamLayout layout = this.cubeworld$layout();
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
		int[] owner = this.cubeworld$owner;
		layout.ownerStorage(x, y, z, owner);
		return owner[0] != x || owner[1] != y || owner[2] != z;
	}

	@Unique
	private @Nullable SeamLayout cubeworld$layout() {
		if (!this.cubeworld$layoutResolved && this.chunkSource.getLevel() instanceof Level level) {
			this.cubeworld$layout = SeamLayout.of(level);
			// The server layout is set when the cube world loads, and the client layout on join. Either can come after
			// the level exists.
			this.cubeworld$layoutResolved = level instanceof ServerLevel
				? SeamLayout.active() != null
				: this.cubeworld$layout != null || level.dimension() != Level.OVERWORLD;
		}
		return this.cubeworld$layout;
	}
}

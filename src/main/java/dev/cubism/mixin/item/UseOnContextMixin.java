package dev.cubism.mixin.item;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.cube.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Blocks orient themselves from the context in the frame of the clicked position. Across a seam, the player stands
 * in the region of another face, and the hit can come from that region too, so both turn into the frame of the
 * clicked position.
 */
@Mixin(UseOnContext.class)
public abstract class UseOnContextMixin {
	@Shadow public abstract Level getLevel();
	@Shadow public abstract @Nullable Player getPlayer();
	@Shadow public abstract BlockPos getClickedPos();
	@Shadow protected abstract BlockHitResult getHitResult();

	/** The motion from the region of the hit to the region of the clicked position. They differ when a block goes across a seam. */
	private int @Nullable [] cubism$hitMotion() {
		SeamLayout layout = SeamLayout.of(this.getLevel());
		return layout == null ? null : SeamInteraction.blockMotion(layout, this.getHitResult().getBlockPos(), this.getClickedPos());
	}

	@ModifyReturnValue(method = "getClickedFace", at = @At("RETURN"))
	private Direction cubism$clickedFaceAcrossSeam(Direction face) {
		int[] m = this.cubism$hitMotion();
		return m == null ? face : SeamInteraction.rotate(m, face);
	}

	@ModifyReturnValue(method = "getClickLocation", at = @At("RETURN"))
	private Vec3 cubism$clickLocationAcrossSeam(Vec3 location) {
		int[] m = this.cubism$hitMotion();
		return m == null ? location : SeamInteraction.move(m, location);
	}

	@ModifyReturnValue(method = "getHorizontalDirection", at = @At("RETURN"))
	private Direction cubism$facingAcrossSeam(Direction direction) {
		Vec3 facing = SeamInteraction.placerFacing(this.getLevel(), this.getPlayer(), this.getClickedPos());
		return facing == null ? direction : Direction.getApproximateNearest(facing.x, 0.0, facing.z);
	}

	@ModifyReturnValue(method = "getRotation", at = @At("RETURN"))
	private float cubism$rotationAcrossSeam(float rotation) {
		Vec3 facing = SeamInteraction.placerFacing(this.getLevel(), this.getPlayer(), this.getClickedPos());
		return facing == null ? rotation : (float) Math.toDegrees(Math.atan2(-facing.x, facing.z));
	}
}

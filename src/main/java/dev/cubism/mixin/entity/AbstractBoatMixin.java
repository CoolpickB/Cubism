package dev.cubism.mixin.entity;

import dev.cubism.gravity.GravityEntityAccess;
import dev.cubism.cube.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Boats add their own pull in {@code floatBoat}, not in {@code applyGravity}. In the air over an edge, that pull turns
 * to the edge pull, as for other entities. Without it, a boat that falls off an edge crosses the diagonal back and
 * forth.
 */
@Mixin(AbstractBoat.class)
public abstract class AbstractBoatMixin {
	@Shadow private AbstractBoat.Status status;

	@Inject(method = "floatBoat", at = @At("TAIL"))
	private void cubism$edgeGravity(CallbackInfo ci) {
		AbstractBoat self = (AbstractBoat) (Object) this;
		Vec3 pull = ((GravityEntityAccess) self).cubism$edgePull();
		if (pull != null && this.status == AbstractBoat.Status.IN_AIR) {
			double gravity = self.getGravity();
			self.setDeltaMovement(self.getDeltaMovement().add(pull.x * gravity, (pull.y + 1.0) * gravity, pull.z * gravity));
		}
	}

	/**
	 * Over a rounded edge the sea stands in steps. Vanilla counts a boat as under water when water anywhere over its
	 * wide box reaches above its top, and the water of the next higher step did, so the boat sank at each step. On the
	 * curve only the water over the middle of the boat counts; the higher step then lifts the boat as a water level.
	 */
	@Inject(method = "isUnderwater", at = @At("HEAD"), cancellable = true)
	private void cubism$underwaterOnCurve(CallbackInfoReturnable<AbstractBoat.Status> cir) {
		AbstractBoat self = (AbstractBoat) (Object) this;
		SeamLayout layout = SeamLayout.of(self.level());
		if (layout == null || !layout.inRoundedEdgeBand(Mth.floor(self.getX()), Mth.floor(self.getZ()))) {
			return;
		}
		AABB box = self.getBoundingBox();
		BlockPos pos = BlockPos.containing(self.getX(), box.maxY, self.getZ());
		FluidState fluid = self.level().getFluidState(pos);
		if (fluid.is(FluidTags.WATER) && box.maxY + 0.001 < pos.getY() + fluid.getHeight(self.level(), pos)) {
			cir.setReturnValue(fluid.isSource() ? AbstractBoat.Status.UNDER_WATER : AbstractBoat.Status.UNDER_FLOWING_WATER);
		} else {
			cir.setReturnValue(null);
		}
	}
}

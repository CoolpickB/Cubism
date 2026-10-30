package dev.cubeworld.mixin;

import dev.cubeworld.gravity.GravityEntityAccess;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Boats add their own pull in {@code floatBoat}, not in {@code applyGravity}. In the air over an edge, that pull turns
 * to the edge pull, as for other entities. Without it, a boat that falls off an edge crosses the diagonal back and
 * forth.
 */
@Mixin(AbstractBoat.class)
public abstract class AbstractBoatMixin {
	@Shadow private AbstractBoat.Status status;

	@Inject(method = "floatBoat", at = @At("TAIL"))
	private void cubeworld$edgeGravity(CallbackInfo ci) {
		AbstractBoat self = (AbstractBoat) (Object) this;
		Vec3 pull = ((GravityEntityAccess) self).cubeworld$edgePull();
		if (pull != null && this.status == AbstractBoat.Status.IN_AIR) {
			double gravity = self.getGravity();
			self.setDeltaMovement(self.getDeltaMovement().add(pull.x * gravity, (pull.y + 1.0) * gravity, pull.z * gravity));
		}
	}
}

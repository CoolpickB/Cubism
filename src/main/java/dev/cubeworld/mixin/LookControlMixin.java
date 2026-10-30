package dev.cubeworld.mixin;

import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A mob looks at a point of another face region where the point shows. All look targets pass here: entities, and
 * the position trackers of brain-based mobs.
 */
@Mixin(LookControl.class)
public abstract class LookControlMixin {
	@Shadow @Final protected Mob mob;

	@Shadow
	public abstract void setLookAt(double x, double y, double z, float yMaxRotSpeed, float xMaxRotAngle);

	@Inject(method = "setLookAt(DDDFF)V", at = @At("HEAD"), cancellable = true)
	private void cubeworld$lookAcrossSeam(double x, double y, double z, float yMaxRotSpeed, float xMaxRotAngle, CallbackInfo ci) {
		SeamLayout layout = SeamLayout.of(this.mob.level());
		int[] m = layout == null ? null : SeamInteraction.regionMotion(layout, x, z, this.mob.getX(), this.mob.getZ());
		if (m != null) {
			// The moved point is in the region of the mob, so the second call passes.
			Vec3 shown = SeamInteraction.move(m, new Vec3(x, y, z));
			this.setLookAt(shown.x, shown.y, shown.z, yMaxRotSpeed, xMaxRotAngle);
			ci.cancel();
		}
	}
}

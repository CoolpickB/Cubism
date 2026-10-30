package dev.cubeworld.mixin;

import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A mob looks at an entity across a seam where the entity shows. */
@Mixin(LookControl.class)
public abstract class LookControlMixin {
	@Shadow @Final protected Mob mob;

	@Shadow
	public abstract void setLookAt(double x, double y, double z, float yMaxRotSpeed, float xMaxRotAngle);

	@Inject(method = "setLookAt(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
	private void cubeworld$lookAcrossSeam(Entity target, CallbackInfo ci) {
		this.cubeworld$lookAcrossSeam(target, this.mob.getHeadRotSpeed(), this.mob.getMaxHeadXRot(), ci);
	}

	@Inject(method = "setLookAt(Lnet/minecraft/world/entity/Entity;FF)V", at = @At("HEAD"), cancellable = true)
	private void cubeworld$lookAcrossSeam(Entity target, float yMaxRotSpeed, float xMaxRotAngle, CallbackInfo ci) {
		Vec3 eye = this.cubeworld$shownEye(target);
		if (eye != null) {
			this.setLookAt(eye.x, eye.y, eye.z, yMaxRotSpeed, xMaxRotAngle);
			ci.cancel();
		}
	}

	@Unique
	private @Nullable Vec3 cubeworld$shownEye(Entity target) {
		SeamLayout layout = SeamLayout.of(this.mob.level());
		int[] m = layout == null ? null : SeamInteraction.regionMotion(layout, target.getX(), target.getZ(), this.mob.getX(), this.mob.getZ());
		return m == null ? null : SeamInteraction.move(m, new Vec3(target.getX(), target.getEyeY(), target.getZ()));
	}
}

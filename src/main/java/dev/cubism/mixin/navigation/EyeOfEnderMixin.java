package dev.cubism.mixin.navigation;

import dev.cubism.cube.SeamLayout;
import dev.cubism.cube.SeamPaths;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The eye flies toward the stronghold as it appears in the unfolded plane of the face that it was thrown on. For a
 * stronghold on another face that is toward the edge on the way there, over two edges for the opposite face. Past the
 * edge the eye crosses to the next face like any other entity, and the player throws it again from there.
 */
@Mixin(EyeOfEnder.class)
public abstract class EyeOfEnderMixin {
	@ModifyVariable(method = "signalTo", at = @At("HEAD"), argsOnly = true)
	private Vec3 cubism$unfoldTarget(Vec3 target) {
		Entity self = (Entity) (Object) this;
		SeamLayout layout = SeamLayout.of(self.level());
		return layout == null ? target : SeamPaths.aim(layout, self.position(), target);
	}
}

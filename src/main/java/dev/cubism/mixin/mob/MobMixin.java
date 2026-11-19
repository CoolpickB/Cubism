package dev.cubism.mixin.mob;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.cube.SeamLayout;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A mob hits a target across a seam where the target shows. Despawning needs no change here: it reads the nearest
 * player and its distance through the seam-aware distances of {@link EntityMixin}.
 */
@Mixin(Mob.class)
public abstract class MobMixin {
	@ModifyExpressionValue(method = "isWithinMeleeAttackRange", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getHitbox()Lnet/minecraft/world/phys/AABB;"))
	private AABB cubism$targetHitboxAcrossSeam(AABB hitbox) {
		Mob self = (Mob) (Object) this;
		SeamLayout layout = SeamLayout.of(self.level());
		return layout == null ? hitbox : SeamInteraction.intoRegionOf(layout, hitbox, self.getX(), self.getZ());
	}
}

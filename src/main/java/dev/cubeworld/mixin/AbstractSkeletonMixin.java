package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** A skeleton aims at a target across a seam where the target shows. The arrow crosses the seam in flight. */
@Mixin(AbstractSkeleton.class)
public abstract class AbstractSkeletonMixin {
	@WrapOperation(method = "performRangedAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getX()D"))
	private double cubeworld$aimX(LivingEntity target, Operation<Double> original) {
		return this.cubeworld$shown(target, original.call(target), target.getY(), target.getZ()).x;
	}

	@WrapOperation(method = "performRangedAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getY(D)D"))
	private double cubeworld$aimY(LivingEntity target, double scale, Operation<Double> original) {
		return this.cubeworld$shown(target, target.getX(), original.call(target, scale), target.getZ()).y;
	}

	@WrapOperation(method = "performRangedAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getZ()D"))
	private double cubeworld$aimZ(LivingEntity target, Operation<Double> original) {
		return this.cubeworld$shown(target, target.getX(), target.getY(), original.call(target)).z;
	}

	/** A point of the target region moved into the region of this skeleton. */
	@Unique
	private Vec3 cubeworld$shown(LivingEntity target, double x, double y, double z) {
		AbstractSkeleton self = (AbstractSkeleton) (Object) this;
		SeamLayout layout = SeamLayout.of(self.level());
		Vec3 point = new Vec3(x, y, z);
		return layout == null ? point : SeamInteraction.intoRegionOf(layout, point, self.getX(), self.getZ());
	}
}

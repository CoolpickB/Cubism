package dev.cubeworld.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Sets the position fields without the section and chunk updates of {@link Entity#setPos}. */
@Mixin(Entity.class)
public interface EntityAccessor {
	@Accessor("position")
	void cubeworld$setPositionField(Vec3 position);

	@Accessor("bb")
	void cubeworld$setBoundingBoxField(AABB bb);

	@Accessor("deltaMovement")
	void cubeworld$setDeltaMovementField(Vec3 deltaMovement);
}

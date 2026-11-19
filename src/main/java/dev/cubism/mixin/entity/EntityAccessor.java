package dev.cubism.mixin.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Sets the position fields without the section and chunk updates of {@link Entity#setPos}. */
@Mixin(Entity.class)
public interface EntityAccessor {
	@Accessor("position")
	void cubism$setPositionField(Vec3 position);

	@Accessor("bb")
	void cubism$setBoundingBoxField(AABB bb);

	@Accessor("deltaMovement")
	void cubism$setDeltaMovementField(Vec3 deltaMovement);
}

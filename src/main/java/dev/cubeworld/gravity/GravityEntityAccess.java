package dev.cubeworld.gravity;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public interface GravityEntityAccess {
	/** The direction that gravity pulls this entity. */
	Direction cubeworld$getGravityDirection();

	/**
	 * The frame of the entity's velocity, rotation, and hitbox. It is the gravity direction for most entities.
	 * Projectiles and minecarts keep the world frame and only fall along their gravity.
	 */
	Direction cubeworld$getFrame();

	/** Changes gravity if the turned hitbox fits. Returns false when the change must wait. */
	boolean cubeworld$setGravityDirection(Direction direction);

	/** Applies a gravity change from the other side without a fit test. */
	void cubeworld$forceGravityDirection(Direction direction);

	/**
	 * The pull over an edge of a seam world, as a unit vector, or null where the vanilla pull applies. Call it once
	 * per tick: it also notes the face where a fall starts.
	 */
	@Nullable Vec3 cubeworld$edgePull();
}

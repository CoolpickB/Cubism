package dev.cubeworld.gravity;

import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public interface GravityEntityAccess {
	/**
	 * The pull over an edge of a seam world, as a unit vector, or null where the vanilla pull applies. Call it once
	 * per tick: it also notes the face where a fall starts.
	 */
	@Nullable Vec3 cubeworld$edgePull();
}

package dev.cubism.gravity;

import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public interface GravityEntityAccess {
	/**
	 * The pull over an edge of a seam world, as a unit vector, or null where the vanilla pull applies. Call it once
	 * per tick: it also notes the face where a fall starts.
	 */
	@Nullable Vec3 cubism$edgePull();

	/** The face that the entity left for space, or -1. A Steadfast lock in space keeps the down of that face. */
	int cubism$spaceFace();

	void cubism$setSpaceFace(int face);
}

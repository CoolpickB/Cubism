package dev.cubism.client.compat.entityculling;

import dev.tr7zw.entityculling.access.Cullable;
import net.minecraft.world.entity.Entity;

/** Calls into Entity Culling. Only reached when Entity Culling is installed. */
public final class EntityCullingLink {
	private EntityCullingLink() {}

	/**
	 * Marks an entity that the seam renderer draws on another face as seen. Entity Culling marks an entity as seen
	 * only in the vanilla extract loop, which skips these entities, and it gives an entity that is not seen only a
	 * basic tick, so its walk animation froze.
	 */
	public static void shown(Entity entity) {
		Cullable cullable = (Cullable) entity;
		cullable.setOutOfCamera(false);
		cullable.setCulled(false);
	}
}

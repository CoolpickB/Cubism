package dev.cubism.client.compat;

import dev.cubism.client.SeamClient;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * Where the minimap and the world map of Xaero put the local player while it is in the space region. Space is stored
 * 10 million blocks away from the faces, so the maps showed an empty area there. The player is pinned to a point past
 * the middle of the row of faces instead: the minimap shows nothing, and the world map opens close enough to the
 * faces to pan to them.
 */
public final class XaeroSpace {
	/** How far the pin is past the strips of the faces, more than the minimap shows when zoomed out. */
	private static final double MARGIN = 512.0;

	private XaeroSpace() {}

	public static boolean pinned(Entity entity) {
		SeamLayout layout = SeamClient.layout;
		return layout != null && entity instanceof LocalPlayer && layout.regionOfX(Mth.floor(entity.getX())) == SeamLayout.SPACE;
	}

	public static double x() {
		SeamLayout layout = SeamClient.layout;
		return (layout.offsetX(0) + layout.offsetX(SeamLayout.FACES.length - 1)) / 2.0;
	}

	public static double z() {
		return -(SeamClient.layout.spacing / 2.0 + MARGIN);
	}
}

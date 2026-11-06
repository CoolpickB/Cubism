package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.cubeworld.worldgen.SeamLayout;
import dev.cubeworld.worldgen.SeamPaths;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * A map shows the flat plane of the face that it was made on. A player, frame or banner on another face is placed where
 * it appears in that plane (see {@link SeamPaths}), so it shows as an off-map arrow on the side of the edge that leads
 * to it, and a marker on the face itself is placed as before.
 */
@Mixin(MapItemSavedData.class)
public abstract class MapItemSavedDataMixin {
	@Shadow
	@Final
	public int centerX;
	@Shadow
	@Final
	public int centerZ;
	@Shadow
	@Final
	public ResourceKey<Level> dimension;

	@WrapMethod(method = "addDecoration")
	private void cubeworld$placeOnFacePlane(Holder<MapDecorationType> type, @Nullable LevelAccessor level, String key, double xPos,
		double zPos, double yRot, @Nullable Component name, Operation<Void> original) {
		SeamLayout layout = this.dimension == Level.OVERWORLD ? SeamLayout.active() : null;
		if (layout == null) {
			original.call(type, level, key, xPos, zPos, yRot, name);
			return;
		}
		Vec3 center = new Vec3(this.centerX, 0.0, this.centerZ);
		Vec3 pos = new Vec3(xPos, 0.0, zPos);
		// The height comes from the player at the marker. The heightmap is no use: near a diagonal the storage column
		// under a player holds only cleared air. Banners and frames go where plain unfolding puts them.
		Player player = null;
		if (level != null) {
			for (Player candidate : level.players()) {
				if (candidate.getX() == xPos && candidate.getZ() == zPos) {
					player = candidate;
					break;
				}
			}
		}
		Vec3 flat = player == null ? SeamPaths.aim(layout, center, pos) : SeamPaths.aimOnMap(layout, center, player.position());
		original.call(type, level, key, flat.x, flat.z, (double) SeamPaths.aimYaw(layout, center, pos, (float) yRot), name);
	}
}

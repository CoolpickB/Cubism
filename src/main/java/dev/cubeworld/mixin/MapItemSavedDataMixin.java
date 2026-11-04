package dev.cubeworld.mixin;

import dev.cubeworld.worldgen.SeamLayout;
import dev.cubeworld.worldgen.SeamPaths;
import net.minecraft.core.Holder;
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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

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

	@ModifyVariable(method = "addDecoration", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private double cubeworld$x(double xPos, Holder<MapDecorationType> type, @Nullable LevelAccessor level, String key, double xx, double zPos, double yRot, @Nullable Component name) {
		Vec3 flat = this.cubeworld$flat(xPos, zPos);
		return flat == null ? xPos : flat.x;
	}

	@ModifyVariable(method = "addDecoration", at = @At("HEAD"), argsOnly = true, ordinal = 1)
	private double cubeworld$z(double zPos, Holder<MapDecorationType> type, @Nullable LevelAccessor level, String key, double xPos, double zz, double yRot, @Nullable Component name) {
		Vec3 flat = this.cubeworld$flat(xPos, zPos);
		return flat == null ? zPos : flat.z;
	}

	@ModifyVariable(method = "addDecoration", at = @At("HEAD"), argsOnly = true, ordinal = 2)
	private double cubeworld$yaw(double yRot, Holder<MapDecorationType> type, @Nullable LevelAccessor level, String key, double xPos, double zPos, double yy, @Nullable Component name) {
		SeamLayout layout = this.dimension == Level.OVERWORLD ? SeamLayout.active() : null;
		return layout == null ? yRot : SeamPaths.aimYaw(layout, new Vec3(this.centerX, 0.0, this.centerZ), new Vec3(xPos, 0.0, zPos), (float) yRot);
	}

	private @Nullable Vec3 cubeworld$flat(double xPos, double zPos) {
		SeamLayout layout = this.dimension == Level.OVERWORLD ? SeamLayout.active() : null;
		return layout == null ? null : SeamPaths.aim(layout, new Vec3(this.centerX, 0.0, this.centerZ), new Vec3(xPos, 0.0, zPos));
	}
}

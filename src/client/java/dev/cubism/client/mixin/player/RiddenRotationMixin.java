package dev.cubism.client.mixin.player;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.cubism.client.CubeFlight;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.animal.nautilus.AbstractNautilus;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A mount that the local player steers turns to the screen heading. Near an edge the view frame tilts the camera, and
 * a look far down swings the yaw of the face from tick to tick, so the mount went in circles on the spot.
 */
@Mixin({AbstractHorse.class, HappyGhast.class, AbstractNautilus.class})
public abstract class RiddenRotationMixin {
	@ModifyReturnValue(method = "getRiddenRotation", at = @At("RETURN"))
	private Vec2 cubism$screenHeading(Vec2 rotation, LivingEntity controller) {
		return controller instanceof LocalPlayer player ? new Vec2(rotation.x, CubeFlight.moveYaw(player)) : rotation;
	}
}

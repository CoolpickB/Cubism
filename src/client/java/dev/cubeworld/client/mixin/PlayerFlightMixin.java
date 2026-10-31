package dev.cubeworld.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.cubeworld.client.CubeFlight;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

/** The local player flies with the physics of vanilla in the air frame of {@link CubeFlight}. */
@Mixin(Player.class)
public abstract class PlayerFlightMixin {
	@WrapMethod(method = "travel")
	private void cubeworld$travelInAirFrame(Vec3 input, Operation<Void> original) {
		if (!((Object) this instanceof LocalPlayer player) || !CubeFlight.enterFrame(player)) {
			original.call(input);
			return;
		}
		try {
			original.call(input);
		} finally {
			CubeFlight.leaveFrame(player);
		}
	}
}

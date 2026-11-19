package dev.cubism.client.mixin.sky;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.cubism.client.render.CubeSky;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The weather is below a camera over the stratosphere. On the client the rain fades out there, so the sun, moon and
 * stars, the colors of the sky, the fog, the rain columns and the sounds all go back to clear weather together. The
 * thunder level of vanilla is a share of the rain level, so it fades too. The integrated server shares this class.
 */
@Mixin(Level.class)
public abstract class LevelRainMixin {
	@ModifyReturnValue(method = "getRainLevel", at = @At("RETURN"))
	private float cubism$clearOverAir(float rain) {
		if (rain <= 0.0F || !((Level) (Object) this).isClientSide() || !CubeSky.active()) {
			return rain;
		}
		return rain * (1.0F - CubeSky.fogClear());
	}
}

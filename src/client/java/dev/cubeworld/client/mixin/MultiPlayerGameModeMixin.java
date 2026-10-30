package dev.cubeworld.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
	// The server reads the hit as an offset from the entity. A hit across a seam shows outside the region of the entity.
	@ModifyExpressionValue(method = "interact", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/EntityHitResult;getLocation()Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 cubeworld$hitInEntityRegion(Vec3 location, @Local(argsOnly = true) Entity entity) {
		SeamLayout layout = SeamLayout.of(entity.level());
		return layout == null ? location : SeamInteraction.intoRegionOf(layout, location, entity.getX(), entity.getZ());
	}
}

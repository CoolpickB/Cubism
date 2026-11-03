package dev.cubeworld.client.mixin;

import dev.cubeworld.client.SeamClient;
import dev.cubeworld.worldgen.SeamLayout;
import dev.cubeworld.worldgen.SeamPaths;
import net.minecraft.client.renderer.item.properties.numeric.CompassAngleState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** A compass points the way over the edges to a target on another face, as the straight line in the unfolded plane. */
@Mixin(CompassAngleState.class)
public abstract class CompassAngleStateMixin {
	@ModifyVariable(method = "getAngleFromEntityToPos", at = @At("STORE"), ordinal = 0)
	private static Vec3 cubeworld$unfoldTarget(Vec3 target, ItemOwner owner) {
		SeamLayout layout = SeamClient.layout;
		return layout == null ? target : SeamPaths.aim(layout, owner.position(), target);
	}
}

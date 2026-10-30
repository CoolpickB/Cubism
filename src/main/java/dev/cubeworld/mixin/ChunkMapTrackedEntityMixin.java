package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * An entity on another face is stored hundreds of blocks from the player, but it shows across the seam next to the
 * player. Its tracking distance is measured to the player position moved into the region of the entity. Vanilla
 * still requires that the player tracks the chunk of the entity, which holds only for chunks seen across seams.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class ChunkMapTrackedEntityMixin {
	@Shadow @Final private Entity entity;

	@ModifyExpressionValue(method = "updatePlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;position()Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 cubeworld$playerInEntityFrame(Vec3 player) {
		SeamLayout layout = SeamLayout.of(this.entity.level());
		if (layout == null) {
			return player;
		}
		int playerFace = SeamInteraction.regionFace(layout, player.x, player.z);
		int entityFace = SeamInteraction.regionFace(layout, this.entity.getX(), this.entity.getZ());
		return playerFace < 0 || entityFace < 0 || playerFace == entityFace ? player : SeamInteraction.move(layout.motion(playerFace, entityFace), player);
	}
}

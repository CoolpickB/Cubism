package dev.cubism.client.mixin;

import dev.cubism.client.TurnableAvatarState;
import dev.cubism.seam.SeamInteraction;
import net.minecraft.client.entity.ClientAvatarState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** The glide pose reads the velocity of the last tick, and the cape trails the position of the player. */
@Mixin(ClientAvatarState.class)
public abstract class ClientAvatarStateMixin implements TurnableAvatarState {
	@Shadow private Vec3 deltaMovementOnPreviousTick;
	@Shadow private double xCloak;
	@Shadow private double yCloak;
	@Shadow private double zCloak;
	@Shadow private double xCloakO;
	@Shadow private double yCloakO;
	@Shadow private double zCloakO;

	@Override
	public void cubism$turn(int[] motion, double halfHeight) {
		this.deltaMovementOnPreviousTick = SeamInteraction.rotate(motion, this.deltaMovementOnPreviousTick);
		Vec3 cloak = SeamInteraction.move(motion, new Vec3(this.xCloak, this.yCloak + halfHeight, this.zCloak));
		Vec3 cloakO = SeamInteraction.move(motion, new Vec3(this.xCloakO, this.yCloakO + halfHeight, this.zCloakO));
		this.xCloak = cloak.x;
		this.yCloak = cloak.y - halfHeight;
		this.zCloak = cloak.z;
		this.xCloakO = cloakO.x;
		this.yCloakO = cloakO.y - halfHeight;
		this.zCloakO = cloakO.z;
	}
}

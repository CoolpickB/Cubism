package dev.cubeworld.client;

import net.minecraft.core.Direction;

public interface GravityRenderStateAccess {
	Direction cubeworld$getRenderGravity();
	void cubeworld$setRenderGravity(Direction direction);
}

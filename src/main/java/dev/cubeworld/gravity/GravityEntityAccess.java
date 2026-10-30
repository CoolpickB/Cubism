package dev.cubeworld.gravity;

import net.minecraft.core.Direction;
import org.joml.Quaternionf;

public interface GravityEntityAccess {
	Direction cubeworld$getGravityDirection();
	void cubeworld$setGravityDirection(Direction direction);
	Quaternionf cubeworld$getRenderCameraRotation(float partialTicks);
}

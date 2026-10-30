package dev.cubeworld.client.mixin;

import dev.cubeworld.client.GravityRenderStateAccess;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public class EntityRenderStateMixin implements GravityRenderStateAccess {
	@Unique
	private Direction cubeworld$renderGravity = Direction.DOWN;

	@Override
	public Direction cubeworld$getRenderGravity() {
		return this.cubeworld$renderGravity;
	}

	@Override
	public void cubeworld$setRenderGravity(Direction direction) {
		this.cubeworld$renderGravity = direction != null ? direction : Direction.DOWN;
	}
}

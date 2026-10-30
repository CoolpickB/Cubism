package dev.cubeworld.mixin;

import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RandomState.class)
public interface RandomStateAccessor {
	@Accessor("router")
	NoiseRouter cubeworld$getRouter();

	@Mutable
	@Accessor("router")
	void cubeworld$setRouter(NoiseRouter router);
}

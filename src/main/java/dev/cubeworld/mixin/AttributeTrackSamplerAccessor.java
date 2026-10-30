package dev.cubeworld.mixin;

import net.minecraft.core.Holder;
import net.minecraft.util.KeyframeTrackSampler;
import net.minecraft.world.attribute.modifier.AttributeModifier;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.timeline.AttributeTrackSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets a cube timeline layer sample a track at a local time, not at the time of the clock. */
@Mixin(AttributeTrackSampler.class)
public interface AttributeTrackSamplerAccessor<V, A> {
	@Accessor("clock")
	Holder<WorldClock> cubeworld$clock();

	@Accessor("modifier")
	AttributeModifier<V, A> cubeworld$modifier();

	@Accessor("argumentSampler")
	KeyframeTrackSampler<A> cubeworld$argumentSampler();
}

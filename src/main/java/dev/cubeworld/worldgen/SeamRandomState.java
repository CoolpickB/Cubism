package dev.cubeworld.worldgen;

/** Added to {@link net.minecraft.world.level.levelgen.RandomState} so that its samplers work in seam storage. */
public interface SeamRandomState {
	void cubeworld$enableSeams(SeamLayout layout);
}

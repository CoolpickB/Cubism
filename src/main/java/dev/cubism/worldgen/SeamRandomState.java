package dev.cubism.worldgen;

/** Added to {@link net.minecraft.world.level.levelgen.RandomState} so that its samplers work in seam storage. */
public interface SeamRandomState {
	void cubism$enableSeams(SeamLayout layout);
}

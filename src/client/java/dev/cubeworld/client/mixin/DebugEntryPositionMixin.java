package dev.cubeworld.client.mixin;

import dev.cubeworld.client.SeamClient;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntryPosition;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugEntryPosition.class)
public abstract class DebugEntryPositionMixin {
	@Inject(method = "display", at = @At("TAIL"))
	private void cubeworld$showCubeFace(
		DebugScreenDisplayer displayer, @Nullable Level level, @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk, CallbackInfo ci
	) {
		SeamLayout layout = SeamClient.layout;
		Entity camera = Minecraft.getInstance().getCameraEntity();
		if (layout == null || camera == null) {
			return;
		}
		int face = SeamInteraction.regionFace(layout, camera.getX(), camera.getZ());
		displayer.addToGroup(DebugEntryPosition.GROUP, "Cube face: " + (face < 0 ? "none" : SeamLayout.FACES[face].name()));
	}
}

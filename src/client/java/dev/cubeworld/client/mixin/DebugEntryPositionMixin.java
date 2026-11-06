package dev.cubeworld.client.mixin;

import dev.cubeworld.client.ClientConfig;
import dev.cubeworld.client.SeamClient;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntryPosition;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * On the cube the position lines show cube coordinates: 0 0 0 is the block at the center of the cube, and X, Y and Z
 * are the same axes on every face. The storage position follows on its own line when the client option showStorageLine is on.
 */
@Mixin(DebugEntryPosition.class)
public abstract class DebugEntryPositionMixin {
	@ModifyArg(method = "display", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/debug/DebugScreenDisplayer;addToGroup(Lnet/minecraft/resources/Identifier;Ljava/util/Collection;)V"), index = 1)
	private Collection<String> cubeworld$cubeCoordinates(Collection<String> lines) {
		SeamLayout layout = SeamClient.layout;
		Entity camera = Minecraft.getInstance().getCameraEntity();
		if (layout == null || camera == null) {
			return lines;
		}
		int face = SeamInteraction.regionFace(layout, camera.getX(), camera.getZ());
		if (face < 0) {
			return lines;
		}
		int[] m = layout.rotation(face);
		// The center of the cube is the center of a block, so positions turn about it and floor to the block position.
		double x = camera.getX() - layout.offsetX(face) - 0.5;
		double y = camera.getY() - layout.centerY - 0.5;
		double z = camera.getZ() - 0.5;
		BlockPos feet = camera.blockPosition();
		int[] block = new int[3];
		layout.toCube(face, feet.getX(), feet.getY(), feet.getZ(), block);
		Direction facing = camera.getDirection();
		Direction cubeFacing = Direction.getApproximateNearest(
			m[0] * facing.getStepX() + m[2] * facing.getStepZ(),
			m[3] * facing.getStepX() + m[5] * facing.getStepZ(),
			m[6] * facing.getStepX() + m[8] * facing.getStepZ()
		);
		List<String> replaced = new ArrayList<>(lines);
		replaced.set(0, String.format(Locale.ROOT, "XYZ: %.3f / %.5f / %.3f",
			m[0] * x + m[1] * y + m[2] * z + 0.5, m[3] * x + m[4] * y + m[5] * z + 0.5, m[6] * x + m[7] * y + m[8] * z + 0.5));
		replaced.set(1, String.format(Locale.ROOT, "Block: %d %d %d", block[0], block[1] - layout.centerY, block[2]));
		replaced.set(3, String.format(Locale.ROOT, "Facing: %s (Towards %s %s) (%.1f / %.1f)", cubeFacing,
			cubeFacing.getAxisDirection() == Direction.AxisDirection.POSITIVE ? "positive" : "negative",
			cubeFacing.getAxis().getName().toUpperCase(Locale.ROOT), Mth.wrapDegrees(camera.getYRot()), Mth.wrapDegrees(camera.getXRot())));
		if (ClientConfig.showStorageLine) {
			replaced.add(2, String.format(Locale.ROOT, "Storage: %.3f / %.5f / %.3f (block %d %d %d)",
				camera.getX(), camera.getY(), camera.getZ(), feet.getX(), feet.getY(), feet.getZ()));
		}
		return replaced;
	}

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
		// In single player the level here is the one of the server, which holds other chunks than the client.
		if (ClientConfig.showStorageLine && Minecraft.getInstance().level instanceof net.minecraft.client.multiplayer.ClientLevel clientLevel) {
			displayer.addToGroup(DebugEntryPosition.GROUP, cubeworld$holes(layout, clientLevel, camera));
		}
	}

	/** The chunks within 10 of the camera that the client lacks, and with Sodium those it has but does not draw. */
	@org.spongepowered.asm.mixin.Unique
	private static String cubeworld$holes(SeamLayout layout, net.minecraft.client.multiplayer.ClientLevel level, Entity camera) {
		int cx = camera.getBlockX() >> 4;
		int cz = camera.getBlockZ() >> 4;
		StringBuilder missing = new StringBuilder();
		StringBuilder waiting = new StringBuilder();
		int missingCount = 0;
		int waitingCount = 0;
		for (int x = cx - 10; x <= cx + 10; x++) {
			for (int z = cz - 10; z <= cz + 10; z++) {
				if (!layout.inRegion(x * 16 + 8, z * 16 + 8)) {
					continue;
				}
				if (level.getChunkSource().getChunk(x, z, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) == null) {
					if (missingCount++ < 4) {
						missing.append(' ').append(x).append(',').append(z);
					}
				} else if (SeamClient.SODIUM && !dev.cubeworld.client.sodiumseam.SodiumLink.isReady(level, x, z)) {
					if (waitingCount++ < 4) {
						waiting.append(' ').append(x).append(',').append(z);
					}
				}
			}
		}
		return "Chunks missing: " + missingCount + missing + " | not drawn: " + waitingCount + waiting;
	}
}

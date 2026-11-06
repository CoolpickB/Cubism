package dev.cubism.client.mixin;

import dev.cubism.client.SeamClient;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Locale;

/** F3 + C copies the position as cube coordinates, which is what the commands take (see the server's WorldCoordinatesMixin). */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	@ModifyArg(
		method = "handleDebugKeys",
		at = @At(value = "INVOKE", target = "Ljava/lang/String;format(Ljava/util/Locale;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;", ordinal = 0),
		index = 2
	)
	private Object[] cubism$cubeLocation(Object[] arguments) {
		SeamLayout layout = SeamClient.layout;
		Player player = Minecraft.getInstance().player;
		if (layout == null || player == null || arguments.length != 6) {
			return arguments;
		}
		Vec3 cube = layout.storageToCube(player.position());
		if (cube == null) {
			return arguments;
		}
		return new Object[] {arguments[0], cube.x, cube.y, cube.z, arguments[4], arguments[5]};
	}

	@ModifyArg(
		method = "copyCreateBlockCommand",
		at = @At(value = "INVOKE", target = "Ljava/lang/String;format(Ljava/util/Locale;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"),
		index = 2
	)
	private Object[] cubism$cubeBlock(Object[] arguments) {
		SeamLayout layout = SeamClient.layout;
		if (layout == null || arguments.length != 4) {
			return arguments;
		}
		int face = SeamInteraction.regionFace(layout, (Integer) arguments[0], (Integer) arguments[2]);
		if (face < 0) {
			return arguments;
		}
		int[] cube = new int[3];
		layout.toCube(face, (Integer) arguments[0], (Integer) arguments[1], (Integer) arguments[2], cube);
		return new Object[] {cube[0], cube[1] - layout.centerY, cube[2], arguments[3]};
	}

	@ModifyArg(
		method = "copyCreateEntityCommand",
		at = @At(value = "INVOKE", target = "Ljava/lang/String;format(Ljava/util/Locale;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"),
		index = 2
	)
	private Object[] cubism$cubeEntity(Object[] arguments) {
		SeamLayout layout = SeamClient.layout;
		if (layout == null) {
			return arguments;
		}
		Vec3 cube = layout.storageToCube(new Vec3((Double) arguments[1], (Double) arguments[2], (Double) arguments[3]));
		if (cube == null) {
			return arguments;
		}
		Object[] replaced = arguments.clone();
		replaced[1] = cube.x;
		replaced[2] = cube.y;
		replaced[3] = cube.z;
		return replaced;
	}
}

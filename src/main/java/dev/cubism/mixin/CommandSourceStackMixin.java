package dev.cubism.mixin;

import dev.cubism.worldgen.SeamLayout;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Map;
import java.util.function.Supplier;

/**
 * The answer of a command that names a position prints it in cube coordinates, to match the commands that take them
 * (see {@link WorldCoordinatesMixin}). The vanilla messages are built without the level, so they are turned here, by key.
 */
@Mixin(CommandSourceStack.class)
public abstract class CommandSourceStackMixin {
	/** Message key to the index of the first of three position arguments and whether they are block (int) or exact (text) numbers. */
	private static final Map<String, Integer> POSITION_AT = Map.of(
		"commands.teleport.success.location.single", 1,
		"commands.teleport.success.location.multiple", 1,
		"commands.spawnpoint.success.single", 0,
		"commands.spawnpoint.success.multiple", 0,
		"commands.setworldspawn.success", 0,
		"commands.setblock.success", 0
	);

	@Shadow
	public abstract net.minecraft.server.level.ServerLevel getLevel();

	@ModifyVariable(method = "sendSuccess", at = @At("HEAD"), argsOnly = true)
	private Supplier<Component> cubism$cubeCoordinates(Supplier<Component> supplier) {
		SeamLayout layout = SeamLayout.of(this.getLevel());
		if (layout == null) {
			return supplier;
		}
		return () -> {
			Component message = supplier.get();
			if (!(message.getContents() instanceof TranslatableContents contents) || !POSITION_AT.containsKey(contents.getKey())) {
				return message;
			}
			int at = POSITION_AT.get(contents.getKey());
			Object[] args = contents.getArgs().clone();
			Vec3 cube;
			boolean block = args[at] instanceof Integer;
			if (block) {
				int[] out = new int[3];
				int x = (Integer) args[at];
				int z = (Integer) args[at + 2];
				if (!layout.inRegion(x, z)) {
					return message;
				}
				layout.toCube(layout.faceIndexAt(x), x, (Integer) args[at + 1], z, out);
				cube = new Vec3(out[0], out[1] - layout.centerY, out[2]);
			} else {
				Vec3 storage = new Vec3(Double.parseDouble((String) args[at]), Double.parseDouble((String) args[at + 1]), Double.parseDouble((String) args[at + 2]));
				cube = layout.storageToCube(storage);
				if (cube == null) {
					return message;
				}
			}
			for (int i = 0; i < 3; i++) {
				double value = i == 0 ? cube.x : i == 1 ? cube.y : cube.z;
				args[at + i] = block ? (Object) (int) value : String.format(java.util.Locale.ROOT, "%f", value);
			}
			return Component.translatable(contents.getKey(), args).withStyle(message.getStyle());
		};
	}
}

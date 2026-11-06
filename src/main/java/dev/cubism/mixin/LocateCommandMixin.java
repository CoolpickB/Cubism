package dev.cubism.mixin;

import com.mojang.datafixers.util.Pair;
import dev.cubism.worldgen.SeamLayout;
import dev.cubism.worldgen.SeamPaths;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.commands.LocateCommand;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.time.Duration;

/**
 * The answer of /locate in cube coordinates, to match the commands that take them (see {@link WorldCoordinatesMixin}), with
 * the distance over the surface. The height is the height of the sender, as the {@code ~} of vanilla is, but written out,
 * because a cube position needs all three numbers to name its face.
 */
@Mixin(LocateCommand.class)
public abstract class LocateCommandMixin {
	@Inject(
		method = "showLocateResult(Lnet/minecraft/commands/CommandSourceStack;Lnet/minecraft/core/BlockPos;Lcom/mojang/datafixers/util/Pair;Ljava/lang/String;ZLjava/lang/String;Ljava/time/Duration;)I",
		at = @At("HEAD"),
		cancellable = true
	)
	private static void cubism$showCubeCoordinates(
		CommandSourceStack source, BlockPos sourcePos, Pair<BlockPos, ? extends Holder<?>> found, String successMessageKey,
		boolean includeY, String foundName, Duration taskDuration, CallbackInfoReturnable<Integer> cir
	) {
		SeamLayout layout = SeamLayout.of(source.getLevel());
		if (layout == null) {
			return;
		}
		BlockPos foundPos = found.getFirst();
		Vec3 from = source.getPosition();
		Vec3 target = new Vec3(foundPos.getX() + 0.5, includeY ? foundPos.getY() + 0.5 : from.y, foundPos.getZ() + 0.5);
		Vec3 cube = layout.storageToCube(target);
		double surface = SeamPaths.distance(layout, from, target);
		if (cube == null || surface < 0.0) {
			return;
		}
		int distance = Mth.floor(includeY ? Math.hypot(surface, target.y - from.y) : surface);
		int x = Mth.floor(cube.x);
		int y = Mth.floor(cube.y);
		int z = Mth.floor(cube.z);
		String displayedY = includeY ? String.valueOf(y) : "~";
		Component coordinates = ComponentUtils.wrapInSquareBrackets(Component.translatable("chat.coordinates", x, displayedY, z))
			.withStyle(
				s -> s.withColor(ChatFormatting.GREEN)
					.withClickEvent(new ClickEvent.SuggestCommand("/tp @s " + x + " " + y + " " + z))
					.withHoverEvent(new HoverEvent.ShowText(Component.translatable("chat.coordinates.tooltip")))
			);
		source.sendSuccess(() -> Component.translatable(successMessageKey, foundName, coordinates, distance), false);
		cir.setReturnValue(distance);
	}
}

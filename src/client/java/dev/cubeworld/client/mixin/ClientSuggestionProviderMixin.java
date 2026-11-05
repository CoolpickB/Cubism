package dev.cubeworld.client.mixin;

import dev.cubeworld.client.SeamClient;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.commands.SharedSuggestionProvider.TextCoordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tab completion of coordinates fills in the block that is looked at in cube coordinates, which is what the commands take. */
@Mixin(ClientSuggestionProvider.class)
public abstract class ClientSuggestionProviderMixin {
	@Inject(method = "getRelevantCoordinates", at = @At("RETURN"), cancellable = true)
	private void cubeworld$cubeBlock(CallbackInfoReturnable<Collection<TextCoordinates>> cir) {
		SeamLayout layout = SeamClient.layout;
		HitResult hit = Minecraft.getInstance().hitResult;
		if (layout == null || hit == null || hit.getType() != HitResult.Type.BLOCK) {
			return;
		}
		BlockPos pos = ((BlockHitResult) hit).getBlockPos();
		int face = SeamInteraction.regionFace(layout, pos.getX(), pos.getZ());
		if (face < 0) {
			return;
		}
		int[] cube = new int[3];
		layout.toCube(face, pos.getX(), pos.getY(), pos.getZ(), cube);
		cir.setReturnValue(Collections.singleton(new TextCoordinates(
			Integer.toString(cube[0]), Integer.toString(cube[1] - layout.centerY), Integer.toString(cube[2]))));
	}

	@Inject(method = "getAbsoluteCoordinates", at = @At("RETURN"), cancellable = true)
	private void cubeworld$cubePoint(CallbackInfoReturnable<Collection<TextCoordinates>> cir) {
		SeamLayout layout = SeamClient.layout;
		HitResult hit = Minecraft.getInstance().hitResult;
		if (layout == null || hit == null || hit.getType() != HitResult.Type.BLOCK) {
			return;
		}
		Vec3 cube = layout.storageToCube(hit.getLocation());
		if (cube == null) {
			return;
		}
		cir.setReturnValue(Collections.singleton(new TextCoordinates(
			String.format(Locale.ROOT, "%.2f", cube.x), String.format(Locale.ROOT, "%.2f", cube.y), String.format(Locale.ROOT, "%.2f", cube.z))));
	}
}

package dev.cubism.mixin;

import dev.cubism.worldgen.SeamLayout;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.WorldCoordinate;
import net.minecraft.commands.arguments.coordinates.WorldCoordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Absolute command coordinates are cube coordinates, the same as the F3 screen shows. When any part is absolute, the
 * parts that are relative are relative to the cube position of the sender, and the result is turned into the storage
 * position of the face that owns it. All-relative coordinates stay in the storage frame, so {@code ~ ~1 ~} still means
 * the block above the sender.
 */
@Mixin(WorldCoordinates.class)
public abstract class WorldCoordinatesMixin {
	@Shadow
	@Final
	private WorldCoordinate x;
	@Shadow
	@Final
	private WorldCoordinate y;
	@Shadow
	@Final
	private WorldCoordinate z;

	/** An integer cube coordinate names a block, whose center is half a block on, so that it maps to the block of the same name. */
	public BlockPos getBlockPos(CommandSourceStack sender) {
		if (!this.x.isRelative() || !this.y.isRelative() || !this.z.isRelative()) {
			SeamLayout layout = SeamLayout.of(sender.getLevel());
			Vec3 here = layout == null ? null : layout.storageToCube(sender.getPosition());
			if (here != null) {
				return BlockPos.containing(layout.cubeToStorage(
					Mth.floor(this.x.get(Mth.floor(here.x))) + 0.5, Mth.floor(this.y.get(Mth.floor(here.y))) + 0.5, Mth.floor(this.z.get(Mth.floor(here.z))) + 0.5
				));
			}
		}
		return BlockPos.containing(((Coordinates) (Object) this).getPosition(sender));
	}

	@Inject(method = "getPosition", at = @At("HEAD"), cancellable = true)
	private void cubism$cubeCoordinates(CommandSourceStack sender, CallbackInfoReturnable<Vec3> cir) {
		if (this.x.isRelative() && this.y.isRelative() && this.z.isRelative()) {
			return;
		}
		SeamLayout layout = SeamLayout.of(sender.getLevel());
		Vec3 here = layout == null ? null : layout.storageToCube(sender.getPosition());
		if (here != null) {
			cir.setReturnValue(layout.cubeToStorage(this.x.get(here.x), this.y.get(here.y), this.z.get(here.z)));
		}
	}
}

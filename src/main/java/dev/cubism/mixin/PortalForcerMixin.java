package dev.cubism.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.portal.PortalForcer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Outside its wedge, a face region is cleared air that stands for other faces. A new portal must not stand there, or
 * it would hang in the air of the next face. Every block of the portal and its frame space must be inside the wedge.
 */
@Mixin(PortalForcer.class)
public abstract class PortalForcerMixin {
	@Shadow
	@Final
	private ServerLevel level;

	@ModifyReturnValue(method = "canPortalReplaceBlock", at = @At("RETURN"))
	private boolean cubism$insideWedge(boolean replaceable, BlockPos.MutableBlockPos pos) {
		if (!replaceable) {
			return false;
		}
		SeamLayout layout = SeamLayout.of(this.level);
		return layout == null || layout.insideWedge(pos.getX(), pos.getY(), pos.getZ());
	}
}

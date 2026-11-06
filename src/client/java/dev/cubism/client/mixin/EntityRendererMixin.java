package dev.cubism.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.cubism.client.SeamClient;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * An entity near a diagonal can stand past its wedge, in space that the next face owns. The storage of its own region
 * holds no light there, so the entity looked dark until it crossed. The light comes from the owner of the block.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity> {
	@Shadow
	protected abstract int getBlockLightLevel(T entity, BlockPos pos);

	@Shadow
	protected abstract int getSkyLightLevel(T entity, BlockPos pos);

	@ModifyReturnValue(method = "getPackedLightCoords", at = @At("RETURN"))
	private int cubism$lightFromOwner(int lightCoords, T entity, float partialTickTime) {
		SeamLayout layout = SeamClient.layout;
		if (layout == null) {
			return lightCoords;
		}
		BlockPos pos = BlockPos.containing(entity.getLightProbePosition(partialTickTime));
		int[] owner = new int[3];
		if (!layout.inRegion(pos.getX(), pos.getZ()) || layout.insideWedge(pos.getX(), pos.getY(), pos.getZ())
			|| !layout.ownerStorage(pos.getX(), pos.getY(), pos.getZ(), owner)) {
			return lightCoords;
		}
		BlockPos ownerPos = new BlockPos(owner[0], owner[1], owner[2]);
		return LightCoordsUtil.pack(this.getBlockLightLevel(entity, ownerPos), this.getSkyLightLevel(entity, ownerPos));
	}
}

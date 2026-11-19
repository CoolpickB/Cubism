package dev.cubism.client.mixin.render;

import dev.cubism.client.SeamClient;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.cube.SeamLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.EntityHitboxDebugRenderer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.debug.DebugValueAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla skips the entities of other face regions, because the level extractor hides them from vanilla. Their
 * hitboxes show here where the seam rendering draws the entity: the box, the eye height and the look direction.
 */
@Mixin(EntityHitboxDebugRenderer.class)
public abstract class EntityHitboxDebugRendererMixin {
	@Shadow @Final private Minecraft minecraft;

	@Inject(method = "emitGizmos", at = @At("TAIL"))
	private void cubism$hitboxesAcrossSeams(
		double camX, double camY, double camZ, DebugValueAccess debugValues, Frustum frustum, float partialTicks, CallbackInfo ci
	) {
		SeamLayout layout = SeamClient.layout;
		int cameraFace = layout == null || this.minecraft.level == null ? -1 : SeamInteraction.regionFace(layout, camX, camZ);
		if (cameraFace < 0) {
			return;
		}
		Vec3 cameraPos = new Vec3(camX, camY, camZ);
		for (Entity entity : this.minecraft.level.entitiesForRendering()) {
			int face = SeamInteraction.regionFace(layout, entity.getX(), entity.getZ());
			if (face < 0 || face == cameraFace || entity.isInvisible()) {
				continue;
			}
			Vec3 camera = SeamInteraction.move(layout.motion(cameraFace, face), cameraPos);
			if (entity.shouldRender(camera.x, camera.y, camera.z)) {
				cubism$showHitbox(entity, layout.motion(face, cameraFace));
			}
		}
	}

	@Unique
	private void cubism$showHitbox(Entity entity, int[] m) {
		float entityPartialTicks = this.minecraft.getDeltaTracker()
			.getGameTimeDeltaPartialTick(!this.minecraft.level.tickRateManager().isEntityFrozen(entity));
		Vec3 position = entity.getPosition(entityPartialTicks);
		AABB box = entity.getBoundingBox().move(position.subtract(entity.position()));
		Gizmos.cuboid(cubism$move(m, box), GizmoStyle.stroke(-1));
		Gizmos.point(SeamInteraction.move(m, position), -1, 2.0F);
		if (entity instanceof LivingEntity) {
			double eye = box.minY + entity.getEyeHeight();
			AABB eyeBox = new AABB(box.minX, eye - 0.01F, box.minZ, box.maxX, eye + 0.01F, box.maxZ);
			Gizmos.cuboid(cubism$move(m, eyeBox), GizmoStyle.stroke(-65536));
		}
		Vec3 eyePosition = position.add(0.0, entity.getEyeHeight(), 0.0);
		Vec3 look = eyePosition.add(entity.getViewVector(entityPartialTicks).scale(2.0));
		Gizmos.arrow(SeamInteraction.move(m, eyePosition), SeamInteraction.move(m, look), -16776961);
	}

	@Unique
	private static AABB cubism$move(int[] m, AABB box) {
		return new AABB(SeamInteraction.move(m, box.getMinPosition()), SeamInteraction.move(m, box.getMaxPosition()));
	}
}

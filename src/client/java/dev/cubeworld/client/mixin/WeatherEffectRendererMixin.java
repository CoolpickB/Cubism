package dev.cubeworld.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.cubeworld.client.SeamClient;
import dev.cubeworld.client.render.CubeWeather;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Weather over the neighbor faces near the camera, leaning toward the rounded surface. See {@link CubeWeather}. */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {
	/** True while the columns of a neighbor face are gathered, so that they do not gather neighbors again. */
	@Unique
	private boolean cubeworld$gathering;

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void cubeworld$addNeighborFaces(ClientLevel level, float partialTicks, Vec3 cameraPos, WeatherRenderState state, CallbackInfo ci) {
		SeamLayout layout = SeamClient.layout;
		if (layout == null || state.intensity <= 0.0F) {
			return;
		}
		CubeWeather.fitColumns(level, layout, state.rainColumns, cameraPos, state.radius);
		CubeWeather.fitColumns(level, layout, state.snowColumns, cameraPos, state.radius);
		if (this.cubeworld$gathering) {
			return;
		}
		int cameraFace = layout.faceIndexAt(Mth.floor(cameraPos.x));
		WeatherRenderState neighbor = new WeatherRenderState();
		this.cubeworld$gathering = true;
		try {
			for (int face = 0; face < SeamLayout.FACES.length; face++) {
				int[] motion = layout.motion(cameraFace, face);
				// The opposite face has its rotation entry Y at -1.
				if (face == cameraFace || motion[4] == -1) {
					continue;
				}
				Vec3 camera = SeamInteraction.move(motion, cameraPos);
				if (!CubeWeather.reaches(layout, face, camera, state.radius)) {
					continue;
				}
				neighbor.reset();
				((WeatherEffectRenderer) (Object) this).extractRenderState(level, partialTicks, camera, neighbor);
				state.rainColumns.addAll(neighbor.rainColumns);
				state.snowColumns.addAll(neighbor.snowColumns);
			}
		} finally {
			this.cubeworld$gathering = false;
		}
	}

	@WrapOperation(method = "prepare", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/WeatherEffectRenderer;prepareInstances(Lcom/mojang/blaze3d/vertex/VertexConsumer;Ljava/util/List;Lnet/minecraft/world/phys/Vec3;FIF)V"))
	private void cubeworld$leanColumns(
		WeatherEffectRenderer renderer, VertexConsumer builder, List<WeatherEffectRenderer.ColumnInstance> columns, Vec3 cameraPos, float maxAlpha,
		int radius, float intensity, Operation<Void> original
	) {
		SeamLayout layout = SeamClient.layout;
		if (layout == null) {
			original.call(renderer, builder, columns, cameraPos, maxAlpha, radius, intensity);
		} else {
			CubeWeather.addColumns(builder, layout, columns, cameraPos, maxAlpha, radius, intensity);
		}
	}
}

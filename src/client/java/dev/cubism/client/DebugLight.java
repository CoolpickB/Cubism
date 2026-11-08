package dev.cubism.client;

import dev.cubism.client.render.CubeSky;
import dev.cubism.seam.CubeTime;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.phys.Vec3;

/** Debug, {@code /cubelight}: prints the values that the light and the sky of the camera come from. */
final class DebugLight {
	private DebugLight() {}

	static int print() {
		Minecraft mc = Minecraft.getInstance();
		SeamLayout layout = mc.level == null ? null : SeamLayout.of(mc.level);
		if (layout == null || mc.player == null) {
			return 0;
		}
		var camera = mc.gameRenderer.mainCamera();
		Vec3 body = dev.cubism.client.CubeFlight.timeFocus(mc.player, 1.0F);
		Vec3 owner = layout.ownerStorage(body);
		var probe = camera.attributeProbe();
		StringBuilder faces = new StringBuilder();
		for (int f = 0; f < SeamLayout.FACES.length; f++) {
			faces.append(CubeTime.offsetTicks(layout, layout.offsetX(f) + 0.5, layout.centerY + layout.radius, 0.5)).append(' ');
		}
		line("orbit=%.3f planetSkyEnd body=%s owner=%s ownerRegion=%d", CubeSky.orbit(), fmt(body), fmt(owner), layout.regionOfX((int) Math.floor(owner.x)));
		line("offsetTicks body=%d owner=%d faceMiddles=[%s]", CubeTime.offsetTicks(layout, body.x, body.y, body.z), CubeTime.offsetTicks(layout, owner.x, owner.y, owner.z), faces.toString().trim());
		line("probe skyLightFactor=%.3f skyLightLevel=%.3f fogEnd=%.1f skyFogEnd=%.1f cloudHeight=%.1f",
			probe.getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, 1.0F), probe.getValue(EnvironmentAttributes.SKY_LIGHT_LEVEL, 1.0F),
			probe.getValue(EnvironmentAttributes.FOG_END_DISTANCE, 1.0F), probe.getValue(EnvironmentAttributes.SKY_FOG_END_DISTANCE, 1.0F),
			probe.getValue(EnvironmentAttributes.CLOUD_HEIGHT, 1.0F));
		line("level skyDarken=%d camera=%s", mc.level.getSkyDarken(), fmt(camera.position()));
		return 1;
	}

	private static String fmt(Vec3 v) {
		return String.format("(%.1f, %.1f, %.1f)", v.x, v.y, v.z);
	}

	private static void line(String format, Object... args) {
		String text = String.format(format, args);
		Minecraft.getInstance().player.sendSystemMessage(Component.literal(text));
		org.slf4j.LoggerFactory.getLogger("cubism").info("cubelight {}", text);
	}
}

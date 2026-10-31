package dev.cubeworld.client.gui;

import dev.cubeworld.worldgen.CubeBiomeMap;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.level.biome.Biome;
import org.joml.Matrix3f;
import org.joml.Matrix3x2f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The surface biomes of the cube, seen from outside. The cube turns slowly, and a drag with the mouse turns it by hand.
 * The biome under the mouse shows as a tooltip.
 */
final class CubePreviewWidget extends AbstractWidget {
	private static final float TURN_PER_MS = 0.0003F;
	/** After a drag, the cube stays still this long before it turns again. */
	private static final long PAUSE_AFTER_DRAG_MS = 3000L;
	private static final float DRAG_SPEED = 0.015F;
	private static final Vector3f LIGHT = new Vector3f(-0.4F, 0.7F, 0.6F).normalize();

	private @Nullable CubeBiomeMap map;
	private Component placeholder = Component.empty();
	private float yaw = Mth.PI / 4.0F;
	private float pitch = Mth.PI / 7.0F;
	private long lastFrame;
	private long lastDrag;

	CubePreviewWidget() {
		super(0, 0, 0, 0, Component.translatable("cubeworld.customize.preview"));
	}

	void setMap(@Nullable CubeBiomeMap map) {
		this.map = map;
	}

	/** Text in place of the cube while there is no map. */
	void setPlaceholder(Component placeholder) {
		this.placeholder = placeholder;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		long now = Util.getMillis();
		if (this.lastFrame != 0L && now - this.lastDrag > PAUSE_AFTER_DRAG_MS) {
			this.yaw += (now - this.lastFrame) * TURN_PER_MS;
		}
		this.lastFrame = now;
		CubeBiomeMap map = this.map;
		if (map == null) {
			graphics.centeredText(Minecraft.getInstance().font, this.placeholder, this.getX() + this.width / 2, this.getY() + this.height / 2 - 4, -1);
			return;
		}
		Matrix3f view = new Matrix3f().rotateX(this.pitch).rotateY(this.yaw);
		Holder<Biome> hovered = null;
		for (int face = 0; face < SeamLayout.FACES.length; face++) {
			Vector3f normal = view.transform(vector(SeamLayout.FACES[face].getUnitVec3i()));
			if (normal.z <= 0.0F) {
				continue;
			}
			float[] affine = this.faceToScreen(view, face, map.cells);
			Holder<Biome>[] cells = map.surface[face];
			if (cells[0] == null) {
				continue;
			}
			float shade = 0.65F + 0.35F * Math.max(0.0F, normal.dot(LIGHT));
			this.drawFace(graphics, affine, cells, map.cells, shade);
			if (this.isHovered()) {
				Holder<Biome> under = cellAt(affine, cells, map.cells, mouseX, mouseY);
				if (under != null) {
					hovered = under;
				}
			}
		}
		if (hovered != null) {
			graphics.setTooltipForNextFrame(BiomeColors.name(hovered), mouseX, mouseY);
		}
	}

	/**
	 * The screen transform of a face, as {x0, y0, x1, y1, ox, oy}: cell (s, t) is at {@code o + s * (x0, y0) + t * (x1,
	 * y1)}. The cube is drawn with a radius of 1, cell (0, 0) at {@code n - a - b}.
	 */
	private float[] faceToScreen(Matrix3f view, int face, int cells) {
		Vec3i[] axes = CubeBiomeMap.axes(face);
		Vec3i n = SeamLayout.FACES[face].getUnitVec3i();
		float scale = Math.min(this.width, this.height) / 2.0F / Mth.sqrt(3.0F);
		float step = 2.0F / cells;
		Vector3f a = view.transform(vector(axes[0])).mul(scale);
		Vector3f b = view.transform(vector(axes[1])).mul(scale);
		Vector3f c = view.transform(vector(n)).mul(scale);
		float cx = this.getX() + this.width / 2.0F;
		float cy = this.getY() + this.height / 2.0F;
		// Screen y grows down.
		return new float[] {
			a.x * step, -a.y * step, b.x * step, -b.y * step,
			cx + c.x - a.x - b.x, cy - (c.y - a.y - b.y)
		};
	}

	private void drawFace(GuiGraphicsExtractor graphics, float[] m, Holder<Biome>[] cells, int side, float shade) {
		// The GUI culls quads that face away. With a mirrored transform, the face is drawn mirrored along s, which
		// puts each cell in the same place.
		boolean mirrored = m[0] * m[3] - m[1] * m[2] < 0.0F;
		Matrix3x2f pose = mirrored
			? new Matrix3x2f(-m[0], -m[1], m[2], m[3], m[4] + m[0] * side, m[5] + m[1] * side)
			: new Matrix3x2f(m[0], m[1], m[2], m[3], m[4], m[5]);
		graphics.pose().pushMatrix();
		graphics.pose().mul(pose);
		for (int s = 0; s < side; s++) {
			int x = mirrored ? side - 1 - s : s;
			int start = 0;
			for (int t = 1; t <= side; t++) {
				if (t == side || cells[s * side + t] != cells[s * side + start]) {
					graphics.fill(x, start, x + 1, t, ARGB.scaleRGB(BiomeColors.color(cells[s * side + start]), shade));
					start = t;
				}
			}
		}
		graphics.pose().popMatrix();
	}

	private static @Nullable Holder<Biome> cellAt(float[] m, Holder<Biome>[] cells, int side, double x, double y) {
		double det = m[0] * m[3] - m[1] * m[2];
		double dx = x - m[4];
		double dy = y - m[5];
		int s = Mth.floor((dx * m[3] - dy * m[2]) / det);
		int t = Mth.floor((dy * m[0] - dx * m[1]) / det);
		return s >= 0 && s < side && t >= 0 && t < side ? cells[s * side + t] : null;
	}

	private static Vector3f vector(Vec3i v) {
		return new Vector3f(v.getX(), v.getY(), v.getZ());
	}

	@Override
	protected void onDrag(MouseButtonEvent event, double dx, double dy) {
		this.yaw += (float) dx * DRAG_SPEED;
		this.pitch = Mth.clamp(this.pitch + (float) dy * DRAG_SPEED, -Mth.HALF_PI, Mth.HALF_PI);
		this.lastDrag = Util.getMillis();
	}

	@Override
	public void playDownSound(SoundManager soundManager) {
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		this.defaultButtonNarrationText(output);
	}
}

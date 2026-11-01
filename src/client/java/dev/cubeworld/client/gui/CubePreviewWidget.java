package dev.cubeworld.client.gui;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.cubeworld.worldgen.CubeBiomeMap;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
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
import org.joml.Matrix3x2fc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The surface biomes of the cube, seen from outside in perspective. The cube turns slowly about an axis that it leans
 * on, so that each face comes into view, and a drag with the mouse turns it by hand. The biome under the mouse shows as
 * a tooltip.
 */
final class CubePreviewWidget extends AbstractWidget {
	private static final float TURN_PER_MS = 0.0003F;
	/** After a drag, the cube stays still this long before it turns again. */
	private static final long PAUSE_AFTER_DRAG_MS = 3000L;
	private static final float DRAG_SPEED = 0.015F;
	private static final Vector3f LIGHT = new Vector3f(-0.4F, 0.7F, 0.6F).normalize();
	/**
	 * The lean of the cube on its turn axis. The view looks down by less than this, so the bottom face also comes into
	 * view once in each turn.
	 */
	private static final float LEAN = Mth.PI / 6.0F;
	/** The distance of the eye from the center, in cube radii. Less is a stronger perspective. */
	private static final float EYE_DISTANCE = 5.0F;
	private static final float CUBE_CORNER = Mth.sqrt(3.0F);

	private @Nullable CubeBiomeMap map;
	private Component placeholder = Component.empty();
	private float yaw = Mth.PI / 4.0F;
	private float pitch = Mth.PI / 12.0F;
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
		Matrix3f view = new Matrix3f().rotateX(this.pitch).rotateY(this.yaw).rotateZ(LEAN);
		int side = map.cells;
		// At most three faces show, each with at most one run per cell.
		float[] quads = new float[3 * side * side * 8];
		int[] colors = new int[3 * side * side];
		int count = 0;
		Holder<Biome> hovered = null;
		for (int face = 0; face < SeamLayout.FACES.length; face++) {
			Holder<Biome>[] cells = map.surface[face];
			Vector3f normal = view.transform(vector(SeamLayout.FACES[face].getUnitVec3i()));
			// The face plane lies at a distance of 1 from the center.
			if (cells[0] == null || normal.z * EYE_DISTANCE <= 1.0F) {
				continue;
			}
			float[] grid = this.projectGrid(view, face, side);
			float shade = 0.65F + 0.35F * Math.max(0.0F, normal.dot(LIGHT));
			for (int s = 0; s < side; s++) {
				int start = 0;
				for (int t = 1; t <= side; t++) {
					if (t == side || cells[s * side + t] != cells[s * side + start]) {
						addQuad(quads, count, grid, side, s, start, t);
						colors[count++] = ARGB.scaleRGB(BiomeColors.color(cells[s * side + start]), shade);
						start = t;
					}
				}
			}
			if (this.isHovered()) {
				Holder<Biome> under = this.cellAt(view, normal, face, cells, side, mouseX, mouseY);
				if (under != null) {
					hovered = under;
				}
			}
		}
		ScreenRectangle area = new ScreenRectangle(this.getX(), this.getY(), this.width, this.height);
		ScreenRectangle scissor = graphics.scissorStack.peek();
		graphics.guiRenderState.addGuiElement(new QuadsRenderState(
			new Matrix3x2f(graphics.pose()), quads, colors, count, scissor, scissor != null ? scissor.intersection(area) : area
		));
		if (hovered != null) {
			graphics.setTooltipForNextFrame(BiomeColors.name(hovered), mouseX, mouseY);
		}
	}

	/** The screen scale of a cube radius at the center, so that the whole cube fits in the widget from any side. */
	private float scale() {
		return Math.min(this.width, this.height) / 2.0F * Mth.sqrt(EYE_DISTANCE * EYE_DISTANCE - 3.0F) / CUBE_CORNER;
	}

	/**
	 * The screen positions of the cell corners of a face, as x and y for corner (s, t) at {@code 2 * (s * (side + 1) + t)}.
	 * The cube has a radius of 1, and corner (0, 0) is at {@code n - a - b}.
	 */
	private float[] projectGrid(Matrix3f view, int face, int side) {
		Vec3i[] axes = CubeBiomeMap.axes(face);
		Vector3f a = view.transform(vector(axes[0]));
		Vector3f b = view.transform(vector(axes[1]));
		Vector3f origin = view.transform(vector(SeamLayout.FACES[face].getUnitVec3i())).sub(a).sub(b);
		float step = 2.0F / side;
		float scale = this.scale();
		float cx = this.getX() + this.width / 2.0F;
		float cy = this.getY() + this.height / 2.0F;
		float[] grid = new float[2 * (side + 1) * (side + 1)];
		Vector3f p = new Vector3f();
		for (int s = 0; s <= side; s++) {
			for (int t = 0; t <= side; t++) {
				origin.fma(s * step, a, p).fma(t * step, b);
				float k = scale / (EYE_DISTANCE - p.z);
				int i = 2 * (s * (side + 1) + t);
				// Screen y grows down.
				grid[i] = cx + p.x * k;
				grid[i + 1] = cy - p.y * k;
			}
		}
		return grid;
	}

	/** Adds the run of cells from t0 to t1 in row s as a quad, wound the way the GUI draws its rectangles. */
	private static void addQuad(float[] quads, int index, float[] grid, int side, int s, int t0, int t1) {
		int[] corners = {s * (side + 1) + t0, (s + 1) * (side + 1) + t0, (s + 1) * (side + 1) + t1, s * (side + 1) + t1};
		float area = 0.0F;
		for (int i = 0; i < 4; i++) {
			int c = 2 * corners[i];
			int n = 2 * corners[(i + 1) % 4];
			area += grid[c] * grid[n + 1] - grid[n] * grid[c + 1];
		}
		// The GUI culls quads of the other winding.
		for (int i = 0; i < 4; i++) {
			int c = 2 * corners[area < 0.0F ? i : 3 - i];
			quads[index * 8 + 2 * i] = grid[c];
			quads[index * 8 + 2 * i + 1] = grid[c + 1];
		}
	}

	/** The cell of a face under a screen position: where the ray from the eye through it meets the face plane. */
	private @Nullable Holder<Biome> cellAt(Matrix3f view, Vector3f normal, int face, Holder<Biome>[] cells, int side, double x, double y) {
		float scale = this.scale();
		Vector3f ray = new Vector3f(
			(float) (x - (this.getX() + this.width / 2.0)) / scale, (float) -(y - (this.getY() + this.height / 2.0)) / scale, -1.0F
		);
		float along = ray.dot(normal);
		if (along >= 0.0F) {
			return null;
		}
		float distance = (1.0F - EYE_DISTANCE * normal.z) / along;
		Vector3f hit = ray.mul(distance).add(0.0F, 0.0F, EYE_DISTANCE);
		Vector3f onCube = new Matrix3f(view).transpose().transform(hit);
		Vec3i[] axes = CubeBiomeMap.axes(face);
		int s = Mth.floor((onCube.dot(vector(axes[0])) + 1.0F) * side / 2.0F);
		int t = Mth.floor((onCube.dot(vector(axes[1])) + 1.0F) * side / 2.0F);
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

	/** Flat colored quads with free corners. The rectangles of the GUI take an affine pose, which has no perspective. */
	private record QuadsRenderState(
		Matrix3x2fc pose, float[] quads, int[] colors, int count, @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds
	) implements GuiElementRenderState {
		@Override
		public void buildVertices(VertexConsumer vertexConsumer) {
			for (int q = 0; q < this.count; q++) {
				for (int i = 0; i < 4; i++) {
					vertexConsumer.addVertexWith2DPose(this.pose, this.quads[q * 8 + 2 * i], this.quads[q * 8 + 2 * i + 1]).setColor(this.colors[q]);
				}
			}
		}

		@Override
		public RenderPipeline pipeline() {
			return RenderPipelines.GUI;
		}

		@Override
		public TextureSetup textureSetup() {
			return TextureSetup.noTexture();
		}
	}
}

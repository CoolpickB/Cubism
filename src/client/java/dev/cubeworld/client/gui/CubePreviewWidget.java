package dev.cubeworld.client.gui;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.cubeworld.worldgen.CubeBiomeMap;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
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
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The surface biomes of the cube, seen from outside in perspective. The cube turns slowly about an axis that it leans
 * on, so that each face comes into view. A drag turns it freely by hand, about any axis, and a drag that starts at the
 * rim rolls it. A thrown cube goes on turning and slows down. After a pause the cube turns again about the same axis
 * as at the start, from the orientation that it was left in. The biome under the mouse shows as a tooltip, and a
 * highlighted biome keeps its color while the others dim.
 */
final class CubePreviewWidget extends AbstractWidget {
	private static final float TURN_PER_MS = 0.0003F;
	/** After a drag, the cube stays still this long before it turns again. */
	private static final long PAUSE_AFTER_DRAG_MS = 3000L;
	private static final float DRAG_SPEED = 0.015F;
	/** A thrown cube slows down with this time constant. */
	private static final float SPIN_DECAY_MS = 1000.0F;
	/** Below this turn speed, in radians per ms, a thrown cube stops. */
	private static final float MIN_SPIN = 0.0004F;
	private static final float MAX_SPIN = 0.04F;
	/** The speed of the hand is an average over about this long, in ms, so that two moves in one frame do not skew it. */
	private static final float SPEED_WINDOW_MS = 30.0F;
	/**
	 * A release throws the cube with the speed of the hand minus this, in radians per ms. A slow hand throws nothing, so
	 * that the cube stops where the hand stops, and the throw grows from zero with the speed of the hand.
	 */
	private static final float THROW_DEADZONE = 0.002F;
	/** A release this long after the last drag movement throws nothing. */
	private static final long THROW_WINDOW_MS = 100L;
	/** A drag that starts this far from the center, in cube radii, rolls the cube. */
	private static final float ROLL_START = 0.85F;
	private static final float START_YAW = Mth.PI / 4.0F;
	private static final float START_PITCH = Mth.PI / 12.0F;
	/** The axis of the turn of the cube, in the view. */
	private static final Vector3f TURN_AXIS = new Matrix3f().rotateX(START_PITCH).transform(new Vector3f(0.0F, 1.0F, 0.0F));
	private static final float DIMMED = 0.35F;
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
	private boolean loading;
	private final Quaternionf orientation = startPose();
	/** The turn of a thrown cube as a rotation vector per ms, in the view. */
	private final Vector3f spin = new Vector3f();
	private boolean dragging;
	/** Whether the drag in progress started at the rim, and rolls the cube instead of tilting it. */
	private boolean rolling;
	private long lastFrame;
	private long lastActive;
	private long lastDragMove;
	private @Nullable Holder<Biome> highlighted;
	/** Whether the highlighted biome is on the surface of the map, or null when not known yet. */
	private @Nullable Boolean highlightOnSurface;

	CubePreviewWidget() {
		super(0, 0, 0, 0, Component.translatable("cubeworld.customize.preview"));
	}

	void setMap(@Nullable CubeBiomeMap map) {
		if (map != this.map) {
			this.highlightOnSurface = null;
		}
		this.map = map;
	}

	/** The biome that keeps its color while the others dim, or null. A biome that is only underground dims nothing. */
	void setHighlighted(@Nullable Holder<Biome> highlighted) {
		if (highlighted != this.highlighted) {
			this.highlighted = highlighted;
			this.highlightOnSurface = null;
		}
	}

	/** Holds the cube still, as a drag does, before it turns again. */
	void pause() {
		this.spin.zero();
		this.lastActive = Util.getMillis();
	}

	private static Quaternionf startPose() {
		return new Quaternionf().rotateX(START_PITCH).rotateY(START_YAW).rotateZ(LEAN);
	}

	/** True while the map is out of date: the placeholder shows over the cube of the last map. */
	void setLoading(boolean loading) {
		this.loading = loading;
	}

	/** Text in place of the cube while there is no map, and over it while it loads. */
	void setPlaceholder(Component placeholder) {
		this.placeholder = placeholder;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		long now = Util.getMillis();
		this.advance(now, this.lastFrame == 0L ? 0L : Math.min(now - this.lastFrame, 100L));
		this.lastFrame = now;
		CubeBiomeMap map = this.map;
		if (map == null) {
			graphics.centeredText(Minecraft.getInstance().font, this.placeholder, this.getX() + this.width / 2, this.getY() + this.height / 2 - 4, -1);
			return;
		}
		Matrix3f view = new Matrix3f().set(this.orientation);
		int side = map.cells;
		Holder<Biome> highlighted = this.highlightsSurface(map) ? this.highlighted : null;
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
						Holder<Biome> biome = cells[s * side + start];
						colors[count++] = ARGB.scaleRGB(BiomeColors.color(biome), highlighted == null || biome == highlighted ? shade : shade * DIMMED);
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
		if (this.loading) {
			Font font = Minecraft.getInstance().font;
			int x = this.getX() + this.width / 2;
			int y = this.getY() + this.height / 2;
			int half = font.width(this.placeholder) / 2 + 4;
			graphics.fill(x - half, y - 7, x + half, y + 7, 0xA0000000);
			graphics.centeredText(font, this.placeholder, x, y - 4, -1);
		}
		if (hovered != null) {
			graphics.setTooltipForNextFrame(BiomeColors.name(hovered), mouseX, mouseY);
		}
	}

	/** Moves the cube by one frame: a thrown spin, or its own turn after a pause, from the orientation it is in. */
	private void advance(long now, long dt) {
		if (this.dragging) {
			this.lastActive = now;
			return;
		}
		float speed = this.spin.length();
		if (speed > MIN_SPIN) {
			this.turn(new Vector3f(this.spin).normalize(), speed * dt);
			this.spin.mul((float) Math.exp(-dt / SPIN_DECAY_MS));
			this.lastActive = now;
			return;
		}
		this.spin.zero();
		if (now - this.lastActive > PAUSE_AFTER_DRAG_MS) {
			this.turn(TURN_AXIS, dt * TURN_PER_MS);
		}
	}

	/** Turns the cube in the view by an angle about an axis. */
	private void turn(Vector3f axis, float angle) {
		if (angle > 0.0F) {
			this.orientation.set(new Quaternionf().rotationAxis(angle, axis).mul(new Quaternionf(this.orientation)));
		}
	}

	private boolean highlightsSurface(CubeBiomeMap map) {
		if (this.highlighted == null) {
			return false;
		}
		if (this.highlightOnSurface == null) {
			boolean found = false;
			for (Holder<Biome>[] face : map.surface) {
				for (Holder<Biome> biome : face) {
					found |= biome == this.highlighted;
				}
			}
			this.highlightOnSurface = found;
		}
		return this.highlightOnSurface;
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
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		float radius = Math.min(this.width, this.height) / 2.0F;
		float x = (float) (event.x() - (this.getX() + this.width / 2.0)) / radius;
		float y = (float) (event.y() - (this.getY() + this.height / 2.0)) / radius;
		this.rolling = Mth.length(x, y) > ROLL_START;
		this.spin.zero();
		this.dragging = true;
		this.lastDragMove = Util.getMillis();
	}

	/**
	 * A drag turns the cube about the axis across the movement. A drag that started at the rim turns it about the look
	 * instead, by the angle that the mouse goes around the center. The cube keeps the turn of the last movement as its
	 * speed. The mode stays for the whole drag, so that no distance of the mouse ends the turn.
	 */
	@Override
	protected void onDrag(MouseButtonEvent event, double dx, double dy) {
		long now = Util.getMillis();
		Vector3f turn;
		if (this.rolling) {
			float radius = Math.min(this.width, this.height) / 2.0F;
			float x1 = (float) (event.x() - (this.getX() + this.width / 2.0)) / radius;
			float y1 = -(float) (event.y() - (this.getY() + this.height / 2.0)) / radius;
			float x0 = x1 - (float) dx / radius;
			float y0 = y1 + (float) dy / radius;
			turn = new Vector3f(0.0F, 0.0F, (float) Math.atan2(x0 * y1 - y0 * x1, x0 * x1 + y0 * y1));
		} else {
			turn = new Vector3f((float) dy * DRAG_SPEED, (float) dx * DRAG_SPEED, 0.0F);
		}
		float angle = turn.length();
		if (angle > 0.0F) {
			this.turn(turn.div(angle), angle);
			float ms = Math.max(1L, now - this.lastDragMove);
			this.spin.lerp(new Vector3f(turn).mul(angle / ms), 1.0F - (float) Math.exp(-ms / SPEED_WINDOW_MS));
			if (this.spin.length() > MAX_SPIN) {
				this.spin.normalize(MAX_SPIN);
			}
		}
		this.lastDragMove = now;
		this.lastActive = now;
	}

	@Override
	public void onRelease(MouseButtonEvent event) {
		long now = Util.getMillis();
		this.dragging = false;
		this.lastActive = now;
		float speed = this.spin.length();
		if (now - this.lastDragMove > THROW_WINDOW_MS || speed <= THROW_DEADZONE) {
			this.spin.zero();
		} else {
			this.spin.mul((speed - THROW_DEADZONE) / speed);
		}
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

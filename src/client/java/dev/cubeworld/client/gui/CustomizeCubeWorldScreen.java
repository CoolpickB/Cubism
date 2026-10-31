package dev.cubeworld.client.gui;

import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.worldgen.CubePregen;
import dev.cubeworld.worldgen.CubeSettings;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldDimensions;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/** Sets the {@link CubeSettings} of a new seam world. Done writes them into the overworld generator of the world. */
public class CustomizeCubeWorldScreen extends Screen {
	private static final int WIDTH = 310;
	/** Steps of the pre-generation slider: off, then radii of {@link #PREGEN_STEP} chunks, then the whole cube. */
	private static final int PREGEN_STEP = 4;
	private static final int PREGEN_STEPS = CubeSettings.MAX_PREGEN_RADIUS / PREGEN_STEP + 2;

	private final CreateWorldScreen parent;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private int diameterChunks;
	private int edgeRounding;
	private Direction spawnFace;
	private int pregenRadius;
	private StringWidget pregenLabel;

	public CustomizeCubeWorldScreen(CreateWorldScreen parent, WorldCreationContext context) {
		super(Component.translatable("cubeworld.customize.title"));
		this.parent = parent;
		CubeSettings settings = CubeSettings.of(context.selectedDimensions().overworld());
		this.diameterChunks = settings.diameterChunks();
		this.edgeRounding = settings.edgeRounding();
		this.spawnFace = settings.spawnFace();
		this.pregenRadius = settings.pregenRadius();
	}

	@Override
	protected void init() {
		this.layout.addToHeader(new StringWidget(this.title, this.font));
		LinearLayout content = this.layout.addToContents(LinearLayout.vertical().spacing(8));
		content.defaultCellSetting().alignHorizontallyCenter();

		content.addChild(slider(
			CubePlanetMath.MIN_DIAMETER_CHUNKS / 2, CubePlanetMath.MAX_DIAMETER_CHUNKS / 2, this.diameterChunks / 2,
			half -> Component.translatable("cubeworld.customize.diameter", half * 2, half * 32),
			half -> {
				this.diameterChunks = half * 2;
				this.refreshPregenLabel();
			}
		));
		content.addChild(slider(
			CubePlanetMath.MIN_EDGE_ROUNDING, CubePlanetMath.MAX_EDGE_ROUNDING, this.edgeRounding,
			blocks -> Component.translatable("cubeworld.customize.edge_rounding", blocks),
			blocks -> this.edgeRounding = blocks
		));
		content.addChild(CycleButton.builder(
				(Direction face) -> Component.translatable("cubeworld.customize.face." + face.getSerializedName()), this.spawnFace
			)
			.withValues(SeamLayout.FACES)
			.create(0, 0, WIDTH, 20, Component.translatable("cubeworld.customize.spawn_face"), (button, face) -> this.spawnFace = face));
		content.addChild(slider(
			0, PREGEN_STEPS - 1, pregenStep(this.pregenRadius),
			step -> pregenText(pregenRadius(step)),
			step -> {
				this.pregenRadius = pregenRadius(step);
				this.refreshPregenLabel();
			}
		));
		this.pregenLabel = content.addChild(new StringWidget(WIDTH, 12, Component.empty(), this.font));
		this.refreshPregenLabel();

		LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> {
			this.applySettings();
			this.onClose();
		}).build());
		footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose()).build());

		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	private static AbstractSliderButton slider(int min, int max, int initial, IntFunction<Component> text, IntConsumer onChange) {
		return new AbstractSliderButton(0, 0, WIDTH, 20, text.apply(initial), (double) (initial - min) / (max - min)) {
			private int current = initial;

			@Override
			protected void updateMessage() {
				this.setMessage(text.apply(this.current));
			}

			@Override
			protected void applyValue() {
				this.current = Mth.clamp(min + (int) Math.round(this.value * (max - min)), min, max);
				onChange.accept(this.current);
			}
		};
	}

	private static int pregenRadius(int step) {
		return step == PREGEN_STEPS - 1 ? CubeSettings.PREGEN_WHOLE_CUBE : step * PREGEN_STEP;
	}

	private static int pregenStep(int radius) {
		return radius == CubeSettings.PREGEN_WHOLE_CUBE ? PREGEN_STEPS - 1 : Mth.clamp(Math.round((float) radius / PREGEN_STEP), 0, PREGEN_STEPS - 2);
	}

	private static Component pregenText(int radius) {
		if (radius == 0) {
			return Component.translatable("cubeworld.customize.pregen.off");
		}
		return radius == CubeSettings.PREGEN_WHOLE_CUBE
			? Component.translatable("cubeworld.customize.pregen.whole")
			: Component.translatable("cubeworld.customize.pregen.radius", radius);
	}

	private void refreshPregenLabel() {
		if (this.pregenLabel == null) {
			return;
		}
		int faceSide = CubePregen.faceSideChunks(this.diameterChunks);
		int columns = this.pregenRadius == CubeSettings.PREGEN_WHOLE_CUBE
			? SeamLayout.FACES.length * faceSide * faceSide
			: Math.min(SeamLayout.FACES.length * faceSide * faceSide, (int) Math.round(Math.PI * this.pregenRadius * this.pregenRadius));
		this.pregenLabel.setMessage(this.pregenRadius == 0
			? Component.translatable("cubeworld.customize.pregen.none")
			: Component.translatable("cubeworld.customize.pregen.columns", columns));
	}

	private void applySettings() {
		CubeSettings settings = new CubeSettings(this.diameterChunks, this.edgeRounding, this.spawnFace, this.pregenRadius);
		this.parent.getUiState().updateDimensions((registryAccess, dimensions) -> {
			ChunkGenerator old = dimensions.overworld();
			if (!(old instanceof NoiseBasedChunkGenerator noise)) {
				return dimensions;
			}
			NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(noise.getBiomeSource(), noise.generatorSettings());
			((CubeSettings.Holder) (Object) generator).cubeworld$setSettings(settings);
			LevelStem overworld = dimensions.get(LevelStem.OVERWORLD).orElseThrow();
			return new WorldDimensions(WorldDimensions.withOverworld(dimensions.dimensions(), overworld.type(), generator));
		});
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}

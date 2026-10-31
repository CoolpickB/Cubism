package dev.cubeworld.client.gui;

import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.worldgen.CubePregen;
import dev.cubeworld.worldgen.CubeSettings;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/** Size, edge rounding and pre-generation of the cube. Done gives the new settings to the Customize screen. */
final class CubeSettingsScreen extends Screen {
	private static final int WIDTH = 310;

	private final Screen parent;
	private final Consumer<CubeSettings> onDone;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private CubeSettings settings;
	private StringWidget pregenLabel;

	CubeSettingsScreen(Screen parent, CubeSettings settings, Consumer<CubeSettings> onDone) {
		super(Component.translatable("cubeworld.customize.settings.title"));
		this.parent = parent;
		this.settings = settings;
		this.onDone = onDone;
	}

	@Override
	protected void init() {
		this.layout.addTitleHeader(this.title, this.font);
		LinearLayout content = this.layout.addToContents(LinearLayout.vertical().spacing(8));
		content.defaultCellSetting().alignHorizontallyCenter();

		content.addChild(slider(
			CubePlanetMath.MIN_DIAMETER_CHUNKS / 2, CubePlanetMath.MAX_DIAMETER_CHUNKS / 2, this.settings.diameterChunks() / 2,
			half -> Component.translatable("cubeworld.customize.diameter", half * 2, half * 32),
			half -> this.update(new CubeSettings(half * 2, this.settings.edgeRounding(), this.settings.spawnFace(), this.settings.pregen()))
		));
		content.addChild(slider(
			CubePlanetMath.MIN_EDGE_ROUNDING, CubePlanetMath.MAX_EDGE_ROUNDING, this.settings.edgeRounding(),
			blocks -> Component.translatable("cubeworld.customize.edge_rounding", blocks),
			blocks -> this.update(new CubeSettings(this.settings.diameterChunks(), blocks, this.settings.spawnFace(), this.settings.pregen()))
		));
		content.addChild(CycleButton.builder(
				(CubeSettings.Pregen pregen) -> Component.translatable("cubeworld.customize.pregen." + pregen.getSerializedName()), this.settings.pregen()
			)
			.withValues(CubeSettings.Pregen.values())
			.create(0, 0, WIDTH, 20, Component.translatable("cubeworld.customize.pregen"), (button, pregen) -> this.update(
				new CubeSettings(this.settings.diameterChunks(), this.settings.edgeRounding(), this.settings.spawnFace(), pregen)
			)));
		this.pregenLabel = content.addChild(new StringWidget(WIDTH, 12, Component.empty(), this.font));
		this.update(this.settings);

		LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> {
			this.onDone.accept(this.settings);
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

	private void update(CubeSettings settings) {
		this.settings = settings;
		this.pregenLabel.setMessage(settings.pregen() == CubeSettings.Pregen.OFF
			? Component.translatable("cubeworld.customize.pregen.none")
			: Component.translatable("cubeworld.customize.pregen.columns", CubePregen.count(settings)));
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

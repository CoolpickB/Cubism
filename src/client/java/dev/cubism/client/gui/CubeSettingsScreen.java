package dev.cubism.client.gui;

import dev.cubism.cube.CubePlanetMath;
import dev.cubism.worldgen.CubePregen;
import dev.cubism.worldgen.CubeSettings;
import dev.cubism.worldgen.structure.StrongholdPositions;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/** Size, climate, pre-generation and edge rounding of the cube. Done gives the new settings to the Customize screen. */
final class CubeSettingsScreen extends Screen {
	private static final int WIDTH = 310;

	private final Screen parent;
	private final Consumer<CubeSettings> onDone;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private CubeSettings settings;
	private StringWidget pregenLabel;
	private CycleButton<CubeSettings.Pregen> pregenButton;

	CubeSettingsScreen(Screen parent, CubeSettings settings, Consumer<CubeSettings> onDone) {
		super(Component.translatable("cubism.customize.settings.title"));
		this.parent = parent;
		this.settings = settings;
		this.onDone = onDone;
	}

	@Override
	protected void init() {
		this.layout.addTitleHeader(this.title, this.font);
		LinearLayout content = this.layout.addToContents(LinearLayout.vertical().spacing(8));
		content.defaultCellSetting().alignHorizontallyCenter();

		content.addChild(CycleButton.builder(CubeSettingsScreen::sizeName, this.settings.diameterChunks())
			.withValues(sizes(this.settings.diameterChunks()))
			.withTooltip(diameter -> Tooltip.create(Component.translatable(
				"cubism.customize.size.tooltip", diameter, diameter * 16, StrongholdPositions.count(diameter)
			)))
			.create(0, 0, WIDTH, 20, Component.translatable("cubism.customize.size"), (button, diameter) -> {
				this.update(this.settings.withDiameterChunks(diameter).withPregen(defaultPregen(diameter)));
				this.pregenButton.setValue(this.settings.pregen());
			}));
		content.addChild(slider(
			CubeSettings.MIN_CLIMATE_SHIFT, CubeSettings.MAX_CLIMATE_SHIFT, this.settings.temperature(),
			shift -> climateText("cubism.customize.temperature", shift), shift -> this.update(this.settings.withTemperature(shift))
		));
		content.addChild(slider(
			CubeSettings.MIN_CLIMATE_SHIFT, CubeSettings.MAX_CLIMATE_SHIFT, this.settings.humidity(),
			shift -> climateText("cubism.customize.humidity", shift), shift -> this.update(this.settings.withHumidity(shift))
		));
		this.pregenButton = content.addChild(CycleButton.builder(
				(CubeSettings.Pregen pregen) -> Component.translatable("cubism.customize.pregen." + pregen.getSerializedName()), this.settings.pregen()
			)
			.withValues(CubeSettings.Pregen.values())
			.create(0, 0, WIDTH, 20, Component.translatable("cubism.customize.pregen"), (button, pregen) -> this.update(this.settings.withPregen(pregen))));
		this.pregenLabel = content.addChild(new StringWidget(WIDTH, 12, Component.empty(), this.font));
		content.addChild(CycleButton.onOffBuilder(this.settings.extraItemSources())
			.withTooltip(value -> Tooltip.create(Component.translatable("cubism.customize.extra_item_sources.tooltip")))
			.create(0, 0, WIDTH, 20, Component.translatable("cubism.customize.extra_item_sources"), (button, value) -> this.update(this.settings.withExtraItemSources(value))));
		AbstractSliderButton rounding = content.addChild(slider(
			CubePlanetMath.MIN_EDGE_ROUNDING, CubePlanetMath.MAX_EDGE_ROUNDING, this.settings.edgeRounding(),
			blocks -> Component.translatable("cubism.customize.edge_rounding", blocks),
			blocks -> this.update(this.settings.withEdgeRounding(blocks))
		));
		rounding.setTooltip(Tooltip.create(Component.translatable("cubism.customize.edge_rounding.tooltip")));
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

	/** The sizes on offer: each has its own count of strongholds, fortresses and bastions (1, 2 and 3). */
	private static final int[] SIZES = {24, 64, 128};
	private static final String[] SIZE_NAMES = {"small", "medium", "large"};

	/** The three sizes, and the size of the world if a world preset gave it another one. */
	private static List<Integer> sizes(int current) {
		List<Integer> sizes = new ArrayList<>();
		for (int size : SIZES) {
			sizes.add(size);
		}
		if (!sizes.contains(current)) {
			sizes.add(current);
			sizes.sort(null);
		}
		return sizes;
	}

	private static Component sizeName(int diameter) {
		for (int i = 0; i < SIZES.length; i++) {
			if (SIZES[i] == diameter) {
				return Component.translatable("cubism.customize.size." + SIZE_NAMES[i]);
			}
		}
		return Component.translatable("cubism.customize.size.custom", diameter);
	}

	/** The whole small cube generates in moments. On a larger cube, the spawn face alone shows the planet sooner. */
	private static CubeSettings.Pregen defaultPregen(int diameter) {
		return diameter <= SIZES[0] ? CubeSettings.Pregen.WHOLE_CUBE : CubeSettings.Pregen.SPAWN_FACE;
	}

	private static Component climateText(String key, int shift) {
		return Component.translatable(key, shift == 0 ? Component.translatable("cubism.customize.climate.normal") : Component.literal(String.format("%+d%%", shift)));
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
			? Component.translatable("cubism.customize.pregen.none")
			: Component.translatable("cubism.customize.pregen.columns", CubePregen.count(settings)));
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

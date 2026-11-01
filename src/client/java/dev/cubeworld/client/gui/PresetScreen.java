package dev.cubeworld.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/** The presets of the Customize screen, as a list like the Superflat presets. */
final class PresetScreen extends Screen {
	private final Screen parent;
	private final List<CustomizeCubeWorldScreen.Preset> presets;
	private final Consumer<CustomizeCubeWorldScreen.Preset> onUse;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private PresetList list;
	private Button useButton;

	PresetScreen(Screen parent, List<CustomizeCubeWorldScreen.Preset> presets, Consumer<CustomizeCubeWorldScreen.Preset> onUse) {
		super(Component.translatable("cubeworld.customize.presets.title"));
		this.parent = parent;
		this.presets = presets;
		this.onUse = onUse;
	}

	@Override
	protected void init() {
		this.layout.addTitleHeader(this.title, this.font);
		this.list = this.layout.addToContents(new PresetList());
		LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
		this.useButton = footer.addChild(Button.builder(Component.translatable("cubeworld.customize.presets.use"), button -> this.use()).build());
		footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose()).build());
		this.useButton.active = false;
		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
		this.list.updateSize(this.width, this.layout);
	}

	private void use() {
		PresetList.Row row = this.list.getSelected();
		if (row != null) {
			this.onUse.accept(row.preset);
			this.onClose();
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	private final class PresetList extends ObjectSelectionList<PresetList.Row> {
		PresetList() {
			super(PresetScreen.this.minecraft, PresetScreen.this.width, PresetScreen.this.layout.getContentHeight(), PresetScreen.this.layout.getHeaderHeight(), 24);
			PresetScreen.this.presets.stream().map(Row::new).forEach(this::addEntry);
		}

		@Override
		public void setSelected(@Nullable Row selected) {
			super.setSelected(selected);
			PresetScreen.this.useButton.active = selected != null;
		}

		final class Row extends ObjectSelectionList.Entry<Row> {
			private final CustomizeCubeWorldScreen.Preset preset;

			Row(CustomizeCubeWorldScreen.Preset preset) {
				this.preset = preset;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
				graphics.text(PresetScreen.this.font, this.preset.name(), this.getContentX() + 2, this.getContentY() + 1, 0xFFFFFFFF);
				graphics.text(PresetScreen.this.font, this.preset.description(), this.getContentX() + 2, this.getContentY() + 11, 0xFF808080);
			}

			@Override
			public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
				PresetList.this.setSelected(this);
				if (doubleClick) {
					PresetScreen.this.use();
				}
				return true;
			}

			@Override
			public Component getNarration() {
				return this.preset.name();
			}
		}
	}
}

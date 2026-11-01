package dev.cubeworld.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Consumer;

/** A compass icon that switches the direction markers of the cube preview on and off. */
final class CompassToggle extends AbstractWidget {
	private static final int SIZE = 20;
	private final ItemStack icon = new ItemStack(Items.COMPASS);
	private final Consumer<Boolean> onChange;
	private boolean on;

	CompassToggle(Consumer<Boolean> onChange) {
		super(0, 0, SIZE, SIZE, Component.translatable("cubeworld.customize.compass"));
		this.onChange = onChange;
		this.setTooltip(Tooltip.create(Component.translatable("cubeworld.customize.compass.tooltip")));
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		this.on = !this.on;
		this.onChange.accept(this.on);
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		int x = this.getX();
		int y = this.getY();
		graphics.fill(x, y, x + SIZE, y + SIZE, this.isHovered() ? 0xA0404040 : 0x80000000);
		if (this.on) {
			graphics.outline(x, y, SIZE, SIZE, 0xFFFFFFFF);
		}
		graphics.item(this.icon, x + 2, y + 2);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		this.defaultButtonNarrationText(output);
	}
}

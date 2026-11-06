package dev.cubism.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.Consumer;

/** A compass icon that switches the direction markers of the cube preview on and off. */
final class CompassToggle extends AbstractWidget {
	private static final int SIZE = 20;
	private static final Identifier ICON = Identifier.withDefaultNamespace("textures/item/compass_16.png");
	private final Consumer<Boolean> onChange;
	private boolean on = true;

	CompassToggle(Consumer<Boolean> onChange) {
		super(0, 0, SIZE, SIZE, Component.translatable("cubism.customize.compass"));
		this.onChange = onChange;
		this.setTooltip(Tooltip.create(Component.translatable("cubism.customize.compass.tooltip")));
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
		int tint = this.on ? 0xFFFFFFFF : this.isHovered() ? 0xFFB0B0B0 : 0xFF707070;
		graphics.blit(RenderPipelines.GUI_TEXTURED, ICON, x + 2, y + 2, 0.0F, 0.0F, 16, 16, 16, 16, tint);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		this.defaultButtonNarrationText(output);
	}
}

package dev.cubism.client.mixin.gui;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.CubismMod;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.layouts.CommonLayouts;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The cube world takes its seed in the Customize screen, so the World tab hides its seed box for the cube, and the
 * rows under it move up into its place.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab")
public abstract class WorldTabMixin extends GridLayoutTab {
	/** The row spacing of the World tab. */
	@Unique private static final int ROW_SPACING = 8;

	@Shadow @Final CreateWorldScreen this$0;
	@Unique private @Nullable Layout cubism$seedRow;
	@Unique private boolean cubism$seedHidden;
	@Unique private @Nullable ScreenRectangle cubism$rectangle;

	private WorldTabMixin(Component title) {
		super(title);
	}

	@WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/layouts/CommonLayouts;labeledElement(Lnet/minecraft/client/gui/Font;Lnet/minecraft/client/gui/layouts/LayoutElement;Lnet/minecraft/network/chat/Component;)Lnet/minecraft/client/gui/layouts/Layout;"))
	private Layout cubism$hideSeedForCube(Font font, LayoutElement element, Component label, Operation<Layout> original) {
		Layout seedRow = original.call(font, element, label);
		this.cubism$seedRow = seedRow;
		this.this$0.getUiState().addListener(data -> {
			Holder<WorldPreset> preset = data.getWorldType().preset();
			boolean visible = preset == null || !preset.is(CubismMod.CUBE_PRESET);
			seedRow.visitWidgets(widget -> {
				if (widget instanceof AbstractWidget w) {
					w.visible = visible;
				}
			});
			if (this.cubism$seedHidden != !visible) {
				this.cubism$seedHidden = !visible;
				if (this.cubism$rectangle != null) {
					this.doLayout(this.cubism$rectangle);
				}
			}
		});
		return seedRow;
	}

	@Override
	public void doLayout(ScreenRectangle rectangle) {
		super.doLayout(rectangle);
		this.cubism$rectangle = rectangle;
		Layout seedRow = this.cubism$seedRow;
		if (!this.cubism$seedHidden || seedRow == null) {
			return;
		}
		int seedY = seedRow.getY();
		int shift = seedRow.getHeight() + ROW_SPACING;
		this.layout.visitChildren(child -> {
			if (child.getY() > seedY) {
				child.setY(child.getY() - shift);
			}
		});
	}
}

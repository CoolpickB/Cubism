package dev.cubism.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.CubismMod;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.layouts.CommonLayouts;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** The cube world takes its seed in the Customize screen, so the World tab hides its seed box for the cube. */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab")
public abstract class WorldTabMixin {
	@Shadow @Final CreateWorldScreen this$0;

	@WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/layouts/CommonLayouts;labeledElement(Lnet/minecraft/client/gui/Font;Lnet/minecraft/client/gui/layouts/LayoutElement;Lnet/minecraft/network/chat/Component;)Lnet/minecraft/client/gui/layouts/Layout;"))
	private Layout cubism$hideSeedForCube(Font font, LayoutElement element, Component label, Operation<Layout> original) {
		Layout seedRow = original.call(font, element, label);
		this.this$0.getUiState().addListener(data -> {
			Holder<WorldPreset> preset = data.getWorldType().preset();
			boolean visible = preset == null || !preset.is(CubismMod.CUBE_PRESET);
			seedRow.visitWidgets(widget -> {
				if (widget instanceof AbstractWidget w) {
					w.visible = visible;
				}
			});
		});
		return seedRow;
	}
}

package dev.cubeworld.client.mixin;

import dev.cubeworld.client.gui.CustomizeCubeWorldScreen;
import dev.cubeworld.CubeWorldMod;
import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(WorldCreationUiState.class)
public abstract class PresetEditorMixin {
	@Shadow public abstract WorldCreationContext getSettings();

	@Inject(method = "getPresetEditor", at = @At("HEAD"), cancellable = true)
	private void cubeworld$getCubePresetEditor(CallbackInfoReturnable<PresetEditor> cir) {
		WorldCreationContext ctx = this.getSettings();
		if (ctx != null) {
			Optional<LevelStem> overworld = ctx.selectedDimensions().get(LevelStem.OVERWORLD);
			if (overworld.isPresent() && overworld.get().generator() instanceof NoiseBasedChunkGenerator generator
				&& generator.generatorSettings().is(CubeWorldMod.SEAM_SETTINGS)) {
				cir.setReturnValue(CustomizeCubeWorldScreen::new);
			}
		}
	}
}

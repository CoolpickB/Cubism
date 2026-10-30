package dev.cubeworld.client.gui;

import dev.cubeworld.CubeWorldMod;
import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.worldgen.CubeChunkGenerator;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldDimensions;

import java.util.Map;

public class CustomizeCubeWorldScreen extends Screen {
	private final CreateWorldScreen parent;
	private final HeaderAndFooterLayout layout;
	private int diameterChunks;

	private StringWidget centerLabel;
	private StringWidget coreLabel;
	private StringWidget boundsLabel;

	public CustomizeCubeWorldScreen(CreateWorldScreen parent, WorldCreationContext context) {
		super(Component.translatable("cubeworld.customize.title"));
		this.parent = parent;
		this.layout = new HeaderAndFooterLayout(this);

		ChunkGenerator currentGen = context.selectedDimensions().overworld();
		if (currentGen instanceof CubeChunkGenerator cubeGen) {
			this.diameterChunks = cubeGen.diameterChunks();
		} else {
			this.diameterChunks = CubePlanetMath.MIN_DIAMETER_CHUNKS;
		}
	}

	@Override
	protected void init() {
		this.layout.addToHeader(new StringWidget(this.title, this.font));

		LinearLayout content = this.layout.addToContents(LinearLayout.vertical().spacing(10));
		content.defaultCellSetting().alignHorizontallyCenter();

		double initialSliderVal = (double) (this.diameterChunks - CubePlanetMath.MIN_DIAMETER_CHUNKS)
			/ (double) (CubePlanetMath.MAX_DIAMETER_CHUNKS - CubePlanetMath.MIN_DIAMETER_CHUNKS);

		content.addChild(new AbstractSliderButton(0, 0, 310, 20, getSliderComponent(this.diameterChunks), initialSliderVal) {
			@Override
			protected void updateMessage() {
				this.setMessage(getSliderComponent(CustomizeCubeWorldScreen.this.diameterChunks));
			}

			@Override
			protected void applyValue() {
				int raw = CubePlanetMath.MIN_DIAMETER_CHUNKS + (int) Math.round(
					this.value * (CubePlanetMath.MAX_DIAMETER_CHUNKS - CubePlanetMath.MIN_DIAMETER_CHUNKS)
				);
				// Snap to even chunk counts so the radius (chunks / 2 * 16) is chunk-aligned
				CustomizeCubeWorldScreen.this.diameterChunks = Mth.clamp((raw / 2) * 2, CubePlanetMath.MIN_DIAMETER_CHUNKS, CubePlanetMath.MAX_DIAMETER_CHUNKS);
				CustomizeCubeWorldScreen.this.refreshPreviewLabels();
			}
		});

		this.centerLabel = content.addChild(new StringWidget(310, 12, Component.empty(), this.font));
		this.coreLabel = content.addChild(new StringWidget(310, 12, Component.empty(), this.font));
		this.boundsLabel = content.addChild(new StringWidget(310, 12, Component.empty(), this.font));
		this.refreshPreviewLabels();

		LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> {
			this.applySettings();
			this.onClose();
		}).build());
		footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose()).build());

		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	private void refreshPreviewLabels() {
		int centerY = CubePlanetMath.getCenterY(this.diameterChunks);
		int radius = CubePlanetMath.getRadiusBlocks(this.diameterChunks);
		int innerBedrockR = CubePlanetMath.getInnerBedrockRadius(this.diameterChunks);
		int bottomSurfaceY = centerY - radius;

		if (this.centerLabel != null) {
			this.centerLabel.setMessage(Component.translatable("cubeworld.customize.center", centerY));
		}
		if (this.coreLabel != null) {
			if (!CubePlanetMath.hasHollowCore(this.diameterChunks)) {
				this.coreLabel.setMessage(Component.translatable("cubeworld.customize.core.solid"));
			} else {
				int bedrockWidth = innerBedrockR * 2 + 1;
				int voidWidth = Math.max(0, innerBedrockR * 2 - 1);
				this.coreLabel.setMessage(Component.translatable(
					"cubeworld.customize.core.hollow",
					bedrockWidth, bedrockWidth, bedrockWidth,
					voidWidth, voidWidth, voidWidth
				));
			}
		}
		if (this.boundsLabel != null) {
			this.boundsLabel.setMessage(Component.translatable("cubeworld.customize.surface_bounds", bottomSurfaceY));
		}
	}

	private static Component getSliderComponent(int chunks) {
		return Component.translatable("cubeworld.customize.diameter", chunks, chunks * 16);
	}

	private void applySettings() {
		int selectedChunks = this.diameterChunks;
		this.parent.getUiState().updateDimensions((registryAccess, dimensions) -> {
			ChunkGenerator oldOverworld = dimensions.overworld();
			BiomeSource biomeSource = oldOverworld.getBiomeSource();

			Registry<NoiseGeneratorSettings> noiseReg = registryAccess.lookupOrThrow(Registries.NOISE_SETTINGS);
			Holder<NoiseGeneratorSettings> settings = (oldOverworld instanceof CubeChunkGenerator cubeGen)
				? cubeGen.generatorSettings()
				: noiseReg.getOrThrow(NoiseGeneratorSettings.OVERWORLD);

			CubeChunkGenerator newGenerator = new CubeChunkGenerator(biomeSource, settings, selectedChunks);

			Registry<DimensionType> dimTypes = registryAccess.lookupOrThrow(Registries.DIMENSION_TYPE);
			Holder<DimensionType> cubeDimType = dimTypes.get(CubeWorldMod.CUBE_OVERWORLD_TYPE)
				.map(ref -> (Holder<DimensionType>) ref)
				.orElseGet(() -> dimensions.get(LevelStem.OVERWORLD).orElseThrow().type());

			Map<net.minecraft.resources.ResourceKey<LevelStem>, LevelStem> updatedMap =
				WorldDimensions.withOverworld(dimensions.dimensions(), cubeDimType, newGenerator);
			return new WorldDimensions(updatedMap);
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

package dev.cubeworld.client.gui;

import dev.cubeworld.worldgen.CubeBiomeMap;
import dev.cubeworld.worldgen.CubeSettings;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Customizes a new cube world. The left side shows the biomes of the seed on a cube that turns. The right side lists
 * every biome: the seed has the white ones and lacks the gray ones. A search tries random seeds until one has every
 * checked biome. A preset checks a set of biomes and starts a search. Done writes the seed and the {@link CubeSettings}
 * into the world.
 */
public class CustomizeCubeWorldScreen extends Screen {
	/** Cells along each face edge for the preview, and for the quick test of each seed in a search. */
	private static final int PREVIEW_CELLS = 64;
	private static final int SEARCH_CELLS = 24;
	private static final int MAX_WIDTH = 460;
	private static final int GAP = 8;
	private static final int SEARCH_BUTTON_WIDTH = 72;
	private static final int FOOTER_BUTTON_WIDTH = 100;

	private static final List<Preset> PRESETS = List.of(
		new Preset("custom"),
		new Preset("every_climate", "desert", "jungle", "snowy_plains", "savanna", "taiga"),
		new Preset("mushroom_island", "mushroom_fields"),
		new Preset("frozen", "ice_spikes", "frozen_peaks", "snowy_taiga", "frozen_ocean"),
		new Preset("tropics", "jungle", "bamboo_jungle", "warm_ocean", "mangrove_swamp"),
		new Preset("badlands", "badlands", "eroded_badlands", "wooded_badlands"),
		new Preset("caves", "lush_caves", "dripstone_caves", "deep_dark", "sulfur_caves")
	);

	private final CreateWorldScreen parent;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private final HolderGetter<NormalNoise> noises;
	private final NoiseGeneratorSettings noiseSettings;
	private final MultiNoiseBiomeSource biomeSource;
	private final Set<Holder<Biome>> required = new HashSet<>();
	/** Each sampling or search gets the next number. A task stops when the number moves on. */
	private final AtomicInteger task = new AtomicInteger();
	private final AtomicInteger tried = new AtomicInteger();

	private CubeSettings settings;
	private long seed;
	private String seedText;
	private Preset preset = PRESETS.getFirst();
	private boolean searching;
	private @Nullable CubeBiomeMap map;
	private @Nullable Set<Holder<Biome>> present;
	/** The seed and settings of {@link #map}, or of the sampling that runs for it. */
	private long mapSeed;
	private @Nullable CubeSettings mapSettings;

	private CubePreviewWidget preview;
	private EditBox seedBox;
	private Button searchButton;
	private StringWidget summary;
	private CycleButton<Preset> presetButton;
	private BiomeFilterList list;
	private boolean settingSeedText;

	public CustomizeCubeWorldScreen(CreateWorldScreen parent, WorldCreationContext context) {
		super(Component.translatable("cubeworld.customize.title"));
		this.parent = parent;
		this.settings = CubeSettings.of(context.selectedDimensions().overworld());
		NoiseBasedChunkGenerator generator = (NoiseBasedChunkGenerator) context.selectedDimensions().overworld();
		this.noises = context.worldgenLoadContext().lookupOrThrow(Registries.NOISE);
		this.noiseSettings = generator.generatorSettings().value();
		this.biomeSource = (MultiNoiseBiomeSource) generator.getBiomeSource();
		// An empty seed field gives the world the random seed that the options already hold.
		this.seed = context.options().seed();
		String text = parent.getUiState().getSeed();
		this.seedText = text.isBlank() ? Long.toString(this.seed) : text;
	}

	@Override
	protected void init() {
		this.layout.addTitleHeader(this.title, this.font);

		this.preview = this.addRenderableWidget(new CubePreviewWidget());
		this.seedBox = this.addRenderableWidget(new EditBox(this.font, 0, 20, Component.translatable("selectWorld.enterSeed")));
		this.seedBox.setMaxLength(64);
		this.seedBox.setValue(this.seedText);
		this.seedBox.setResponder(this::onSeedTyped);
		this.searchButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> this.toggleSearch())
			.size(SEARCH_BUTTON_WIDTH, 20)
			.tooltip(Tooltip.create(Component.translatable("cubeworld.customize.search.tooltip")))
			.build());
		this.summary = this.addRenderableWidget(new StringWidget(0, 12, Component.empty(), this.font));
		this.presetButton = this.addRenderableWidget(CycleButton.builder((Preset preset) -> preset.name, this.preset)
			.withValues(PRESETS)
			.create(0, 0, 0, 20, Component.translatable("cubeworld.customize.preset"), (button, preset) -> this.choosePreset(preset)));
		this.list = this.addRenderableWidget(new BiomeFilterList(this.minecraft, this.biomeSource.possibleBiomes(), this.required, this::onFilterChanged));

		LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(GAP));
		footer.addChild(Button.builder(Component.translatable("cubeworld.customize.settings"), button ->
			this.minecraft.gui.setScreen(new CubeSettingsScreen(this, this.settings, settings -> this.settings = settings))
		).width(FOOTER_BUTTON_WIDTH).build());
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> {
			this.applySettings();
			this.onClose();
		}).width(FOOTER_BUTTON_WIDTH).build());
		footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose()).width(FOOTER_BUTTON_WIDTH).build());
		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();

		this.refresh();
	}

	@Override
	public void added() {
		// Back from the Cube Settings screen. The first time, init comes after this and refreshes.
		if (this.preview != null) {
			this.refresh();
		}
	}

	/** Samples the seed again if the map is out of date. */
	private void refresh() {
		this.showMap();
		if (!this.searching && (this.map == null || this.mapSeed != this.seed || !this.settings.equals(this.mapSettings))) {
			this.sample();
		}
		this.refreshSearchButton();
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
		int width = Math.min(this.width - 2 * GAP, MAX_WIDTH);
		int left = (this.width - width) / 2;
		int top = this.layout.getHeaderHeight() + GAP;
		int bottom = this.height - this.layout.getFooterHeight() - GAP;
		int columnWidth = (width - 2 * GAP) / 2;
		int right = left + width - columnWidth;

		int seedRow = bottom - 20;
		int summaryRow = seedRow - 4 - 12;
		int side = Math.max(0, Math.min(columnWidth, summaryRow - 4 - top));
		this.preview.setRectangle(side, side, left + (columnWidth - side) / 2, top);
		this.summary.setRectangle(columnWidth, 12, left, summaryRow);
		this.seedBox.setRectangle(columnWidth - SEARCH_BUTTON_WIDTH - 4, 20, left, seedRow);
		this.searchButton.setPosition(left + columnWidth - SEARCH_BUTTON_WIDTH, seedRow);

		this.presetButton.setRectangle(columnWidth, 20, right, top);
		// The list draws separators 2 pixels above and below itself.
		this.list.updateSizeAndPosition(columnWidth, bottom - top - 24 - 2, right, top + 24 + 2);
	}

	@Override
	public void tick() {
		if (this.searching) {
			this.refreshSearchButton();
		}
	}

	private void onSeedTyped(String text) {
		if (this.settingSeedText) {
			return;
		}
		this.seedText = text;
		this.seed = WorldOptions.parseSeed(text).orElseGet(WorldOptions::randomSeed);
		this.searching = false;
		this.sample();
		this.refreshSearchButton();
	}

	private void onFilterChanged() {
		this.preset = PRESETS.getFirst();
		this.presetButton.setValue(this.preset);
	}

	private void choosePreset(Preset preset) {
		this.preset = preset;
		if (preset.biomes.isEmpty()) {
			return;
		}
		this.required.clear();
		for (Holder<Biome> biome : this.biomeSource.possibleBiomes()) {
			if (biome.unwrapKey().map(key -> preset.biomes.contains(key.identifier())).orElse(false)) {
				this.required.add(biome);
			}
		}
		this.startSearch();
	}

	private void toggleSearch() {
		if (this.searching) {
			this.task.incrementAndGet();
			this.searching = false;
			this.refreshSearchButton();
		} else {
			this.startSearch();
		}
	}

	/** Tries random seeds until one has every required biome. With no required biome, the first seed it tries is the one. */
	private void startSearch() {
		int id = this.task.incrementAndGet();
		BooleanSupplier stop = () -> this.task.get() != id;
		Set<Holder<Biome>> wanted = Set.copyOf(this.required);
		CubeSettings settings = this.settings;
		this.tried.set(0);
		this.searching = true;
		this.refreshSearchButton();
		run(() -> {
			Random random = new Random();
			while (!stop.getAsBoolean()) {
				long seed = random.nextLong();
				CubeBiomeMap.Sampler sampler = this.sampler(settings, seed);
				CubeBiomeMap quick = sampler.sample(SEARCH_CELLS, wanted.isEmpty() ? null : wanted, stop);
				if (quick == null) {
					return;
				}
				this.tried.incrementAndGet();
				if (quick.present.containsAll(wanted)) {
					CubeBiomeMap full = sampler.sample(PREVIEW_CELLS, null, stop);
					if (full == null) {
						return;
					}
					// Both maps are samples of the same world, so a biome in either one is in the world.
					Set<Holder<Biome>> present = new HashSet<>(full.present);
					present.addAll(quick.present);
					this.minecraft.execute(() -> {
						if (!stop.getAsBoolean()) {
							this.searching = false;
							this.seed = seed;
							this.seedText = Long.toString(seed);
							this.settingSeedText = true;
							this.seedBox.setValue(this.seedText);
							this.settingSeedText = false;
							this.setMap(full, present, seed, settings);
							this.refreshSearchButton();
						}
					});
					return;
				}
			}
		});
	}

	/** Samples the shown seed for the preview. */
	private void sample() {
		int id = this.task.incrementAndGet();
		BooleanSupplier stop = () -> this.task.get() != id;
		long seed = this.seed;
		CubeSettings settings = this.settings;
		this.mapSeed = seed;
		this.mapSettings = settings;
		this.map = null;
		this.present = null;
		this.showMap();
		run(() -> {
			CubeBiomeMap map = this.sampler(settings, seed).sample(PREVIEW_CELLS, null, stop);
			if (map != null) {
				this.minecraft.execute(() -> {
					if (!stop.getAsBoolean()) {
						this.setMap(map, map.present, seed, settings);
					}
				});
			}
		});
	}

	private CubeBiomeMap.Sampler sampler(CubeSettings settings, long seed) {
		return new CubeBiomeMap.Sampler(this.noises, this.noiseSettings, this.biomeSource, settings, seed);
	}

	private static void run(Runnable work) {
		Thread thread = new Thread(work, "Cube World preview");
		thread.setDaemon(true);
		thread.start();
	}

	private void setMap(CubeBiomeMap map, Set<Holder<Biome>> present, long seed, CubeSettings settings) {
		this.map = map;
		this.present = present;
		this.mapSeed = seed;
		this.mapSettings = settings;
		this.showMap();
	}

	private void showMap() {
		this.preview.setMap(this.map);
		this.preview.setPlaceholder(Component.translatable("cubeworld.customize.preview.loading"));
		this.list.setPresent(this.present);
		this.summary.setMessage(this.present == null
			? Component.empty()
			: Component.translatable("cubeworld.customize.summary", this.biomeSource.possibleBiomes().stream().filter(this.present::contains).count(),
				this.biomeSource.possibleBiomes().size()));
	}

	private void refreshSearchButton() {
		this.searchButton.setMessage(this.searching
			? Component.translatable("cubeworld.customize.search.stop", this.tried.get())
			: Component.translatable("cubeworld.customize.search"));
	}

	private void applySettings() {
		this.parent.getUiState().setSeed(this.seedText.isBlank() ? Long.toString(this.seed) : this.seedText);
		CubeSettings settings = this.settings;
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
	public void removed() {
		// Stops a search or sampling that runs. The screen samples again when it comes back.
		this.task.incrementAndGet();
		this.searching = false;
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	/** A set of required biomes with a name. The custom preset has no biomes: it is the name for a set that the player checks. */
	private record Preset(Component name, Set<Identifier> biomes) {
		Preset(String key, String... biomes) {
			this(
				Component.translatable("cubeworld.customize.preset." + key),
				Set.of(Arrays.stream(biomes).map(Identifier::withDefaultNamespace).toArray(Identifier[]::new))
			);
		}
	}
}

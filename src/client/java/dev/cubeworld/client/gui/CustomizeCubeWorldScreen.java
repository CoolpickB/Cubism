package dev.cubeworld.client.gui;

import dev.cubeworld.worldgen.CubeBiomeMap;
import dev.cubeworld.worldgen.CubeSettings;
import dev.cubeworld.worldgen.CubeStructureMap;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Customizes a new cube world, laid out like the Superflat screen. The middle shows the biomes of the seed on a cube
 * that turns. The left list has every biome and the right list every structure: the seed has the white ones and lacks
 * the gray ones. A search tries random seeds until one has everything that is checked. A preset checks a set of
 * biomes and structures and starts a search. Done writes the seed and the {@link CubeSettings} into the world.
 */
public class CustomizeCubeWorldScreen extends Screen {
	/** Cells along each face edge for the preview, and for the quick test of each seed in a search. */
	private static final int PREVIEW_CELLS = 64;
	private static final int SEARCH_CELLS = 24;
	private static final int GAP = 4;
	private static final int ARROW_WIDTH = 14;
	private static final int SEARCH_WIDTH = 52;
	/** Searches pick seeds below this, so that they fit in the seed box: at most 7 digits. */
	private static final int SEARCH_SEED_BOUND = 10_000_000;
	private static final int HISTORY_SIZE = 50;
	/** A search leaves two cores to the game, and no more than 8 threads work on it. */
	private static final int SEARCH_THREADS = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 2));
	/** A rate shows after this many seeds, before that it is noise. */
	private static final int MIN_RATE_SAMPLES = 20;
	/** A typed seed loads after this many ticks without a key. */
	private static final int TYPING_PAUSE_TICKS = 20;
	private static final float SIDE_SHARE = 0.32F;
	private static final Identifier LIST_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/menu_list_background.png");

	static final List<Preset> PRESETS = List.of(
		Preset.of("none", List.of(), List.of()),
		Preset.of("every_climate", List.of("desert", "jungle", "snowy_plains", "savanna", "taiga"), List.of()),
		Preset.of("mushroom_island", List.of("mushroom_fields"), List.of()),
		Preset.of("frozen", List.of("ice_spikes", "frozen_peaks", "snowy_taiga", "frozen_ocean"), List.of()),
		Preset.of("tropics", List.of("jungle", "bamboo_jungle", "warm_ocean", "mangrove_swamp"), List.of()),
		Preset.of("badlands", List.of("badlands", "eroded_badlands", "wooded_badlands"), List.of()),
		Preset.of("caves", List.of("lush_caves", "dripstone_caves", "deep_dark", "sulfur_caves"), List.of()),
		Preset.of("structure_hunter", List.of(), List.of("villages", "pillager_outposts", "woodland_mansions", "ocean_monuments", "ancient_cities"))
	);

	private final CreateWorldScreen parent;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, 64);
	private final HolderGetter<NormalNoise> noises;
	private final HolderLookup<StructureSet> structureSets;
	private final NoiseGeneratorSettings noiseSettings;
	private final MultiNoiseBiomeSource biomeSource;
	private final List<Holder<StructureSet>> structureCandidates;
	private final Set<Holder<Biome>> requiredBiomes = new HashSet<>();
	private final Set<ResourceKey<StructureSet>> requiredStructures = new HashSet<>();
	/** Each sampling or search gets the next number. A task stops when the number moves on. */
	private final AtomicInteger task = new AtomicInteger();
	/** The seeds that the screen showed, oldest first, as the player typed them. {@link #historyAt} is the one that shows now. */
	private final List<String> history = new ArrayList<>();
	private int historyAt;

	private CubeSettings settings;
	private long seed;
	private String seedText;
	private int searchWidth = SEARCH_WIDTH;
	private boolean searching;
	/** The counts of the latest search. The lists show its rates until the checked items change. */
	private volatile @Nullable SearchStats stats;
	private @Nullable Found found;
	/** The seed and settings of {@link #found}. */
	private long foundSeed;
	private @Nullable CubeSettings foundSettings;

	private CubePreviewWidget preview;
	private EditBox seedBox;
	private Button searchButton;
	private Button backButton;
	private Button forwardButton;
	private FilterList<Holder<Biome>> biomeList;
	private FilterList<ResourceKey<StructureSet>> structureList;
	private boolean settingSeedText;
	/** True while a typed seed waits for the pause to end. */
	private boolean typedPending;
	private int typingPause;

	/** What a sampling of a seed found. */
	private record Found(CubeBiomeMap map, Set<Holder<Biome>> biomes, Set<ResourceKey<StructureSet>> structures) {
	}

	public CustomizeCubeWorldScreen(CreateWorldScreen parent, WorldCreationContext context) {
		super(Component.translatable("cubeworld.customize.title"));
		this.parent = parent;
		this.settings = CubeSettings.of(context.selectedDimensions().overworld());
		NoiseBasedChunkGenerator generator = (NoiseBasedChunkGenerator) context.selectedDimensions().overworld();
		this.noises = context.worldgenLoadContext().lookupOrThrow(Registries.NOISE);
		this.structureSets = context.worldgenLoadContext().lookupOrThrow(Registries.STRUCTURE_SET);
		this.noiseSettings = generator.generatorSettings().value();
		this.biomeSource = (MultiNoiseBiomeSource) generator.getBiomeSource();
		this.structureCandidates = CubeStructureMap.candidates(this.structureSets, this.biomeSource);
		// An empty seed field gives the world the random seed that the options already hold.
		this.seed = context.options().seed();
		String text = parent.getUiState().getSeed();
		this.seedText = text.isBlank() ? Long.toString(this.seed) : text;
	}

	@Override
	protected void init() {
		this.layout.addTitleHeader(this.title, this.font);

		this.biomeList = this.addRenderableWidget(new FilterList<>(
			this.minecraft, this.biomeSource.possibleBiomes(), this.requiredBiomes, BiomeColors::name, BiomeColors::color, this::clearRates
		));
		this.structureList = this.addRenderableWidget(new FilterList<>(
			this.minecraft, this.structureCandidates.stream().flatMap(set -> set.unwrapKey().stream()).toList(), this.requiredStructures,
			CustomizeCubeWorldScreen::structureName, null, this::clearRates
		));
		this.preview = this.addRenderableWidget(new CubePreviewWidget());
		this.seedBox = this.addRenderableWidget(new EditBox(this.font, 100, 20, Component.translatable("selectWorld.enterSeed")) {
			@Override
			public void setFocused(boolean focused) {
				super.setFocused(focused);
				if (!focused) {
					CustomizeCubeWorldScreen.this.loadTypedSeed();
					CustomizeCubeWorldScreen.this.remember(CustomizeCubeWorldScreen.this.seedText);
				}
			}
		});
		this.seedBox.setMaxLength(64);
		this.seedBox.setValue(this.seedText);
		this.seedBox.setResponder(this::onSeedTyped);
		this.backButton = this.addRenderableWidget(Button.builder(Component.literal("<"), button -> this.step(-1))
			.tooltip(Tooltip.create(Component.translatable("cubeworld.customize.history.back")))
			.build());
		this.forwardButton = this.addRenderableWidget(Button.builder(Component.literal(">"), button -> this.step(1))
			.tooltip(Tooltip.create(Component.translatable("cubeworld.customize.history.forward")))
			.build());
		if (this.history.isEmpty()) {
			this.history.add(this.seedText);
		}
		this.searchButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> this.toggleSearch())
			.tooltip(Tooltip.create(Component.translatable("cubeworld.customize.search.tooltip")))
			.build());

		LinearLayout footer = this.layout.addToFooter(LinearLayout.vertical().spacing(4));
		footer.defaultCellSetting().alignVerticallyMiddle();
		LinearLayout topButtons = footer.addChild(LinearLayout.horizontal().spacing(8));
		LinearLayout bottomButtons = footer.addChild(LinearLayout.horizontal().spacing(8));
		topButtons.addChild(Button.builder(Component.translatable("cubeworld.customize.settings"), button ->
			this.minecraft.gui.setScreen(new CubeSettingsScreen(this, this.settings, settings -> this.settings = settings))
		).build());
		topButtons.addChild(Button.builder(Component.translatable("cubeworld.customize.presets"), button ->
			this.minecraft.gui.setScreen(new PresetScreen(this, PRESETS, this::usePreset))
		).build());
		bottomButtons.addChild(Button.builder(CommonComponents.GUI_DONE, button -> {
			this.applySettings();
			this.onClose();
		}).build());
		bottomButtons.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose()).build());
		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
		// The box scrolls to the cursor when the text is set. Start at the left now that the box has its width.
		this.seedBox.moveCursorToStart(false);

		this.refresh();
	}

	@Override
	public void added() {
		// Back from the Cube Settings or Presets screen. The first time, init comes after this and refreshes.
		if (this.preview != null) {
			this.refresh();
		}
	}

	/** Samples the seed again if the map is out of date. */
	private void refresh() {
		this.showFound();
		if (!this.searching && (this.found == null || this.foundSeed != this.seed || !this.settings.equals(this.foundSettings))) {
			this.sample();
		}
		this.refreshSearchButton();
		this.refreshHistoryButtons();
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
		int top = this.layout.getHeaderHeight();
		int height = this.layout.getContentHeight();
		int side = Math.round(this.width * SIDE_SHARE);
		int middle = this.width - 2 * side;
		this.biomeList.updateSizeAndPosition(side, height, 0, top);
		this.structureList.updateSizeAndPosition(side, height, this.width - side, top);

		int left = side + GAP;
		int inner = middle - 2 * GAP;
		int seedRow = top + height - GAP - 20;
		int previewSize = Math.max(0, Math.min(inner, seedRow - GAP - (top + GAP)));
		this.preview.setRectangle(previewSize, previewSize, left + (inner - previewSize) / 2, top + GAP);
		this.backButton.setRectangle(ARROW_WIDTH, 20, left, seedRow);
		this.forwardButton.setRectangle(ARROW_WIDTH, 20, left + ARROW_WIDTH + 2, seedRow);
		int seedLeft = left + 2 * ARROW_WIDTH + 2 + GAP;
		this.searchButton.setRectangle(this.searchWidth, 20, left + inner - this.searchWidth, seedRow);
		this.seedBox.setRectangle(left + inner - this.searchWidth - GAP - seedLeft, 20, seedLeft, seedRow);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		// The lists draw their own background and separators. This is the same for the middle column.
		int side = Math.round(this.width * SIDE_SHARE);
		int top = this.layout.getHeaderHeight();
		int height = this.layout.getContentHeight();
		int middle = this.width - 2 * side;
		graphics.blit(RenderPipelines.GUI_TEXTURED, LIST_BACKGROUND, side, top, (float) side, (float) top, middle, height, 32, 32);
		graphics.blit(RenderPipelines.GUI_TEXTURED, Screen.HEADER_SEPARATOR, side, top - 2, 0.0F, 0.0F, middle, 2, 32, 2);
		graphics.blit(RenderPipelines.GUI_TEXTURED, Screen.FOOTER_SEPARATOR, side, top + height, 0.0F, 0.0F, middle, 2, 32, 2);
	}

	@Override
	public void tick() {
		if (this.typedPending && --this.typingPause <= 0) {
			this.loadTypedSeed();
		}
		if (this.searching) {
			this.refreshSearchButton();
		}
	}

	private void onSeedTyped(String text) {
		if (this.settingSeedText) {
			return;
		}
		this.seedText = text;
		this.task.incrementAndGet();
		this.searching = false;
		this.typedPending = true;
		this.typingPause = TYPING_PAUSE_TICKS;
		this.refreshSearchButton();
	}

	/** Loads the seed that the player typed, now instead of after the pause. */
	private void loadTypedSeed() {
		if (!this.typedPending) {
			return;
		}
		this.typedPending = false;
		this.seed = WorldOptions.parseSeed(this.seedText).orElseGet(WorldOptions::randomSeed);
		this.remember(this.seedText.isBlank() ? Long.toString(this.seed) : this.seedText);
		this.sample();
	}

	private void usePreset(Preset preset) {
		this.requiredBiomes.clear();
		for (Holder<Biome> biome : this.biomeSource.possibleBiomes()) {
			if (biome.unwrapKey().map(key -> preset.biomes.contains(key.identifier())).orElse(false)) {
				this.requiredBiomes.add(biome);
			}
		}
		this.requiredStructures.clear();
		for (Holder<StructureSet> set : this.structureCandidates) {
			set.unwrapKey().filter(key -> preset.structures.contains(key.identifier())).ifPresent(this.requiredStructures::add);
		}
		if (!this.requiredBiomes.isEmpty() || !this.requiredStructures.isEmpty()) {
			this.startSearch();
		}
	}

	/** Makes the seed the newest entry of the history, and drops the entries after the one that shows. */
	private void remember(String text) {
		if (text.isBlank() || this.historyAt < this.history.size() && this.history.get(this.historyAt).equals(text)) {
			return;
		}
		while (this.history.size() > this.historyAt + 1) {
			this.history.removeLast();
		}
		this.history.add(text);
		if (this.history.size() > HISTORY_SIZE) {
			this.history.removeFirst();
		}
		this.historyAt = this.history.size() - 1;
		this.refreshHistoryButtons();
	}

	private void step(int direction) {
		this.typedPending = false;
		this.remember(this.seedText);
		this.historyAt = Mth.clamp(this.historyAt + direction, 0, this.history.size() - 1);
		this.task.incrementAndGet();
		this.searching = false;
		this.seedText = this.history.get(this.historyAt);
		this.seed = WorldOptions.parseSeed(this.seedText).orElseGet(WorldOptions::randomSeed);
		this.settingSeedText = true;
		this.seedBox.setValue(this.seedText);
		this.seedBox.moveCursorToStart(false);
		this.settingSeedText = false;
		this.sample();
		this.refreshSearchButton();
		this.refreshHistoryButtons();
	}

	private void refreshHistoryButtons() {
		this.backButton.active = this.historyAt > 0;
		this.forwardButton.active = this.historyAt < this.history.size() - 1;
	}

	private void toggleSearch() {
		if (this.searching) {
			this.task.incrementAndGet();
			this.searching = false;
			this.settleOnBest();
			this.refreshSearchButton();
		} else {
			this.startSearch();
		}
	}

	/**
	 * Tries random seeds on several threads until one has everything that is required. With nothing required, the first
	 * seed it tries is the one. The search also keeps the seed that has the most of the required items: Stop leaves the
	 * screen on that seed.
	 */
	private void startSearch() {
		this.typedPending = false;
		this.remember(this.seedText);
		int id = this.task.incrementAndGet();
		BooleanSupplier stop = () -> this.task.get() != id;
		Set<Holder<Biome>> wantedBiomes = Set.copyOf(this.requiredBiomes);
		Set<ResourceKey<StructureSet>> wantedStructures = Set.copyOf(this.requiredStructures);
		CubeSettings settings = this.settings;
		SearchStats stats = SearchStats.of(wantedBiomes, wantedStructures);
		this.stats = stats;
		this.biomeList.setRates(stats::biomeRate);
		this.structureList.setRates(stats::structureRate);
		this.searching = true;
		this.refreshSearchButton();
		BooleanSupplier halt = () -> stop.getAsBoolean() || stats.finished().get();
		for (int i = 0; i < SEARCH_THREADS; i++) {
			run(() -> this.searchSeeds(stats, wantedBiomes, wantedStructures, settings, stop, halt));
		}
	}

	/** One thread of a search. {@code stop} ends the search, {@code halt} also ends it when another thread found the seed. */
	private void searchSeeds(
		SearchStats stats, Set<Holder<Biome>> wantedBiomes, Set<ResourceKey<StructureSet>> wantedStructures, CubeSettings settings,
		BooleanSupplier stop, BooleanSupplier halt
	) {
		Random random = ThreadLocalRandom.current();
		while (!halt.getAsBoolean()) {
			long seed = random.nextInt(SEARCH_SEED_BOUND);
			CubeBiomeMap.Sampler sampler = this.sampler(settings, seed);
			CubeBiomeMap quick = sampler.sample(SEARCH_CELLS, wantedBiomes.isEmpty() ? null : wantedBiomes, halt);
			if (quick == null) {
				return;
			}
			stats.tried().incrementAndGet();
			int score = 0;
			for (Holder<Biome> biome : wantedBiomes) {
				if (quick.present.contains(biome)) {
					score++;
					stats.biomeHits().get(biome).incrementAndGet();
				}
			}
			// The structures cost much more than the biomes. A seed that cannot beat the best so far is not worth them.
			if (!wantedStructures.isEmpty() && score + wantedStructures.size() > stats.best().get().score()) {
				Set<ResourceKey<StructureSet>> structures = this.structures(sampler, settings, wantedStructures);
				stats.structuresChecked().incrementAndGet();
				structures.forEach(key -> stats.structureHits().get(key).incrementAndGet());
				score += structures.size();
			}
			if (score < stats.total()) {
				stats.offer(score, seed);
				continue;
			}
			if (!stats.finished().compareAndSet(false, true)) {
				return;
			}
			CubeBiomeMap full = sampler.sample(PREVIEW_CELLS, null, stop);
			if (full == null) {
				return;
			}
			// Both maps are samples of the same world, so a biome in either one is in the world.
			Set<Holder<Biome>> biomes = new HashSet<>(full.present);
			biomes.addAll(quick.present);
			Found result = new Found(full, biomes, this.structures(sampler, settings, null));
			this.minecraft.execute(() -> {
				if (!stop.getAsBoolean()) {
					this.searching = false;
					this.showSeed(seed);
					this.setFound(result, seed, settings);
					this.refreshSearchButton();
				}
			});
			return;
		}
	}

	/** Puts a seed in the seed box and the history. The caller samples it. */
	private void showSeed(long seed) {
		this.seed = seed;
		this.seedText = Long.toString(seed);
		this.settingSeedText = true;
		this.seedBox.setValue(this.seedText);
		this.seedBox.moveCursorToStart(false);
		this.settingSeedText = false;
		this.remember(this.seedText);
	}

	/** After Stop: the screen shows the seed with the most required items that the search saw, if it had any. */
	private void settleOnBest() {
		SearchStats stats = this.stats;
		SearchStats.Best best = stats == null ? null : stats.best().get();
		if (best != null && best.score() > 0) {
			this.showSeed(best.seed());
			this.sample();
		} else {
			// The search may have cut off the sampling of the shown seed.
			this.refresh();
		}
	}

	private void clearRates() {
		this.stats = null;
		this.biomeList.setRates(null);
		this.structureList.setRates(null);
	}

	/** Samples the shown seed for the preview. The screen keeps showing the last map, its counts and its marks until the new ones come. */
	private void sample() {
		int id = this.task.incrementAndGet();
		BooleanSupplier stop = () -> this.task.get() != id;
		long seed = this.seed;
		CubeSettings settings = this.settings;
		this.showFound();
		run(() -> {
			CubeBiomeMap.Sampler sampler = this.sampler(settings, seed);
			CubeBiomeMap map = sampler.sample(PREVIEW_CELLS, null, stop);
			if (map != null) {
				Found result = new Found(map, map.present, this.structures(sampler, settings, null));
						this.minecraft.execute(() -> {
					if (!stop.getAsBoolean()) {
						this.setFound(result, seed, settings);
					}
				});
			}
		});
	}

	private Set<ResourceKey<StructureSet>> structures(
		CubeBiomeMap.Sampler sampler, CubeSettings settings, @Nullable Set<ResourceKey<StructureSet>> wanted
	) {
		return CubeStructureMap.find(sampler, settings, this.structureSets, this.biomeSource, this.structureCandidates, wanted);
	}

	private CubeBiomeMap.Sampler sampler(CubeSettings settings, long seed) {
		return new CubeBiomeMap.Sampler(this.noises, this.noiseSettings, this.biomeSource, settings, seed);
	}

	private static void run(Runnable work) {
		Thread thread = new Thread(work, "Cube World preview");
		thread.setDaemon(true);
		thread.start();
	}

	private void setFound(Found found, long seed, CubeSettings settings) {
		this.found = found;
		this.foundSeed = seed;
		this.foundSettings = settings;
		this.showFound();
	}

	private void showFound() {
		Found found = this.found;
		this.preview.setMap(found == null ? null : found.map);
		this.preview.setPlaceholder(Component.translatable("cubeworld.customize.preview.loading"));
		this.preview.setLoading(!this.searching && (found == null || this.foundSeed != this.seed || !this.settings.equals(this.foundSettings)));
		this.biomeList.setPresent(found == null ? null : found.biomes);
		this.structureList.setPresent(found == null ? null : found.structures);
		int biomes = this.biomeSource.possibleBiomes().size();
		int structures = this.structureCandidates.size();
		this.biomeList.setTitle(found == null
			? Component.translatable("cubeworld.customize.biomes")
			: Component.translatable("cubeworld.customize.biomes.count", this.biomeSource.possibleBiomes().stream().filter(found.biomes::contains).count(), biomes));
		this.structureList.setTitle(found == null
			? Component.translatable("cubeworld.customize.structures")
			: Component.translatable("cubeworld.customize.structures.count", found.structures.size(), structures));
	}

	private void refreshSearchButton() {
		SearchStats stats = this.stats;
		Component label;
		if (!this.searching || stats == null) {
			label = Component.translatable("cubeworld.customize.search");
		} else if (stats.total() > 1) {
			label = Component.translatable("cubeworld.customize.search.progress", Math.max(0, stats.best().get().score()), stats.total(), stats.tried().get());
		} else {
			label = Component.translatable("cubeworld.customize.search.stop", stats.tried().get());
		}
		this.searchButton.setMessage(label);
		// The button grows with the count that it shows, and the seed box gives way.
		int width = Mth.clamp(this.font.width(label) + 12, SEARCH_WIDTH, this.searchMaxWidth());
		if (width != this.searchWidth) {
			this.searchWidth = width;
			this.repositionElements();
		}
	}

	/** The widest the search button gets: the seed box keeps room for 7 digits. */
	private int searchMaxWidth() {
		int middle = this.width - 2 * Math.round(this.width * SIDE_SHARE);
		return Math.max(SEARCH_WIDTH, middle - 2 * GAP - 2 * ARROW_WIDTH - 2 - 2 * GAP - this.font.width("0000000") - 10);
	}

	private static Component structureName(ResourceKey<StructureSet> key) {
		String translationKey = "cubeworld.customize.structure." + key.identifier().getPath();
		if (Language.getInstance().has(translationKey)) {
			return Component.translatable(translationKey);
		}
		StringBuilder name = new StringBuilder();
		for (String word : key.identifier().getPath().split("_")) {
			name.append(name.isEmpty() ? "" : " ").append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
		}
		return Component.literal(name.toString());
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

	/** The counts of one search, which its threads update. Rates are the share of the tried seeds that had the item. */
	private record SearchStats(
		int total, Map<Holder<Biome>, AtomicInteger> biomeHits, Map<ResourceKey<StructureSet>, AtomicInteger> structureHits,
		AtomicInteger tried, AtomicInteger structuresChecked, AtomicReference<Best> best, AtomicBoolean finished
	) {
		/** The most required items that one seed had. Score -1 is no seed yet. */
		record Best(int score, long seed) {
		}

		static SearchStats of(Set<Holder<Biome>> biomes, Set<ResourceKey<StructureSet>> structures) {
			Map<Holder<Biome>, AtomicInteger> biomeHits = new ConcurrentHashMap<>();
			biomes.forEach(biome -> biomeHits.put(biome, new AtomicInteger()));
			Map<ResourceKey<StructureSet>, AtomicInteger> structureHits = new ConcurrentHashMap<>();
			structures.forEach(key -> structureHits.put(key, new AtomicInteger()));
			return new SearchStats(biomes.size() + structures.size(), biomeHits, structureHits, new AtomicInteger(), new AtomicInteger(),
				new AtomicReference<>(new Best(-1, 0L)), new AtomicBoolean());
		}

		void offer(int score, long seed) {
			this.best.accumulateAndGet(new Best(score, seed), (old, now) -> now.score() > old.score() ? now : old);
		}

		double biomeRate(Holder<Biome> biome) {
			return rate(this.biomeHits.get(biome), this.tried.get());
		}

		double structureRate(ResourceKey<StructureSet> key) {
			return rate(this.structureHits.get(key), this.structuresChecked.get());
		}

		private static double rate(@Nullable AtomicInteger hits, int seeds) {
			return hits == null || seeds < MIN_RATE_SAMPLES ? -1.0 : (double) hits.get() / seeds;
		}
	}

	/** A set of required biomes and structures, by their ids in the minecraft namespace. */
	record Preset(Component name, Component description, Set<Identifier> biomes, Set<Identifier> structures) {
		static Preset of(String key, List<String> biomes, List<String> structures) {
			return new Preset(
				Component.translatable("cubeworld.customize.preset." + key),
				Component.translatable("cubeworld.customize.preset." + key + ".description"),
				ids(biomes), ids(structures)
			);
		}

		private static Set<Identifier> ids(List<String> paths) {
			return Set.copyOf(paths.stream().map(Identifier::withDefaultNamespace).toList());
		}
	}
}

package dev.cubeworld.worldgen;

import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.mixin.PoolElementStructurePieceAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Lets a jigsaw structure (a village, an outpost, an ancient city) or a mineshaft cross an edge of the cube. The structure is laid
 * out on the flat plane of its face. The part past the edge goes to the neighbor face as a copy of the start, folded
 * over the edge (see {@link SeamFold}). The copy is a start in a chunk of the neighbor face, so the references, the
 * terrain adaptation and the placement of vanilla find it like any other start, and it is saved with that chunk.
 */
public final class FoldedStarts {
	/** How far a structure reaches from the chunk where it starts: eight chunks, the range of the references. */
	private static final int REACH = 8 * 16;
	private static final Set<StructurePieceType> MINESHAFT_PIECES = Set.of(
		StructurePieceType.MINE_SHAFT_CORRIDOR, StructurePieceType.MINE_SHAFT_CROSSING, StructurePieceType.MINE_SHAFT_ROOM, StructurePieceType.MINE_SHAFT_STAIRS
	);
	private static final Map<ChunkGeneratorStructureState, Map<Long, List<StructureStart>>> STARTS = Collections.synchronizedMap(new WeakHashMap<>());

	private FoldedStarts() {
	}

	/** Adds to a chunk the folded copies of the starts of other faces whose folded part is centered in that chunk. */
	public static void addTo(
		ChunkGenerator generator, RegistryAccess registries, ChunkGeneratorStructureState state, StructureManager manager,
		ChunkAccess host, StructureTemplateManager templates, ResourceKey<Level> level
	) {
		SeamLayout layout = SeamLayout.active();
		ChunkPos hostPos = host.getPos();
		if (layout == null || !layout.inRegion(hostPos.getMiddleBlockX(), hostPos.getMiddleBlockZ())) {
			return;
		}
		int face = layout.faceIndexAt(hostPos.getMiddleBlockX());
		BlockPos hostMiddle = new BlockPos(hostPos.getMiddleBlockX(), 0, hostPos.getMiddleBlockZ());
		for (int from = 0; from < SeamLayout.FACES.length; from++) {
			for (int edge = 0; edge < SeamFold.EDGES; edge++) {
				SeamFold.Fold fold = layout.fold.get(from, edge);
				if (fold.to != face) {
					continue;
				}
				BoundingBox strip = fold.strip(host.getMinY(), host.getMaxY());
				if (!strip.isInside(hostMiddle)) {
					continue;
				}
				BlockPos flat = fold.inverse(hostMiddle);
				addFrom(generator, registries, state, manager, host, templates, level, layout, fold, strip, flat);
			}
		}
	}

	private static void addFrom(
		ChunkGenerator generator, RegistryAccess registries, ChunkGeneratorStructureState state, StructureManager manager,
		ChunkAccess host, StructureTemplateManager templates, ResourceKey<Level> level, SeamLayout layout,
		SeamFold.Fold fold, BoundingBox strip, BlockPos flat
	) {
		int r = layout.radius;
		int offset = layout.offsetX(fold.from);
		// An underground structure folds about a hinge at its depth (see SeamFold.Fold#atDepth), so the flat place of this chunk
		// moves along the edge normal by twice the depth. The window spans all depths down to the bottom of the cube.
		int deepest = Math.min(r, CubePlanetMath.TOP_SURFACE_Y - host.getMinY());
		BlockPos hostMiddle = new BlockPos(host.getPos().getMiddleBlockX(), 0, host.getPos().getMiddleBlockZ());
		BlockPos deepFlat = fold.atDepth(deepest).inverse(hostMiddle);
		int minChunkX = Math.floorDiv(Math.min(flat.getX(), deepFlat.getX()) - REACH, 16);
		int maxChunkX = Math.floorDiv(Math.max(flat.getX(), deepFlat.getX()) + REACH, 16);
		int minChunkZ = Math.floorDiv(Math.min(flat.getZ(), deepFlat.getZ()) - REACH, 16);
		int maxChunkZ = Math.floorDiv(Math.max(flat.getZ(), deepFlat.getZ()) + REACH, 16);
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				// A jigsaw structure starts at the corner of its chunk with the lowest x and z, and that position
				// lies in the square of its face. The corner can be on the edge row while the chunk reaches past it.
				int cornerX = chunkX * 16 - offset;
				int cornerZ = chunkZ * 16;
				if (cornerX < -r || cornerX >= r || cornerZ < -r || cornerZ >= r) {
					continue;
				}
				for (StructureStart source : startsOf(generator, registries, state, templates, level, host, chunkX, chunkZ)) {
					SeamFold.Fold hinged = fold.atDepth(layout.hingeDepth(source.getPieces().getFirst().getBoundingBox().getCenter().getY()));
					StructureStart copy = copy(source, hinged, strip, host.getPos(), registries, templates);
					if (copy != null && manager.getStartForStructure(source.getStructure(), host) == null) {
						manager.setStartForStructure(source.getStructure(), copy, host);
					}
				}
			}
		}
	}

	/** The copy of a start folded over an edge, or null when no piece reaches the strip or the copy is centered in another chunk. */
	private static StructureStart copy(
		StructureStart source, SeamFold.Fold fold, BoundingBox strip, ChunkPos host, RegistryAccess registries, StructureTemplateManager templates
	) {
		List<StructurePiece> pieces = new ArrayList<>();
		BoundingBox visible = null;
		for (StructurePiece piece : source.getPieces()) {
			BoundingBox folded = fold.apply(piece.getBoundingBox());
			if (!folded.intersects(strip)) {
				continue;
			}
			StructurePiece foldedPiece;
			if (piece instanceof PoolElementStructurePiece pool) {
				foldedPiece = copyOf(pool, fold, templates);
			} else if (MINESHAFT_PIECES.contains(piece.getType())) {
				foldedPiece = mineshaftCopyOf(piece, folded, fold, registries, templates);
			} else {
				return null;
			}
			BoundingBox inside = intersection(folded, strip);
			visible = visible == null ? inside : BoundingBox.encapsulating(visible, inside);
			pieces.add(foldedPiece);
		}
		if (visible == null) {
			return null;
		}
		BlockPos center = visible.getCenter();
		if (ChunkPos.containing(center).x() != host.x() || ChunkPos.containing(center).z() != host.z()) {
			return null;
		}
		StructureStart copy = new StructureStart(source.getStructure(), host, 0, new PiecesContainer(pieces));
		((StartClip) (Object) copy).cubeworld$setClip(strip);
		return copy;
	}

	private static PoolElementStructurePiece copyOf(PoolElementStructurePiece piece, SeamFold.Fold fold, StructureTemplateManager templates) {
		PoolElementStructurePieceAccessor access = (PoolElementStructurePieceAccessor) piece;
		BlockPos position = fold.apply(piece.getPosition());
		var rotation = piece.getRotation().getRotated(fold.rotation());
		// The box of the source piece, not one from the element: the layout moves a piece in Y after it makes the box.
		BoundingBox box = fold.apply(piece.getBoundingBox());
		PoolElementStructurePiece copy = new PoolElementStructurePiece(
			templates, piece.getElement(), position, piece.getGroundLevelDelta(), rotation, box, access.cubeworld$liquidSettings()
		);
		for (JigsawJunction junction : piece.getJunctions()) {
			BlockPos source = fold.apply(new BlockPos(junction.getSourceX(), 0, junction.getSourceZ()));
			copy.addJunction(new JigsawJunction(source.getX(), junction.getSourceGroundY(), source.getZ(), junction.getDeltaY(), junction.getDestProjection()));
		}
		return copy;
	}

	/**
	 * A mineshaft piece keeps its place and shape in its save tag: the box, the facing, and for a crossing its direction
	 * and for a room the boxes of its entrances. The copy is the piece loaded from that tag with all of them folded.
	 */
	private static StructurePiece mineshaftCopyOf(
		StructurePiece piece, BoundingBox folded, SeamFold.Fold fold, RegistryAccess registries, StructureTemplateManager templates
	) {
		// Mineshaft pieces do not read from the resource manager.
		StructurePieceSerializationContext context = new StructurePieceSerializationContext(null, registries, templates);
		CompoundTag tag = piece.createTag(context);
		tag.store("BB", BoundingBox.CODEC, folded);
		int facing = tag.getIntOr("O", -1);
		if (facing != -1) {
			tag.putInt("O", fold.rotation().rotate(Direction.from2DDataValue(facing)).get2DDataValue());
		}
		tag.read("D", Direction.LEGACY_ID_CODEC_2D).ifPresent(direction -> tag.store("D", Direction.LEGACY_ID_CODEC_2D, fold.rotation().rotate(direction)));
		tag.read("Entrances", BoundingBox.CODEC.listOf()).ifPresent(entrances ->
			tag.store("Entrances", BoundingBox.CODEC.listOf(), entrances.stream().map(fold::apply).toList()));
		return piece.getType().load(context, tag);
	}

	private static BoundingBox intersection(BoundingBox a, BoundingBox b) {
		return new BoundingBox(
			Math.max(a.minX(), b.minX()), Math.max(a.minY(), b.minY()), Math.max(a.minZ(), b.minZ()),
			Math.min(a.maxX(), b.maxX()), Math.min(a.maxY(), b.maxY()), Math.min(a.maxZ(), b.maxZ())
		);
	}

	/**
	 * The jigsaw starts that vanilla makes for a chunk, as {@link ChunkGenerator#createStructures} chooses them. A start
	 * depends on the seed and the position only, so the result is the start that the chunk makes when it generates.
	 * Saved starts are kept for the folds of the neighbors of the chunk.
	 */
	private static List<StructureStart> startsOf(
		ChunkGenerator generator, RegistryAccess registries, ChunkGeneratorStructureState state, StructureTemplateManager templates,
		ResourceKey<Level> level, LevelHeightAccessor heights, int chunkX, int chunkZ
	) {
		Map<Long, List<StructureStart>> cache = STARTS.computeIfAbsent(state, key -> new ConcurrentHashMap<>());
		return cache.computeIfAbsent(ChunkPos.pack(chunkX, chunkZ), key -> {
			List<StructureStart> starts = new ArrayList<>();
			Climate.Sampler climate = state.randomState().createClimateSampler(SamplerContext.builder().enableCaches().build());
			for (Holder<StructureSet> set : state.possibleStructureSets()) {
				StructureStart start = startOf(set, generator, registries, state, templates, level, heights, climate, chunkX, chunkZ);
				if (start != null && (start.getStructure().type() == StructureType.JIGSAW || start.getStructure().type() == StructureType.MINESHAFT)) {
					starts.add(start);
				}
			}
			return starts;
		});
	}

	/**
	 * The valid start that a structure set makes in a chunk, chosen as {@link ChunkGenerator#createStructures} chooses it:
	 * the options are tried by weight until one generates. Returns null when the chunk is no placement chunk of the set or
	 * no option generates.
	 */
	public static @Nullable StructureStart startOf(
		Holder<StructureSet> set, ChunkGenerator generator, RegistryAccess registries, ChunkGeneratorStructureState state,
		StructureTemplateManager templates, ResourceKey<Level> level, LevelHeightAccessor heights, Climate.Sampler climate, int chunkX, int chunkZ
	) {
		if (!set.value().placement().isStructureChunk(state, chunkX, chunkZ)) {
			return null;
		}
		List<StructureSet.StructureSelectionEntry> options = new ArrayList<>(set.value().structures());
		WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
		random.setLargeFeatureSeed(state.getLevelSeed(), chunkX, chunkZ);
		int total = 0;
		for (StructureSet.StructureSelectionEntry option : options) {
			total += option.weight();
		}
		while (!options.isEmpty()) {
			int choice = options.size() == 1 ? 0 : random.nextInt(total);
			int index = 0;
			if (options.size() > 1) {
				for (StructureSet.StructureSelectionEntry option : options) {
					choice -= option.weight();
					if (choice < 0) {
						break;
					}
					index++;
				}
			}
			StructureSet.StructureSelectionEntry selected = options.get(index);
			Structure structure = selected.structure().value();
			Predicate<Holder<Biome>> validBiome = structure.biomes()::contains;
			StructureStart start = structure.generate(
				selected.structure(), level, registries, generator, generator.getBiomeSource(), climate, state.randomState(),
				templates, state.getLevelSeed(), new ChunkPos(chunkX, chunkZ), 0, heights, validBiome
			);
			if (start.isValid()) {
				return start;
			}
			options.remove(index);
			total -= selected.weight();
		}
		return null;
	}
}

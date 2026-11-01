package dev.cubeworld.worldgen;

import dev.cubeworld.mixin.PoolElementStructurePieceAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
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
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Lets a jigsaw structure (a village, an outpost, an ancient city) cross an edge of the cube. The structure is laid
 * out on the flat plane of its face. The part past the edge goes to the neighbor face as a copy of the start, folded
 * over the edge (see {@link SeamFold}). The copy is a start in a chunk of the neighbor face, so the references, the
 * terrain adaptation and the placement of vanilla find it like any other start, and it is saved with that chunk.
 */
public final class FoldedStarts {
	/** How far a structure reaches from the chunk where it starts: eight chunks, the range of the references. */
	private static final int REACH = 8 * 16;
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
		int minChunkX = Math.floorDiv(flat.getX() - REACH, 16);
		int maxChunkX = Math.floorDiv(flat.getX() + REACH, 16);
		int minChunkZ = Math.floorDiv(flat.getZ() - REACH, 16);
		int maxChunkZ = Math.floorDiv(flat.getZ() + REACH, 16);
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				// A jigsaw structure starts at the corner of its chunk with the lowest x and z, and that position
				// lies in the square of its face. The corner can be on the edge row while the chunk reaches past it.
				if (Math.abs(chunkX * 16 - offset) > r || Math.abs(chunkZ * 16) > r) {
					continue;
				}
				for (StructureStart source : startsOf(generator, registries, state, templates, level, host, chunkX, chunkZ)) {
					StructureStart copy = copy(source, fold, strip, host.getPos(), templates);
					if (copy != null && manager.getStartForStructure(source.getStructure(), host) == null) {
						manager.setStartForStructure(source.getStructure(), copy, host);
					}
				}
			}
		}
	}

	/** The copy of a start folded over an edge, or null when no piece reaches the strip or the copy is centered in another chunk. */
	private static StructureStart copy(StructureStart source, SeamFold.Fold fold, BoundingBox strip, ChunkPos host, StructureTemplateManager templates) {
		List<StructurePiece> pieces = new ArrayList<>();
		BoundingBox visible = null;
		for (StructurePiece piece : source.getPieces()) {
			if (!(piece instanceof PoolElementStructurePiece pool)) {
				return null;
			}
			BoundingBox folded = fold.apply(piece.getBoundingBox());
			if (!folded.intersects(strip)) {
				continue;
			}
			BoundingBox inside = intersection(folded, strip);
			visible = visible == null ? inside : BoundingBox.encapsulating(visible, inside);
			pieces.add(copyOf(pool, fold, templates));
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
				if (!set.value().placement().isStructureChunk(state, chunkX, chunkZ)) {
					continue;
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
						if (structure.type() == StructureType.JIGSAW) {
							starts.add(start);
						}
						break;
					}
					options.remove(index);
					total -= selected.weight();
				}
			}
			return starts;
		});
	}
}

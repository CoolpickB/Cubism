package dev.cubeworld.worldgen;

import dev.cubeworld.math.CubePlanetMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;

import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Shows one cube face to vanilla features as a vanilla overworld. The virtual frame is the entity frame of the
 * face (see {@link CubePlanetMath#localToWorld}), rotated about the cube center, so virtual Y is the distance
 * along the face normal plus {@code centerY} and the face surface is at virtual Y 64 on every face.
 *
 * <p>Reads and writes reach only the 3x3 chunks that vanilla permits around the chunk being decorated. Other
 * reads give air. Heightmaps come from the terrain density ({@link CubeChunkGenerator#faceHeight}), because a
 * column on a side face goes through many chunks.
 */
public final class OrientedWorldGenLevel implements WorldGenLevel {
	public static final int VIRTUAL_MIN_Y = -64;
	public static final int VIRTUAL_HEIGHT = 384;

	private final WorldGenLevel delegate;
	private final CubeChunkGenerator generator;
	private final Direction face;
	private final int centerY;
	private final ChunkPos centerChunk;
	private final BiomeResolver biomes;

	public OrientedWorldGenLevel(WorldGenLevel delegate, CubeChunkGenerator generator, Direction face, ChunkPos centerChunk) {
		this.delegate = delegate;
		this.generator = generator;
		this.face = face;
		this.centerY = generator.centerY();
		this.centerChunk = centerChunk;
		this.biomes = generator.createBiomeResolver();
	}

	public Direction face() {
		return this.face;
	}

	public static BlockPos toWorld(Direction face, int centerY, int vx, int vy, int vz) {
		int x = vx;
		int y = vy - centerY;
		int z = vz;
		return switch (face) {
			case UP -> new BlockPos(x, y + centerY, z);
			case DOWN -> new BlockPos(-x, -y + centerY, z);
			case SOUTH -> new BlockPos(x, -z + centerY, y);
			case NORTH -> new BlockPos(x, z + centerY, -y);
			case EAST -> new BlockPos(y, -x + centerY, z);
			case WEST -> new BlockPos(-y, x + centerY, z);
		};
	}

	public static BlockPos toVirtual(Direction face, int centerY, int wx, int wy, int wz) {
		int x = wx;
		int y = wy - centerY;
		int z = wz;
		return switch (face) {
			case UP -> new BlockPos(x, y + centerY, z);
			case DOWN -> new BlockPos(-x, -y + centerY, z);
			case SOUTH -> new BlockPos(x, z + centerY, -y);
			case NORTH -> new BlockPos(x, -z + centerY, y);
			case EAST -> new BlockPos(-y, x + centerY, z);
			case WEST -> new BlockPos(y, -x + centerY, z);
		};
	}

	public BlockPos toWorld(BlockPos vPos) {
		return toWorld(this.face, this.centerY, vPos.getX(), vPos.getY(), vPos.getZ());
	}

	public BlockPos toVirtual(BlockPos wPos) {
		return toVirtual(this.face, this.centerY, wPos.getX(), wPos.getY(), wPos.getZ());
	}

	/** True when vanilla permits this region to read and write the world position. */
	public boolean isReachable(BlockPos wPos) {
		return Math.abs((wPos.getX() >> 4) - this.centerChunk.x()) <= 1
			&& Math.abs((wPos.getZ() >> 4) - this.centerChunk.z()) <= 1
			&& !this.delegate.isOutsideBuildHeight(wPos.getY());
	}

	/** {@link net.minecraft.world.level.levelgen.feature.Feature#markAboveForPostProcessing} in the virtual frame. */
	public void markAboveForPostProcessing(BlockPos vPos) {
		BlockPos.MutableBlockPos pos = vPos.mutable();
		for (int i = 0; i < 2; i++) {
			pos.move(Direction.UP);
			if (this.getBlockState(pos).isAir()) {
				return;
			}
			this.markPosForPostProcessing(pos);
		}
	}

	public void markPosForPostProcessing(BlockPos vPos) {
		BlockPos wPos = toWorld(vPos);
		if (isReachable(wPos)) {
			this.delegate.getChunk(wPos).markPosForPostProcessing(wPos);
		}
	}

	@Override
	public long getSeed() {
		return this.delegate.getSeed();
	}

	@Override
	public boolean ensureCanWrite(BlockPos vPos) {
		BlockPos wPos = toWorld(vPos);
		return isReachable(wPos) && this.delegate.ensureCanWrite(wPos);
	}

	@Override
	public void setCurrentlyGenerating(Supplier<String> supplier) {
		this.delegate.setCurrentlyGenerating(supplier);
	}

	@Override
	public ServerLevel getLevel() {
		return this.delegate.getLevel();
	}

	@Override
	public DifficultyInstance getCurrentDifficultyAt(BlockPos vPos) {
		BlockPos wPos = toWorld(vPos);
		return this.delegate.getCurrentDifficultyAt(isReachable(wPos) ? wPos : this.centerChunk.getWorldPosition());
	}

	@Override
	public long nextSubTickCount() {
		return this.delegate.nextSubTickCount();
	}

	@Override
	public <T> ScheduledTick<T> createTick(BlockPos vPos, T type, int delay, TickPriority priority) {
		return this.delegate.createTick(toWorld(vPos), type, delay, priority);
	}

	@Override
	public <T> ScheduledTick<T> createTick(BlockPos vPos, T type, int delay) {
		return this.delegate.createTick(toWorld(vPos), type, delay);
	}

	@Override
	public LevelData getLevelData() {
		return this.delegate.getLevelData();
	}

	@Override
	public MinecraftServer getServer() {
		return this.delegate.getServer();
	}

	@Override
	public ChunkSource getChunkSource() {
		return this.delegate.getChunkSource();
	}

	@Override
	public RandomSource getRandom() {
		return this.delegate.getRandom();
	}

	@Override
	public void playSound(Entity entity, BlockPos vPos, SoundEvent sound, SoundSource source, float volume, float pitch) {
	}

	@Override
	public void addParticle(ParticleOptions options, double x, double y, double z, double dx, double dy, double dz) {
	}

	@Override
	public void levelEvent(Entity entity, int type, BlockPos vPos, int data) {
	}

	@Override
	public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {
	}

	// Ticks arrive here from createTick, which already converted the position to the world frame.
	@Override
	public LevelTickAccess<Block> getBlockTicks() {
		return new ReachableTicks<>(this.delegate.getBlockTicks());
	}

	@Override
	public LevelTickAccess<Fluid> getFluidTicks() {
		return new ReachableTicks<>(this.delegate.getFluidTicks());
	}

	private final class ReachableTicks<T> implements LevelTickAccess<T> {
		private final LevelTickAccess<T> ticks;

		private ReachableTicks(LevelTickAccess<T> ticks) {
			this.ticks = ticks;
		}

		@Override
		public boolean willTickThisTick(BlockPos pos, T type) {
			return false;
		}

		@Override
		public boolean hasScheduledTick(BlockPos pos, T type) {
			return false;
		}

		@Override
		public void schedule(ScheduledTick<T> tick) {
			if (isReachable(tick.pos())) {
				this.ticks.schedule(tick);
			}
		}

		@Override
		public int count() {
			return 0;
		}
	}

	@Override
	public ChunkAccess getChunk(BlockPos vPos) {
		BlockPos wPos = toWorld(vPos);
		return isReachable(wPos) ? this.delegate.getChunk(wPos) : this.delegate.getChunk(this.centerChunk.x(), this.centerChunk.z());
	}

	// Chunk coordinates have no single world chunk in a turned frame. The region refuses a FULL request for the
	// center chunk while it generates, so ask for any status.
	@Override
	public ChunkAccess getChunk(int chunkX, int chunkZ) {
		return this.delegate.getChunk(this.centerChunk.x(), this.centerChunk.z());
	}

	@Override
	public ChunkAccess getChunk(int chunkX, int chunkZ, ChunkStatus status, boolean create) {
		return this.delegate.getChunk(this.centerChunk.x(), this.centerChunk.z(), ChunkStatus.EMPTY, create);
	}

	@Override
	public boolean hasChunk(int chunkX, int chunkZ) {
		return true;
	}

	@Override
	public int getHeight(Heightmap.Types type, int vx, int vz) {
		boolean oceanFloor = type == Heightmap.Types.OCEAN_FLOOR || type == Heightmap.Types.OCEAN_FLOOR_WG;
		int terrainHeight = this.generator.faceHeight(this.face, vx, vz, oceanFloor);
		// Blocks from earlier features (trees, lakes) change the height. Use them where the region can read them.
		Predicate<BlockState> opaque = type.isOpaque();
		BlockPos.MutableBlockPos vPos = new BlockPos.MutableBlockPos();
		for (int vy = terrainHeight + 40; vy >= terrainHeight - 16; vy--) {
			BlockPos wPos = toWorld(this.face, this.centerY, vx, vy, vz);
			if (CubePlanetMath.getPyramidOutwardFace(wPos.getX(), wPos.getY(), wPos.getZ(), this.centerY) != this.face) {
				continue;
			}
			if (!isReachable(wPos)) {
				return terrainHeight;
			}
			if (opaque.test(this.delegate.getBlockState(wPos))) {
				return vy + 1;
			}
		}
		return terrainHeight;
	}

	@Override
	public int getBrightness(LightLayer layer, BlockPos vPos) {
		return layer == LightLayer.SKY && this.canSeeSky(vPos) ? 15 : 0;
	}

	@Override
	public int getRawBrightness(BlockPos vPos, int darkening) {
		return Math.max(this.getBrightness(LightLayer.SKY, vPos) - darkening, 0);
	}

	@Override
	public boolean canSeeSky(BlockPos vPos) {
		return vPos.getY() >= this.getHeight(Heightmap.Types.WORLD_SURFACE, vPos.getX(), vPos.getZ());
	}

	@Override
	public int getSkyDarken() {
		return this.delegate.getSkyDarken();
	}

	@Override
	public BiomeManager getBiomeManager() {
		return this.delegate.getBiomeManager();
	}

	@Override
	public Holder<Biome> getBiome(BlockPos vPos) {
		BlockPos wPos = toWorld(vPos);
		return this.biomes.getNoiseBiome(QuartPos.fromBlock(wPos.getX()), QuartPos.fromBlock(wPos.getY()), QuartPos.fromBlock(wPos.getZ()));
	}

	@Override
	public Holder<Biome> getUncachedNoiseBiome(int qx, int qy, int qz) {
		BlockPos wPos = toWorld(new BlockPos((qx << 2) + 2, (qy << 2) + 2, (qz << 2) + 2));
		return this.biomes.getNoiseBiome(QuartPos.fromBlock(wPos.getX()), QuartPos.fromBlock(wPos.getY()), QuartPos.fromBlock(wPos.getZ()));
	}

	@Override
	public boolean isClientSide() {
		return false;
	}

	@Override
	public int getSeaLevel() {
		return this.generator.getSeaLevel();
	}

	@Override
	public DimensionType dimensionType() {
		return this.delegate.dimensionType();
	}

	// Vanilla height ranges (ores, lakes, geodes) count from the overworld floor, so the face uses its build range.
	@Override
	public int getMinY() {
		return VIRTUAL_MIN_Y;
	}

	@Override
	public int getHeight() {
		return VIRTUAL_HEIGHT;
	}

	@Override
	public RegistryAccess registryAccess() {
		return this.delegate.registryAccess();
	}

	@Override
	public FeatureFlagSet enabledFeatures() {
		return this.delegate.enabledFeatures();
	}

	@Override
	public EnvironmentAttributeReader environmentAttributes() {
		return this.delegate.environmentAttributes();
	}

	@Override
	public LevelLightEngine getLightEngine() {
		return this.delegate.getLightEngine();
	}

	@Override
	public WorldBorder getWorldBorder() {
		return this.delegate.getWorldBorder();
	}

	@Override
	public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) {
		return this;
	}

	@Override
	public List<net.minecraft.world.phys.shapes.VoxelShape> getEntityCollisions(Entity entity, AABB box) {
		return Collections.emptyList();
	}

	@Override
	public BlockEntity getBlockEntity(BlockPos vPos) {
		BlockPos wPos = toWorld(vPos);
		return isReachable(wPos) ? this.delegate.getBlockEntity(wPos) : null;
	}

	@Override
	public BlockState getBlockState(BlockPos vPos) {
		BlockPos wPos = toWorld(vPos);
		return isReachable(wPos) ? this.delegate.getBlockState(wPos) : Blocks.AIR.defaultBlockState();
	}

	@Override
	public FluidState getFluidState(BlockPos vPos) {
		BlockPos wPos = toWorld(vPos);
		return isReachable(wPos) ? this.delegate.getFluidState(wPos) : Fluids.EMPTY.defaultFluidState();
	}

	@Override
	public boolean isStateAtPosition(BlockPos vPos, Predicate<BlockState> predicate) {
		return predicate.test(getBlockState(vPos));
	}

	@Override
	public boolean isFluidAtPosition(BlockPos vPos, Predicate<FluidState> predicate) {
		return predicate.test(getFluidState(vPos));
	}

	// Block states keep their local orientation (GOALS.md, core model item 4).
	@Override
	public boolean setBlock(BlockPos vPos, BlockState state, int flags, int recursionLeft) {
		BlockPos wPos = toWorld(vPos);
		return isReachable(wPos) && this.delegate.setBlock(wPos, state, flags, recursionLeft);
	}

	@Override
	public boolean removeBlock(BlockPos vPos, boolean isMoving) {
		BlockPos wPos = toWorld(vPos);
		return isReachable(wPos) && this.delegate.removeBlock(wPos, isMoving);
	}

	@Override
	public boolean destroyBlock(BlockPos vPos, boolean dropBlock, Entity entity, int recursionLeft) {
		BlockPos wPos = toWorld(vPos);
		return isReachable(wPos) && this.delegate.destroyBlock(wPos, dropBlock, entity, recursionLeft);
	}

	@Override
	public boolean addFreshEntity(Entity entity) {
		return false;
	}

	@Override
	public List<Entity> getEntities(Entity entity, AABB box, Predicate<? super Entity> predicate) {
		return Collections.emptyList();
	}

	@Override
	public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> typeTest, AABB box, Predicate<? super T> predicate) {
		return Collections.emptyList();
	}

	@Override
	public List<? extends Player> players() {
		return Collections.emptyList();
	}

	@Override
	public Player getNearestPlayer(double x, double y, double z, double maxDist, Predicate<Entity> predicate) {
		return null;
	}
}

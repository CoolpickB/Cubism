package dev.cubeworld.worldgen;

import dev.cubeworld.math.CubePlanetMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
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
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
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
 * Wraps a {@link WorldGenLevel} so vanilla {@link net.minecraft.world.level.levelgen.feature.Feature}
 * implementations (trees, ores, flowers, grass patches, freeze layers, springs, disks) execute
 * in a virtual +Y-up coordinate system anchored on any of the 6 cube faces.
 */
public final class OrientedWorldGenLevel implements WorldGenLevel {
	public static final int VIRTUAL_SEA_LEVEL = CubePlanetMath.TOP_SURFACE_Y;

	private final WorldGenLevel delegate;
	private final ChunkPos centerChunkPos;
	private final Direction outward;
	private final Direction gravity;
	private final int centerY;
	private final int anchorVx;
	private final int anchorVy;
	private final int anchorVz;
	private final int anchorWx;
	private final int anchorWy;
	private final int anchorWz;
	private final int minSafeX;
	private final int maxSafeX;
	private final int minSafeY;
	private final int maxSafeY;
	private final int minSafeZ;
	private final int maxSafeZ;

	public OrientedWorldGenLevel(
		WorldGenLevel delegate,
		ChunkPos centerChunkPos,
		Direction outward,
		int centerY,
		BlockPos worldSurfaceSeaAnchor
	) {
		this.delegate = delegate;
		this.centerChunkPos = centerChunkPos;
		this.outward = outward;
		this.gravity = outward.getOpposite();
		this.centerY = centerY;
		this.anchorWx = worldSurfaceSeaAnchor.getX();
		this.anchorWy = worldSurfaceSeaAnchor.getY();
		this.anchorWz = worldSurfaceSeaAnchor.getZ();
		this.anchorVx = 0;
		this.anchorVy = VIRTUAL_SEA_LEVEL;
		this.anchorVz = 0;

		int centerMinX = centerChunkPos.getMinBlockX();
		int centerMinZ = centerChunkPos.getMinBlockZ();
		this.minSafeX = centerMinX - 15;
		this.maxSafeX = centerMinX + 30;
		this.minSafeZ = centerMinZ - 15;
		this.maxSafeZ = centerMinZ + 30;
		this.minSafeY = delegate.getMinY() + 1;
		this.maxSafeY = delegate.getMinY() + delegate.getHeight() - 2;
	}

	public BlockPos toWorld(BlockPos vPos) {
		if (this.outward == Direction.UP) {
			return vPos;
		}
		int dvx = vPos.getX() - this.anchorVx;
		int dvy = vPos.getY() - this.anchorVy;
		int dvz = vPos.getZ() - this.anchorVz;
		int wx;
		int wy;
		int wz;
		switch (this.gravity) {
			case UP -> {
				wx = this.anchorWx - dvx;
				wy = this.anchorWy - dvy;
				wz = this.anchorWz + dvz;
			}
			case NORTH -> {
				wx = this.anchorWx + dvx;
				wy = this.anchorWy - dvz;
				wz = this.anchorWz + dvy;
			}
			case SOUTH -> {
				wx = this.anchorWx + dvx;
				wy = this.anchorWy + dvz;
				wz = this.anchorWz - dvy;
			}
			case WEST -> {
				wx = this.anchorWx + dvy;
				wy = this.anchorWy - dvx;
				wz = this.anchorWz + dvz;
			}
			case EAST -> {
				wx = this.anchorWx - dvy;
				wy = this.anchorWy + dvx;
				wz = this.anchorWz + dvz;
			}
			default -> {
				wx = this.anchorWx + dvx;
				wy = this.anchorWy + dvy;
				wz = this.anchorWz + dvz;
			}
		}
		return new BlockPos(wx, wy, wz);
	}

	public BlockPos toVirtual(BlockPos wPos) {
		if (this.outward == Direction.UP) {
			return wPos;
		}
		int dwx = wPos.getX() - this.anchorWx;
		int dwy = wPos.getY() - this.anchorWy;
		int dwz = wPos.getZ() - this.anchorWz;
		int dvx;
		int dvy;
		int dvz;
		switch (this.gravity) {
			case UP -> {
				dvx = -dwx;
				dvy = -dwy;
				dvz = dwz;
			}
			case NORTH -> {
				dvx = dwx;
				dvy = dwz;
				dvz = -dwy;
			}
			case SOUTH -> {
				dvx = dwx;
				dvy = -dwz;
				dvz = dwy;
			}
			case WEST -> {
				dvx = -dwy;
				dvy = dwx;
				dvz = dwz;
			}
			case EAST -> {
				dvx = dwy;
				dvy = -dwx;
				dvz = dwz;
			}
			default -> {
				dvx = dwx;
				dvy = dwy;
				dvz = dwz;
			}
		}
		return new BlockPos(this.anchorVx + dvx, this.anchorVy + dvy, this.anchorVz + dvz);
	}

	public boolean isWithinSafeBounds(BlockPos wPos) {
		int x = wPos.getX();
		int y = wPos.getY();
		int z = wPos.getZ();
		return x >= this.minSafeX && x <= this.maxSafeX
			&& z >= this.minSafeZ && z <= this.maxSafeZ
			&& y >= this.minSafeY && y <= this.maxSafeY;
	}

	private boolean belongsToThisFaceSector(BlockPos wPos) {
		return CubePlanetMath.getPyramidOutwardFace(wPos.getX(), wPos.getY(), wPos.getZ(), this.centerY) == this.outward;
	}

	@Override
	public long getSeed() {
		return this.delegate.getSeed();
	}

	@Override
	public boolean ensureCanWrite(BlockPos vPos) {
		BlockPos wPos = toWorld(vPos);
		return isWithinSafeBounds(wPos) && belongsToThisFaceSector(wPos) && this.delegate.ensureCanWrite(wPos);
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
		if (!isWithinSafeBounds(wPos)) {
			return this.delegate.getCurrentDifficultyAt(this.centerChunkPos.getWorldPosition());
		}
		return this.delegate.getCurrentDifficultyAt(wPos);
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
		BlockPos wPos = toWorld(vPos);
		if (isWithinSafeBounds(wPos)) {
			this.delegate.playSound(entity, wPos, sound, source, volume, pitch);
		}
	}

	@Override
	public void addParticle(ParticleOptions options, double x, double y, double z, double dx, double dy, double dz) {
		this.delegate.addParticle(options, x, y, z, dx, dy, dz);
	}

	@Override
	public void levelEvent(Entity entity, int type, BlockPos vPos, int data) {
		BlockPos wPos = toWorld(vPos);
		if (isWithinSafeBounds(wPos)) {
			this.delegate.levelEvent(entity, type, wPos, data);
		}
	}

	@Override
	public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {
		this.delegate.gameEvent(event, pos, context);
	}

	@Override
	public LevelTickAccess<Block> getBlockTicks() {
		return new LevelTickAccess<>() {
			@Override
			public boolean willTickThisTick(BlockPos vPos, Block type) {
				return false;
			}

			@Override
			public boolean hasScheduledTick(BlockPos vPos, Block type) {
				return false;
			}

			@Override
			public void schedule(ScheduledTick<Block> tick) {
				if (isWithinSafeBounds(tick.pos())) {
					OrientedWorldGenLevel.this.delegate.getBlockTicks().schedule(tick);
				}
			}

			@Override
			public int count() {
				return 0;
			}
		};
	}

	@Override
	public LevelTickAccess<Fluid> getFluidTicks() {
		return new LevelTickAccess<>() {
			@Override
			public boolean willTickThisTick(BlockPos vPos, Fluid type) {
				return false;
			}

			@Override
			public boolean hasScheduledTick(BlockPos vPos, Fluid type) {
				return false;
			}

			@Override
			public void schedule(ScheduledTick<Fluid> tick) {
				if (isWithinSafeBounds(tick.pos())) {
					OrientedWorldGenLevel.this.delegate.getFluidTicks().schedule(tick);
				}
			}

			@Override
			public int count() {
				return 0;
			}
		};
	}

	@Override
	public ChunkAccess getChunk(BlockPos vPos) {
		BlockPos wPos = toWorld(vPos);
		int wChunkX = wPos.getX() >> 4;
		int wChunkZ = wPos.getZ() >> 4;
		if (!isWithinSafeBounds(wPos) || !this.delegate.hasChunk(wChunkX, wChunkZ)) {
			return this.delegate.getChunk(this.centerChunkPos.getMinBlockX() >> 4, this.centerChunkPos.getMinBlockZ() >> 4);
		}
		return this.delegate.getChunk(wChunkX, wChunkZ);
	}

	@Override
	public ChunkAccess getChunk(int chunkX, int chunkZ, ChunkStatus status, boolean create) {
		BlockPos wPos = toWorld(new BlockPos(chunkX << 4, VIRTUAL_SEA_LEVEL, chunkZ << 4));
		int wChunkX = wPos.getX() >> 4;
		int wChunkZ = wPos.getZ() >> 4;
		if (!isWithinSafeBounds(wPos) || !this.delegate.hasChunk(wChunkX, wChunkZ)) {
			return this.delegate.getChunk(this.centerChunkPos.getMinBlockX() >> 4, this.centerChunkPos.getMinBlockZ() >> 4, status, create);
		}
		return this.delegate.getChunk(wChunkX, wChunkZ, status, create);
	}

	@Override
	public boolean hasChunk(int chunkX, int chunkZ) {
		BlockPos wPos = toWorld(new BlockPos(chunkX << 4, VIRTUAL_SEA_LEVEL, chunkZ << 4));
		return isWithinSafeBounds(wPos) && this.delegate.hasChunk(wPos.getX() >> 4, wPos.getZ() >> 4);
	}

	@Override
	public int getHeight(Heightmap.Types type, int vx, int vz) {
		for (int vy = VIRTUAL_SEA_LEVEL + 64; vy >= VIRTUAL_SEA_LEVEL - 48; vy--) {
			BlockState state = getBlockState(new BlockPos(vx, vy, vz));
			boolean match = switch (type) {
				case WORLD_SURFACE, WORLD_SURFACE_WG -> !state.isAir();
				case OCEAN_FLOOR, OCEAN_FLOOR_WG -> !state.isAir() && state.getFluidState().isEmpty();
				case MOTION_BLOCKING -> !state.isAir() || !state.getFluidState().isEmpty();
				case MOTION_BLOCKING_NO_LEAVES -> (!state.isAir() || !state.getFluidState().isEmpty())
					&& !(state.getBlock() instanceof LeavesBlock);
			};
			if (match) {
				return vy + 1;
			}
		}
		return VIRTUAL_SEA_LEVEL;
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
		if (!isWithinSafeBounds(wPos)) {
			return this.delegate.getBiome(this.centerChunkPos.getWorldPosition());
		}
		return this.delegate.getBiome(wPos);
	}

	@Override
	public Holder<Biome> getUncachedNoiseBiome(int qx, int qy, int qz) {
		BlockPos wPos = toWorld(new BlockPos(qx << 2, qy << 2, qz << 2));
		return this.delegate.getUncachedNoiseBiome(wPos.getX() >> 2, wPos.getY() >> 2, wPos.getZ() >> 2);
	}

	@Override
	public boolean isClientSide() {
		return this.delegate.isClientSide();
	}

	@Override
	public int getSeaLevel() {
		return VIRTUAL_SEA_LEVEL;
	}

	@Override
	public DimensionType dimensionType() {
		return this.delegate.dimensionType();
	}

	@Override
	public int getMinY() {
		return this.delegate.getMinY();
	}

	@Override
	public int getHeight() {
		return this.delegate.getHeight();
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
		if (!isWithinSafeBounds(wPos)) {
			return null;
		}
		return this.delegate.getBlockEntity(wPos);
	}

	@Override
	public BlockState getBlockState(BlockPos vPos) {
		BlockPos wPos = toWorld(vPos);
		if (!isWithinSafeBounds(wPos)) {
			return Blocks.AIR.defaultBlockState();
		}
		return this.delegate.getBlockState(wPos);
	}

	@Override
	public FluidState getFluidState(BlockPos vPos) {
		BlockPos wPos = toWorld(vPos);
		if (!isWithinSafeBounds(wPos)) {
			return Fluids.EMPTY.defaultFluidState();
		}
		return this.delegate.getFluidState(wPos);
	}

	@Override
	public boolean isStateAtPosition(BlockPos vPos, Predicate<BlockState> predicate) {
		return predicate.test(getBlockState(vPos));
	}

	@Override
	public boolean isFluidAtPosition(BlockPos vPos, Predicate<FluidState> predicate) {
		return predicate.test(getFluidState(vPos));
	}

	@Override
	public boolean setBlock(BlockPos vPos, BlockState state, int flags, int recursionLeft) {
		BlockPos wPos = toWorld(vPos);
		if (!isWithinSafeBounds(wPos) || !belongsToThisFaceSector(wPos)) {
			return false;
		}
		if (!this.delegate.ensureCanWrite(wPos)) {
			return false;
		}
		return this.delegate.setBlock(wPos, state, flags, recursionLeft);
	}

	@Override
	public boolean removeBlock(BlockPos vPos, boolean isMoving) {
		BlockPos wPos = toWorld(vPos);
		if (!isWithinSafeBounds(wPos) || !belongsToThisFaceSector(wPos)) {
			return false;
		}
		return this.delegate.removeBlock(wPos, isMoving);
	}

	@Override
	public boolean destroyBlock(BlockPos vPos, boolean dropBlock, Entity entity, int recursionLeft) {
		BlockPos wPos = toWorld(vPos);
		if (!isWithinSafeBounds(wPos) || !belongsToThisFaceSector(wPos)) {
			return false;
		}
		return this.delegate.destroyBlock(wPos, dropBlock, entity, recursionLeft);
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

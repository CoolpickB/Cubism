package dev.cubeworld.client.sodiumseam;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.cubeworld.client.SeamClient;
import dev.cubeworld.client.sodium.ViewportAccessor;
import dev.cubeworld.worldgen.SeamLayout;
import java.util.function.Consumer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.trigger.CameraMovement;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.oit.OitStage;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.util.iterator.ByteIterator;
import dev.cubeworld.client.render.SeamRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * One Sodium section manager per face region. Sodium draws the sections around one camera, and the face regions lie
 * far apart in storage. The manager of the face that holds the camera is Sodium's own. Every other manager draws
 * from the position that the camera would have in its face, with the fold that turns its face to the camera face.
 */
public final class SeamFaces {
	private static final int FACES = SeamLayout.FACES.length;
	private static final RenderSectionManager[] managers = new RenderSectionManager[FACES];
	private static final UniformBufferManager[] uniforms = new UniformBufferManager[FACES];
	private static final Vector3d[] position = new Vector3d[FACES];
	private static final int[][] motion = new int[FACES][];
	private static final boolean[] drawn = new boolean[FACES];
	// Index 1 is the Iris shadow pass, which draws with another frustum and cull matrix than the main pass.
	private static final Vector3d[][] seen = new Vector3d[2][FACES];
	private static final Matrix4f[][] lastCull = new Matrix4f[2][FACES];
	private static final float[][] lastFog = new float[2][FACES];

	private static @Nullable ClientLevel level;
	private static int renderDistance;
	private static SortBehavior sortBehavior = SortBehavior.OFF;
	private static int primary = -1;
	/** The face whose manager runs or draws right now, or -1 while Sodium's own manager does. */
	private static int working = -1;

	private SeamFaces() {}

	public static boolean active() {
		return primary >= 0;
	}

	public static int primary() {
		return primary;
	}

	public static void start(ClientLevel newLevel, int distance, SortBehavior sort, int face) {
		level = newLevel;
		renderDistance = distance;
		sortBehavior = sort;
		primary = face;
	}

	/** The face whose sections Sodium handles right now: a folded face inside {@link #drive} and {@link #draw}, else the camera face. */
	public static int currentFace() {
		return working >= 0 ? working : primary;
	}

	public static void setPrimary(int face) {
		primary = face;
	}

	public static int faceOfChunk(int chunkX) {
		SeamLayout layout = SeamClient.layout;
		return layout == null ? 0 : layout.faceIndexAt((chunkX << 4) + 8);
	}

	public static int faceOfCamera(Camera camera) {
		SeamLayout layout = SeamClient.layout;
		return layout == null ? 0 : layout.faceIndexAt((int) Math.floor(camera.position().x));
	}

	public static RenderSectionManager manager(int face) {
		if (managers[face] == null) {
			managers[face] = new RenderSectionManager(level, renderDistance, sortBehavior);
			uniforms[face] = new UniformBufferManager(level, renderDistance);
		}
		return managers[face];
	}

	public static UniformBufferManager uniforms(int face) {
		manager(face);
		return uniforms[face];
	}

	public static @Nullable RenderSectionManager existing(int face) {
		return managers[face];
	}

	/** Destroys every manager but the one that Sodium destroys itself, and forgets all of them. */
	public static void destroyAllBut(@Nullable RenderSectionManager keep) {
		for (int f = 0; f < FACES; f++) {
			if (managers[f] != null && managers[f] != keep) {
				managers[f].destroy();
				uniforms[f].delete();
			}
			managers[f] = null;
			uniforms[f] = null;
			drawn[f] = false;
			position[f] = null;
			for (int s = 0; s < 2; s++) {
				seen[s][f] = null;
				lastCull[s][f] = null;
			}
		}
		primary = -1;
		level = null;
	}

	/** Takes every manager out of the Iris shadow state. */
	public static void endShadowScope() {
		if (SeamClient.IRIS) {
			IrisLink.setShadowScope(managers, uniforms, false);
		}
	}

	public static void forEachManager(Consumer<RenderSectionManager> action) {
		for (RenderSectionManager manager : managers) {
			if (manager != null) {
				action.accept(manager);
			}
		}
	}

	public static void endFrame() {
		for (int f = 0; f < FACES; f++) {
			if (f != primary && uniforms[f] != null) {
				uniforms[f].endFrame();
			}
		}
	}

	/** The camera as it would stand in face {@code face}: the inverse of the motion that carries the face to the camera face. */
	private static Vector3d foldedCamera(int[] m, Vec3 camera) {
		double cx = camera.x - m[9], cy = camera.y - m[10], cz = camera.z - m[11];
		return new Vector3d(
			m[0] * cx + m[3] * cy + m[6] * cz,
			m[1] * cx + m[4] * cy + m[7] * cz,
			m[2] * cx + m[5] * cy + m[8] * cz);
	}

	/** Runs the frame of Sodium for every face but the primary one, from the position that the camera has there. */
	public static void drive(Camera camera, Viewport viewport, FogParameters fog, boolean occlusion, boolean immediately,
		Matrix4f cullMatrix, int maxUpdates) {
		SeamLayout layout = SeamClient.layout;
		boolean shadow = SeamClient.IRIS && IrisLink.shadowsActive();
		int s = shadow ? 1 : 0;
		if (shadow) {
			IrisLink.setShadowScope(managers, uniforms, true);
		}
		for (int f = 0; f < FACES; f++) {
			drawn[f] = false;
			RenderSectionManager manager = managers[f];
			if (layout == null || manager == null || f == primary) {
				continue;
			}
			int[] m = layout.motion(f, primary);
			if (m[4] == -1) {
				continue;
			}
			Vector3d p = foldedCamera(m, camera.position());
			double reach = layout.spacing / 2.0 + renderDistance * 16.0;
			if (Math.abs(p.x - layout.offsetX(f)) > reach || Math.abs(p.z) > reach) {
				continue;
			}
			working = f;
			UniformBufferManager ubm = uniforms[f];
			Frustum frustum = new FoldedFrustum(((ViewportAccessor) (Object) viewport).cubeworld$frustum(), m);
			Viewport folded = new Viewport(frustum, p);

			Vector3d last = seen[s][f];
			boolean moved = last == null || !last.equals(p);
			if (moved || lastCull[s][f] == null || !cullMatrix.equals(lastCull[s][f], 1.0E-4F) || fog.cullDistance() != lastFog[s][f]) {
				manager.notifyChangedCamera();
			}
			if (lastCull[s][f] == null) {
				lastCull[s][f] = new Matrix4f();
			}
			lastCull[s][f].set(cullMatrix);
			lastFog[s][f] = fog.cullDistance();

			manager.prepareFrame(p);
			ubm.prepareFrame();
			if (moved && last != null) {
				manager.processGFNIMovement(new CameraMovement(last, p));
			}
			seen[s][f] = p;
			position[f] = p;
			motion[f] = m;

			for (int i = 0; i < maxUpdates; i++) {
				manager.prepareRender();
				manager.prepareRenderTrees(folded, fog, occlusion);
				manager.cleanupAndFlip(ubm);
				manager.updateChunks(folded, immediately);
				manager.processChunkBuilds(folded, ubm);
				if (!manager.needsUpdate()) {
					break;
				}
			}
			manager.finalizeRenderLists(camera, folded, fog, immediately);
			manager.tickVisibleRenders();
			drawn[f] = true;
		}
		working = -1;
	}

	private static ChunkRenderMatrices folded(ChunkRenderMatrices matrices, int[] m) {
		Matrix4f turn = new Matrix4f(m[0], m[3], m[6], 0, m[1], m[4], m[7], 0, m[2], m[5], m[8], 0, 0, 0, 0, 1);
		return new ChunkRenderMatrices(matrices.projection(), new Matrix4f(matrices.modelView()).mul(turn));
	}

	public static void prepareRendering(ChunkRenderMatrices matrices, boolean indexed) {
		for (int f = 0; f < FACES; f++) {
			if (drawn[f]) {
				Vector3d p = position[f];
				managers[f].prepareChunkRendering(folded(matrices, motion[f]), p.x, p.y, p.z, indexed);
			}
		}
	}

	public static void draw(RenderPass renderPass, ChunkSectionLayerGroup group, ChunkRenderMatrices matrices, GpuSampler sampler,
		@Nullable OitStage stage, FogParameters fog, boolean sorting) {
		// The shadow pass needs them too: the pack lays the water caustics out on the world x and z of each shadow vertex.
		boolean faceUniforms = SeamClient.IRIS;
		for (int f = 0; f < FACES; f++) {
			if (!drawn[f]) {
				continue;
			}
			if (faceUniforms) {
				IrisFaceUniforms.beginFace(motion[f]);
			}
			working = f;
			ChunkRenderMatrices turned = folded(matrices, motion[f]);
			Vector3d p = position[f];
			RenderSectionManager manager = managers[f];
			UniformBufferManager ubm = uniforms[f];
			ubm.update(turned, fog);
			if (group == ChunkSectionLayerGroup.OPAQUE) {
				drawPass(manager, ubm, turned, DefaultTerrainRenderPasses.SOLID, p, fog, sorting, renderPass, sampler, stage);
				drawPass(manager, ubm, turned, DefaultTerrainRenderPasses.CUTOUT, p, fog, sorting, renderPass, sampler, stage);
			} else if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
				drawPass(manager, ubm, turned, DefaultTerrainRenderPasses.TRANSLUCENT, p, fog, sorting, renderPass, sampler, stage);
			}
		}
		working = -1;
		if (faceUniforms) {
			IrisFaceUniforms.endFaces();
		}
	}

	private static void drawPass(RenderSectionManager manager, UniformBufferManager ubm, ChunkRenderMatrices matrices, TerrainRenderPass pass,
		Vector3d p, FogParameters fog, boolean sorting, RenderPass renderPass, GpuSampler sampler, @Nullable OitStage stage) {
		manager.getChunkRenderer().render(matrices, manager.getRenderLists(), pass, new CameraTransform(p.x, p.y, p.z), fog, sorting,
			renderPass, sampler, ubm.getUniformBuffer(), ubm.getSectionTimeInfo(), stage);
	}

	/**
	 * Adds the block entities of the faces that were drawn this frame. Each state remembers the motion that carries its
	 * face to the camera face, and the dispatcher computes its camera distance in the frame of its face.
	 */
	public static void extractBlockEntities(Camera camera, float tickDelta, LevelRenderState levelRenderState) {
		BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
		try {
			for (int f = 0; f < FACES; f++) {
				if (!drawn[f]) {
					continue;
				}
				Vector3d p = position[f];
				dispatcher.prepare(new Vec3(p.x, p.y, p.z));
				RenderSectionManager manager = managers[f];
				var lists = manager.getRenderLists().iterator(false);
				while (lists.hasNext()) {
					ChunkRenderList renderList = lists.next();
					RenderRegion region = renderList.getRegion();
					ByteIterator ids = renderList.sectionsWithEntitiesIterator();
					while (ids != null && ids.hasNext()) {
						BlockEntity[] blockEntities = region.getCulledBlockEntities(ids.nextByteAsInt());
						if (blockEntities != null) {
							for (BlockEntity blockEntity : blockEntities) {
								addBlockEntity(dispatcher, blockEntity, tickDelta, false, motion[f], levelRenderState);
							}
						}
					}
				}
				for (RenderSection section : manager.getSectionsWithGlobalEntities()) {
					BlockEntity[] blockEntities = section.getRegion().getGlobalBlockEntities(section.getSectionIndex());
					if (blockEntities != null) {
						for (BlockEntity blockEntity : blockEntities) {
							addBlockEntity(dispatcher, blockEntity, tickDelta, true, motion[f], levelRenderState);
						}
					}
				}
			}
		} finally {
			dispatcher.prepare(camera.position());
		}
	}

	private static void addBlockEntity(BlockEntityRenderDispatcher dispatcher, BlockEntity blockEntity, float tickDelta, boolean global,
		int[] toCamera, LevelRenderState levelRenderState) {
		BlockEntityRenderState state = dispatcher.tryExtractRenderState(blockEntity, tickDelta, null, global);
		if (state != null) {
			levelRenderState.blockEntityRenderStates.add(state);
			SeamRenderer.setBlockEntityMotion(state, toCamera);
		}
	}
}

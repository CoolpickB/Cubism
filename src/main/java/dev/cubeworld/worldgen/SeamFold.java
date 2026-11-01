package dev.cubeworld.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The flat plane of a face, folded over one of its edges onto the neighbor face. A structure is laid out on the flat
 * plane of its face, so the part past an edge has no ground in its own region. The fold bends that part down the
 * neighbor face: a block at {@code d} past the edge goes to the block {@code d} past the edge on the neighbor face
 * (the edge row is shared), and keeps its height above the surface. As the up direction stays up, the fold is a
 * move in the horizontal plane that keeps Y, and block states need only a turn about the vertical axis.
 *
 * <p>The fold covers the strip past the edge, as wide as the face. The corner space past two edges has no neighbor
 * face in this plane and is not folded.
 */
public final class SeamFold {
	/** The edges of a face in its local axes: +X, -X, +Z, -Z. */
	public static final int EDGES = 4;

	private final SeamLayout layout;
	private final Fold[][] folds = new Fold[SeamLayout.FACES.length][EDGES];

	public SeamFold(SeamLayout layout) {
		this.layout = layout;
		for (int face = 0; face < SeamLayout.FACES.length; face++) {
			for (int edge = 0; edge < EDGES; edge++) {
				this.folds[face][edge] = this.build(face, edge);
			}
		}
	}

	public Fold get(int face, int edge) {
		return this.folds[face][edge];
	}

	/**
	 * The fold of the flat plane of {@code from} over one edge. A storage position {@code (x, z)} of the region of
	 * {@code from} goes to {@code (a x + b z + tx, c x + d z + tz)} in the region of {@code to}.
	 */
	public static final class Fold {
		public final int from;
		public final int edge;
		public final int to;
		private final int a;
		private final int b;
		private final int c;
		private final int d;
		private final int tx;
		private final int tz;
		private final BoundingBox strip;

		private Fold(int from, int edge, int to, int[] map, BoundingBox strip) {
			this.from = from;
			this.edge = edge;
			this.to = to;
			this.a = map[0];
			this.b = map[1];
			this.c = map[2];
			this.d = map[3];
			this.tx = map[4];
			this.tz = map[5];
				this.strip = strip;
		}

		public int x(int x, int z) {
			return this.a * x + this.b * z + this.tx;
		}

		public int z(int x, int z) {
			return this.c * x + this.d * z + this.tz;
		}

		public BlockPos apply(BlockPos pos) {
			return new BlockPos(this.x(pos.getX(), pos.getZ()), pos.getY(), this.z(pos.getX(), pos.getZ()));
		}

		/** The cell of the flat plane of the first face that the fold puts at a storage position of the target face. */
		public BlockPos inverse(BlockPos pos) {
			int x = pos.getX() - this.tx;
			int z = pos.getZ() - this.tz;
			return new BlockPos(this.a * x + this.c * z, pos.getY(), this.b * x + this.d * z);
		}

		/** The box that the fold makes of a box. */
		public BoundingBox apply(BoundingBox box) {
			int x0 = this.x(box.minX(), box.minZ());
			int x1 = this.x(box.maxX(), box.maxZ());
			int z0 = this.z(box.minX(), box.minZ());
			int z1 = this.z(box.maxX(), box.maxZ());
			return new BoundingBox(Math.min(x0, x1), box.minY(), Math.min(z0, z1), Math.max(x0, x1), box.maxY(), Math.max(z0, z1));
		}

		/** The turn of a structure about the vertical axis when the fold moves it. */
		public Rotation rotation() {
			if (this.a == 1) {
				return Rotation.NONE;
			}
			if (this.a == -1) {
				return Rotation.CLOCKWISE_180;
			}
			return this.c == 1 ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90;
		}

		/** The cells of the target face that this fold fills: the rows from the edge row inward, within the width of the face. */
		public BoundingBox strip(int minY, int maxY) {
			return new BoundingBox(this.strip.minX(), minY, this.strip.minZ(), this.strip.maxX(), maxY, this.strip.maxZ());
		}
	}

	private Fold build(int from, int edge) {
		int[] base = this.raw(from, edge, this.layout.offsetX(from), 0);
		int[] px = this.raw(from, edge, this.layout.offsetX(from) + 1, 0);
		int[] pz = this.raw(from, edge, this.layout.offsetX(from), 1);
		int ox = this.layout.offsetX(from);
		int a = px[0] - base[0];
		int c = px[1] - base[1];
		int b = pz[0] - base[0];
		int d = pz[1] - base[1];
		int tx = base[0] - a * ox;
		int tz = base[1] - c * ox;
		int to = this.target(from, edge);
		int r = this.layout.radius;
		// The cells of the target face at least one row from the shared edge row, in the axes of the target face.
		int[] m = this.layout.rotation(to);
		int[] f = this.layout.rotation(from);
		int[] up = {f[1], f[4], f[7]};
		int off = this.layout.offsetX(to);
		int[] xr = this.range(up, m[0], m[3], m[6], r);
		int[] zr = this.range(up, m[2], m[5], m[8], r);
		BoundingBox strip = new BoundingBox(off + xr[0], 0, zr[0], off + xr[1], 0, zr[1]);
		return new Fold(from, edge, to, new int[] {a, b, c, d, tx, tz}, strip);
	}

	/** The range of one local axis of the target face over the strip: one row from the edge row to the far edge, and the face width along the edge. */
	private int[] range(int[] up, int ax, int ay, int az, int r) {
		int alongUp = up[0] * ax + up[1] * ay + up[2] * az;
		if (alongUp == 0) {
			return new int[] {-r, r};
		}
		return alongUp > 0 ? new int[] {-r, r - 1} : new int[] {-(r - 1), r};
	}

	private int[] edgeDirection(int[] f, int edge) {
		return switch (edge) {
			case 0 -> new int[] {f[0], f[3], f[6]};
			case 1 -> new int[] {-f[0], -f[3], -f[6]};
			case 2 -> new int[] {f[2], f[5], f[8]};
			default -> new int[] {-f[2], -f[5], -f[8]};
		};
	}

	private int target(int from, int edge) {
		int[] direction = this.edgeDirection(this.layout.rotation(from), edge);
		for (int face = 0; face < SeamLayout.FACES.length; face++) {
			int[] m = this.layout.rotation(face);
			if (m[1] == direction[0] && m[4] == direction[1] && m[7] == direction[2]) {
				return face;
			}
		}
		throw new IllegalStateException("No face over edge " + edge + " of face " + from);
	}

	/** The position in the region of the target face, for storage (x, z) of the face, from the cube vectors. */
	private int[] raw(int from, int edge, int x, int z) {
		int r = this.layout.radius;
		int[] f = this.layout.rotation(from);
		int to = this.target(from, edge);
		int[] g = this.layout.rotation(to);
		int lx = x - this.layout.offsetX(from);
		int[] up = {f[1], f[4], f[7]};
		int[] cf;
		int along;
		int lateral;
		if (edge < 2) {
			along = edge == 0 ? lx : -lx;
			cf = new int[] {f[2], f[5], f[8]};
			lateral = z;
		} else {
			along = edge == 2 ? z : -z;
			cf = new int[] {f[0], f[3], f[6]};
			lateral = lx;
		}
		int n = 2 * r - along;
		int[] gx = {g[0], g[3], g[6]};
		int[] gz = {g[2], g[5], g[8]};
		int nx = n * dot(up, gx) + lateral * dot(cf, gx);
		int nz = n * dot(up, gz) + lateral * dot(cf, gz);
		return new int[] {this.layout.offsetX(to) + nx, nz};
	}

	private static int dot(int[] p, int[] q) {
		return p[0] * q[0] + p[1] * q[1] + p[2] * q[2];
	}
}

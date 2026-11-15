package com.github.roompredictor.core;

/**
 * Turns the 128x128 colour buffer of the dungeon map item into a {@link MapGrid}.
 *
 * <p>Layout facts this relies on: rooms are drawn as equal squares separated by a 4 pixel gap,
 * the grid is centred on the map, the Entrance is always drawn, doors are drawn in the middle of
 * a gap and two tiles of the same room have their whole gap filled.
 */
public final class MapDecoder {
	public static final int SIZE = 128;
	public static final int GAP = 4;
	private static final byte ENTRANCE = 30;

	/** Pixel geometry, found once per run from the Entrance square. */
	public record Geometry(int roomSize, int offsetX, int offsetZ) {
		public int pitch() {
			return roomSize + GAP;
		}
	}

	private MapDecoder() {
	}

	/** Finds the room size and grid origin, or null if the Entrance isn't drawn yet. */
	public static Geometry geometry(byte[] colors, Floor floor) {
		if (colors == null || colors.length < SIZE * SIZE) return null;
		for (int z = 0; z < SIZE; z++) {
			for (int x = 0; x < SIZE; x++) {
				if (colors[z * SIZE + x] != ENTRANCE) continue;
				if (x > 0 && colors[z * SIZE + x - 1] == ENTRANCE) continue;
				if (z > 0 && colors[(z - 1) * SIZE + x] == ENTRANCE) continue;
				int w = 0;
				while (x + w < SIZE && colors[z * SIZE + x + w] == ENTRANCE) w++;
				int h = 0;
				while (z + h < SIZE && colors[(z + h) * SIZE + x] == ENTRANCE) h++;
				// Checkmarks share the Entrance colour but are much smaller than a room.
				if (w < 10 || Math.abs(w - h) > 1) continue;
				int pitch = w + GAP;
				Integer ox = origin(x, pitch, floor.width, w);
				Integer oz = origin(z, pitch, floor.height, w);
				if (ox == null || oz == null) return null;
				return new Geometry(w, ox, oz);
			}
		}
		return null;
	}

	/**
	 * Grid origin along one axis: the first room position at or after the map edge that lines up
	 * with the Entrance, shifted one room in when the grid leaves that much space (small grids
	 * are centred). On floors with an extra puzzle column the normal columns are centred and the
	 * extra column runs off the right edge, so the grid may not fit on the map.
	 */
	private static Integer origin(int roomStart, int pitch, int count, int roomSize) {
		int span = count * pitch - GAP;
		int o = roomStart % pitch;
		if (SIZE - span >= 2 * pitch) o += pitch;
		return o;
	}

	public static MapGrid decode(byte[] colors, Floor floor, Geometry g) {
		MapGrid.Builder b = new MapGrid.Builder(floor);
		int rs = g.roomSize();
		int p = g.pitch();
		for (int z = 0; z < floor.height; z++) {
			for (int x = 0; x < floor.width; x++) {
				int px = g.offsetX() + x * p + 1;
				int pz = g.offsetZ() + z * p + 1;
				Tile t = Tile.fromMapColor(at(colors, px, pz));
				if (t == null) t = Tile.EMPTY;
				b.tile(x, z, t);
				if (t.explored()) b.check(x, z, check(colors, g.offsetX() + x * p, g.offsetZ() + z * p, rs, t));
			}
		}
		for (int z = 0; z < floor.height; z++) {
			for (int x = 0; x < floor.width; x++) {
				int left = g.offsetX() + x * p;
				int top = g.offsetZ() + z * p;
				if (x + 1 < floor.width) {
					int gx = left + rs + GAP / 2;
					b.east(x, z, link(at(colors, gx, top + 1), at(colors, gx, top + rs / 2)));
				}
				if (z + 1 < floor.height) {
					int gz = top + rs + GAP / 2;
					b.south(x, z, link(at(colors, left + 1, gz), at(colors, left + rs / 2, gz)));
				}
			}
		}
		return b.build();
	}

	/** Looks for a check icon colour in the middle of a tile (the icon is a thin shape, so scan an area). */
	private static Check check(byte[] c, int left, int top, int rs, Tile t) {
		Check best = Check.NONE;
		int r = Math.max(2, rs / 4);
		int cx = left + rs / 2, cz = top + rs / 2;
		for (int z = cz - r; z <= cz + r; z++) {
			for (int x = cx - r; x <= cx + r; x++) {
				Check k = Check.fromIconColor(at(c, x, z), t);
				if (k != null && k.ordinal() > best.ordinal() && k != Check.NONE) best = k;
			}
		}
		return best;
	}

	/** Every pixel {@link #decode} reads, as {x, z, kind}: kind 0 = tile, 1 = gap edge, 2 = gap middle. */
	public static java.util.List<int[]> samplePoints(Floor floor, Geometry g) {
		java.util.List<int[]> out = new java.util.ArrayList<>();
		int rs = g.roomSize(), p = g.pitch();
		for (int z = 0; z < floor.height; z++) {
			for (int x = 0; x < floor.width; x++) {
				int left = g.offsetX() + x * p, top = g.offsetZ() + z * p;
				out.add(new int[]{left + 1, top + 1, 0});
				if (x + 1 < floor.width) {
					int gx = left + rs + GAP / 2;
					out.add(new int[]{gx, top + 1, 1});
					out.add(new int[]{gx, top + rs / 2, 2});
				}
				if (z + 1 < floor.height) {
					int gz = top + rs + GAP / 2;
					out.add(new int[]{left + 1, gz, 1});
					out.add(new int[]{left + rs / 2, gz, 2});
				}
			}
		}
		return out;
	}

	/** {@code edge} is sampled near the corner of the gap, {@code middle} where a door would be. */
	private static Link link(byte edge, byte middle) {
		if (middle == 0) return Link.NONE;
		if (edge != 0 && edge == middle) return Link.MERGED;
		if (middle == 119) return Link.WITHER_DOOR;
		if (middle == 18) return Link.BLOOD_DOOR;
		return Link.DOOR;
	}

	private static byte at(byte[] c, int x, int z) {
		if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) return 0;
		return c[z * SIZE + x];
	}
}

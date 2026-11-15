package com.github.roompredictor;

import com.github.roompredictor.core.*;

import java.util.Arrays;

/**
 * Test fixture: the M7 layout from the Strad M7 plugin (rooms + door pairs), plus a painter that
 * draws a grid into a 128x128 map colour buffer the way Hypixel's map item looks.
 */
public final class TestMaps {
	private TestMaps() {
	}

	/** room name -> cells, as in the M7 plugin's Rooms.java */
	static final Object[][] ROOMS = {
		{Tile.PUZZLE, new int[][]{{0, 0}}},
		{Tile.NORMAL, new int[][]{{1, 0}, {2, 0}, {3, 0}, {4, 0}}},
		{Tile.NORMAL, new int[][]{{5, 0}, {4, 1}, {5, 1}}},
		{Tile.NORMAL, new int[][]{{0, 1}, {1, 1}, {2, 1}}},
		{Tile.BLOOD, new int[][]{{3, 1}}},
		{Tile.NORMAL, new int[][]{{4, 2}, {5, 2}, {4, 3}, {5, 3}}},
		{Tile.NORMAL, new int[][]{{1, 2}, {2, 2}, {3, 2}}},
		{Tile.NORMAL, new int[][]{{0, 2}, {0, 3}, {1, 3}}},
		{Tile.NORMAL, new int[][]{{2, 3}, {2, 4}, {2, 5}}},
		{Tile.FAIRY, new int[][]{{3, 3}}},
		{Tile.NORMAL, new int[][]{{0, 4}, {0, 5}, {1, 5}}},
		{Tile.PUZZLE, new int[][]{{1, 4}}},
		{Tile.NORMAL, new int[][]{{3, 4}, {4, 4}, {5, 4}}},
		{Tile.ENTRANCE, new int[][]{{3, 5}}},
		{Tile.TRAP, new int[][]{{4, 5}}},
		{Tile.YELLOW, new int[][]{{5, 5}}},
	};

	static final int[][][] DOORS = {
		{{0, 0}, {0, 1}}, {{4, 0}, {5, 0}}, {{5, 1}, {5, 2}}, {{3, 2}, {4, 2}}, {{3, 2}, {3, 1}},
		{{3, 2}, {3, 3}}, {{2, 2}, {2, 1}}, {{0, 1}, {0, 2}}, {{3, 3}, {3, 4}}, {{3, 4}, {3, 5}},
		{{4, 4}, {4, 5}}, {{5, 4}, {5, 5}}, {{3, 3}, {2, 3}}, {{2, 5}, {1, 5}}, {{0, 4}, {1, 4}}
	};

	/** The fully explored M7 map. */
	public static MapGrid fullM7() {
		MapGrid.Builder b = new MapGrid.Builder(Floor.M7);
		for (Object[] r : ROOMS) {
			Tile t = (Tile) r[0];
			int[][] cells = (int[][]) r[1];
			for (int[] c : cells) b.tile(c[0], c[1], t);
			for (int[] a : cells) {
				for (int[] c : cells) {
					if (c[0] == a[0] + 1 && c[1] == a[1]) b.east(a[0], a[1], Link.MERGED);
					if (c[0] == a[0] && c[1] == a[1] + 1) b.south(a[0], a[1], Link.MERGED);
				}
			}
		}
		for (int[][] d : DOORS) {
			int[] a = d[0], c = d[1];
			Link l = Link.DOOR;
			if (Arrays.equals(a, new int[]{3, 2}) && Arrays.equals(c, new int[]{3, 3})) l = Link.WITHER_DOOR;
			if (Arrays.equals(a, new int[]{3, 2}) && Arrays.equals(c, new int[]{3, 1})) l = Link.BLOOD_DOOR;
			int x0 = Math.min(a[0], c[0]), z0 = Math.min(a[1], c[1]);
			if (a[1] == c[1]) b.east(x0, z0, l);
			else b.south(x0, z0, l);
		}
		return b.build();
	}

	static byte color(Tile t) {
		return switch (t) {
			case EMPTY -> 0;
			case UNKNOWN -> 85;
			case ENTRANCE -> 30;
			case NORMAL -> 63;
			case PUZZLE -> 66;
			case TRAP -> 62;
			case YELLOW -> 74;
			case BLOOD -> 18;
			case FAIRY -> 82;
		};
	}

	/** Paints a grid into map colours: 16px rooms, 4px gaps, grid centred, 6px wide doors. */
	public static byte[] paint(MapGrid g) {
		return paint(g, g.w == 6 || g.h == 6 ? 16 : 18);
	}

	/** Same, with an explicit room size in map pixels. */
	public static byte[] paint(MapGrid g, int rs) {
		byte[] c = new byte[128 * 128];
		int pitch = rs + 4;
		// Hypixel centres the dungeon's actual width: the normal columns, plus the extra puzzle
		// column (F4-F6/M4-M6) only if a side puzzle is there.
		boolean sidePuzzles = false;
		for (int i = 0; i < g.size(); i++) if (g.floor.isExtraColumn(g.x(i)) && g.tile(i) != Tile.EMPTY) sidePuzzles = true;
		int cols = sidePuzzles ? g.w : g.floor.standardWidth;
		int ox = (128 - (cols * pitch - 4)) / 2;
		int oz = (128 - (g.h * pitch - 4)) / 2;
		for (int i = 0; i < g.size(); i++) {
			Tile t = g.tile(i);
			if (t == Tile.EMPTY) continue;
			int x0 = ox + g.x(i) * pitch, z0 = oz + g.z(i) * pitch;
			fill(c, x0, z0, rs, rs, color(t));
			Check ck = g.check(i);
			if (ck != Check.NONE) {
				byte cc = ck == Check.GREEN ? 30 : ck == Check.WHITE ? 34 : (byte) 18;
				fill(c, x0 + rs / 2 - 2, z0 + rs / 2 - 2, 4, 4, cc);
			}
			for (int d = 1; d <= 2; d++) {
				Link l = g.link(i, d);
				if (l == Link.NONE) continue;
				byte col = switch (l) {
					case WITHER_DOOR -> 119;
					case BLOOD_DOOR -> 18;
					case MERGED -> color(t);
					default -> 63;
				};
				if (l == Link.MERGED) {
					if (d == 1) fill(c, x0 + rs, z0, 4, rs, col);
					else fill(c, x0, z0 + rs, rs, 4, col);
				} else {
					if (d == 1) fill(c, x0 + rs, z0 + rs / 2 - 3, 4, 6, col);
					else fill(c, x0 + rs / 2 - 3, z0 + rs, 6, 4, col);
				}
			}
		}
		return c;
	}

	private static void fill(byte[] c, int x, int z, int w, int h, byte v) {
		// Clipped at the map edge (an extra puzzle column can run off the right side).
		for (int zz = Math.max(0, z); zz < Math.min(128, z + h); zz++)
			for (int xx = Math.max(0, x); xx < Math.min(128, x + w); xx++) c[zz * 128 + xx] = v;
	}

	/** Reveal order for the fixture: breadth-first from the Entrance, one room per 10 seconds. */
	public static int[] revealOrder(MapGrid g) {
		int[] dist = g.walkDistances(g.entrance());
		int[] reveal = new int[g.size()];
		for (int i = 0; i < g.size(); i++) reveal[i] = dist[i] < 0 ? -1 : dist[i] * 10;
		return reveal;
	}
}

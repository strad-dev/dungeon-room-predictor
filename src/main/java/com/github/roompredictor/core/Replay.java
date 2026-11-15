package com.github.roompredictor.core;

import java.util.Arrays;
import java.util.TreeSet;

/** Rebuilds what the map item looked like at a moment of a stored run, from its reveal times. */
public final class Replay {
	private Replay() {
	}

	/** Distinct reveal seconds in order (the steps the viewer can scrub through). */
	public static int[] steps(int[] reveal) {
		TreeSet<Integer> s = new TreeSet<>();
		if (reveal != null) for (int r : reveal) if (r >= 0) s.add(r);
		return s.stream().mapToInt(Integer::intValue).toArray();
	}

	/**
	 * The map at second {@code t}: cells revealed by then are shown as stored; an unrevealed room
	 * reachable through a door of a revealed cell shows as a single "?" on the door side (as the
	 * real map does); everything else is blank.
	 */
	public static MapGrid at(MapGrid fin, int[] reveal, int t) {
		if (reveal == null || reveal.length != fin.size()) return fin;
		int n = fin.size();
		boolean[] open = new boolean[n];
		for (int i = 0; i < n; i++) open[i] = fin.tile(i).explored() && reveal[i] >= 0 && reveal[i] <= t;
		Tile[] tiles = new Tile[n];
		Arrays.fill(tiles, Tile.EMPTY);
		MapGrid.Builder b = new MapGrid.Builder(fin.floor);
		for (int i = 0; i < n; i++) {
			if (open[i]) tiles[i] = fin.tile(i);
		}
		for (int i = 0; i < n; i++) {
			if (!open[i]) continue;
			for (int d = 0; d < 4; d++) {
				int nb = fin.neighbor(i, d);
				if (nb < 0 || open[nb]) continue;
				if (fin.link(i, d).isDoor() && fin.tile(nb) != Tile.EMPTY) tiles[nb] = Tile.UNKNOWN;
			}
		}
		for (int i = 0; i < n; i++) {
			b.tile(fin.x(i), fin.z(i), tiles[i]);
			int e = fin.neighbor(i, 1);
			if (e >= 0 && (open[i] || open[e]) && tiles[i] != Tile.EMPTY && tiles[e] != Tile.EMPTY) {
				Link l = fin.link(i, 1);
				if (l != Link.MERGED || (open[i] && open[e])) b.east(fin.x(i), fin.z(i), l);
			}
			int s = fin.neighbor(i, 2);
			if (s >= 0 && (open[i] || open[s]) && tiles[i] != Tile.EMPTY && tiles[s] != Tile.EMPTY) {
				Link l = fin.link(i, 2);
				if (l != Link.MERGED || (open[i] && open[s])) b.south(fin.x(i), fin.z(i), l);
			}
		}
		return b.build();
	}
}

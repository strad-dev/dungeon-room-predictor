package com.github.roompredictor.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Immutable snapshot of what the dungeon map item shows: a tile per grid cell and the links
 * (walls, doors, same-room joins) between orthogonal neighbours.
 */
public final class MapGrid {
	public final Floor floor;
	public final int w;
	public final int h;
	private final Tile[] tiles;
	/** Link between (x,z) and (x+1,z), indexed by the left cell. */
	private final Link[] east;
	/** Link between (x,z) and (x,z+1), indexed by the top cell. */
	private final Link[] south;
	/** Same-room component id per cell (cells joined by MERGED links), -1 for EMPTY. */
	private final int[] component;
	private final int entrance;
	/** Checkmark per cell (spread over whole rooms). */
	private final Check[] checks;

	public MapGrid(Floor floor, Tile[] tiles, Link[] east, Link[] south) {
		this(floor, tiles, east, south, null);
	}

	public MapGrid(Floor floor, Tile[] tiles, Link[] east, Link[] south, Check[] checks) {
		this.floor = floor;
		this.w = floor.width;
		this.h = floor.height;
		this.tiles = tiles.clone();
		this.east = east.clone();
		this.south = south.clone();
		this.component = new int[w * h];
		Arrays.fill(component, -1);
		int next = 0;
		int ent = -1;
		for (int i = 0; i < w * h; i++) {
			if (this.tiles[i] == Tile.ENTRANCE && ent < 0) ent = i;
			if (this.tiles[i] == Tile.EMPTY || component[i] >= 0) continue;
			ArrayDeque<Integer> q = new ArrayDeque<>();
			q.add(i);
			component[i] = next;
			while (!q.isEmpty()) {
				int c = q.poll();
				for (int d = 0; d < 4; d++) {
					int n = neighbor(c, d);
					if (n < 0 || component[n] >= 0 || link(c, d) != Link.MERGED) continue;
					component[n] = next;
					q.add(n);
				}
			}
			next++;
		}
		this.entrance = ent;
		// A room's check is drawn on one of its tiles; give every tile of the room the best one.
		this.checks = new Check[w * h];
		Arrays.fill(this.checks, Check.NONE);
		if (checks != null) {
			for (int i = 0; i < w * h && i < checks.length; i++) {
				if (checks[i] == null || checks[i] == Check.NONE || component[i] < 0) continue;
				for (int j = 0; j < w * h; j++) {
					if (component[j] == component[i] && checks[i].ordinal() > this.checks[j].ordinal()) this.checks[j] = checks[i];
				}
			}
		}
	}

	public Check check(int i) {
		return checks[i];
	}

	public static MapGrid empty(Floor floor) {
		int n = floor.cells();
		Tile[] t = new Tile[n];
		Link[] e = new Link[n];
		Link[] s = new Link[n];
		Arrays.fill(t, Tile.EMPTY);
		Arrays.fill(e, Link.NONE);
		Arrays.fill(s, Link.NONE);
		return new MapGrid(floor, t, e, s);
	}

	/**
	 * What the map will show at the start of a run, built before the map item exists: the
	 * Entrance where the player spawned and a "?" behind its single door, which is the direction
	 * the player faces when joining the dungeon. {@code dir}: 0 north, 1 east, 2 south, 3 west.
	 * Returns null if the spawn cell is off the grid.
	 */
	public static MapGrid spawnGrid(Floor floor, int cellX, int cellZ, int dir) {
		if (cellX < 0 || cellZ < 0 || cellX >= floor.standardWidth || cellZ >= floor.height) return null;
		Builder b = new Builder(floor).tile(cellX, cellZ, Tile.ENTRANCE);
		int nx = cellX + (dir == 1 ? 1 : dir == 3 ? -1 : 0);
		int nz = cellZ + (dir == 2 ? 1 : dir == 0 ? -1 : 0);
		if (dir >= 0 && nx >= 0 && nz >= 0 && nx < floor.standardWidth && nz < floor.height) {
			b.tile(nx, nz, Tile.UNKNOWN);
			switch (dir) {
				case 0 -> b.south(nx, nz, Link.DOOR);
				case 1 -> b.east(cellX, cellZ, Link.DOOR);
				case 2 -> b.south(cellX, cellZ, Link.DOOR);
				default -> b.east(nx, nz, Link.DOOR);
			}
		}
		return b.build();
	}

	/** Side of the Entrance its door is on (0 north .. 3 west), or -1 if no door is visible. */
	public int entranceDoorDir() {
		if (entrance < 0) return -1;
		for (int d = 0; d < 4; d++) if (link(entrance, d).isDoor()) return d;
		return -1;
	}

	public int size() {
		return w * h;
	}

	public int idx(int x, int z) {
		return z * w + x;
	}

	public int x(int i) {
		return i % w;
	}

	public int z(int i) {
		return i / w;
	}

	public Tile tile(int i) {
		return tiles[i];
	}

	public int entrance() {
		return entrance;
	}

	public int component(int i) {
		return component[i];
	}

	/** Directions: 0 = -z (north), 1 = +x (east), 2 = +z (south), 3 = -x (west). Returns -1 off-grid. */
	public int neighbor(int i, int dir) {
		int x = x(i), z = z(i);
		switch (dir) {
			case 0 -> z--;
			case 1 -> x++;
			case 2 -> z++;
			default -> x--;
		}
		if (x < 0 || z < 0 || x >= w || z >= h) return -1;
		return idx(x, z);
	}

	public Link link(int i, int dir) {
		int n = neighbor(i, dir);
		if (n < 0) return Link.NONE;
		return switch (dir) {
			case 0 -> south[n];
			case 1 -> east[i];
			case 2 -> south[i];
			default -> east[n];
		};
	}

	public int doorCount(int i) {
		int c = 0;
		for (int d = 0; d < 4; d++) if (link(i, d).isDoor()) c++;
		return c;
	}

	/** Doors of the whole room containing cell i. */
	public int roomDoorCount(int i) {
		int comp = component[i];
		if (comp < 0) return doorCount(i);
		int c = 0;
		for (int j = 0; j < size(); j++) if (component[j] == comp) c += doorCount(j);
		return c;
	}

	public List<int[]> componentCells(int i) {
		List<int[]> out = new ArrayList<>();
		int comp = component[i];
		if (comp < 0) {
			out.add(new int[]{x(i), z(i)});
			return out;
		}
		for (int j = 0; j < size(); j++) if (component[j] == comp) out.add(new int[]{x(j), z(j)});
		return out;
	}

	public int componentSize(int i) {
		int comp = component[i];
		if (comp < 0) return 1;
		int c = 0;
		for (int j = 0; j < size(); j++) if (component[j] == comp) c++;
		return c;
	}

	/** Walkable-tile distance from cell {@code from} over known links. -1 where unreachable. */
	public int[] walkDistances(int from) {
		int[] dist = new int[size()];
		Arrays.fill(dist, -1);
		if (from < 0) return dist;
		ArrayDeque<Integer> q = new ArrayDeque<>();
		dist[from] = 0;
		q.add(from);
		while (!q.isEmpty()) {
			int c = q.poll();
			for (int d = 0; d < 4; d++) {
				int n = neighbor(c, d);
				if (n < 0 || dist[n] >= 0 || !link(c, d).walkable()) continue;
				dist[n] = dist[c] + 1;
				q.add(n);
			}
		}
		return dist;
	}

	public int manhattan(int a, int b) {
		return Math.abs(x(a) - x(b)) + Math.abs(z(a) - z(b));
	}

	public int find(Tile t) {
		for (int i = 0; i < size(); i++) if (tiles[i] == t) return i;
		return -1;
	}

	public int count(Tile t) {
		int c = 0;
		for (Tile x : tiles) if (x == t) c++;
		return c;
	}

	/** Explored puzzles are 1x1, so tile count == room count. */
	public int countRooms(Tile t) {
		boolean[] seen = new boolean[size() + 1];
		int c = 0;
		for (int i = 0; i < size(); i++) {
			if (tiles[i] != t) continue;
			int comp = component[i];
			if (comp >= 0 && seen[comp]) continue;
			if (comp >= 0) seen[comp] = true;
			c++;
		}
		return c;
	}

	public boolean sameAs(MapGrid o) {
		return o != null && o.floor == floor && Arrays.equals(tiles, o.tiles)
			&& Arrays.equals(east, o.east) && Arrays.equals(south, o.south) && Arrays.equals(checks, o.checks);
	}

	/** Compact text form for the run log: one char per tile, then links. */
	public String encode() {
		StringBuilder sb = new StringBuilder();
		for (Tile t : tiles) sb.append("._ENPTYBF".charAt(t.ordinal()));
		sb.append('|');
		for (Link l : east) sb.append(".MDWB".charAt(l.ordinal()));
		sb.append('|');
		for (Link l : south) sb.append(".MDWB".charAt(l.ordinal()));
		sb.append('|');
		for (Check c : checks) sb.append(".wgx".charAt(c.ordinal()));
		return sb.toString();
	}

	public static MapGrid decode(Floor floor, String s) {
		String[] parts = s.split("\\|");
		int n = floor.cells();
		if (parts.length < 3 || parts[0].length() != n || parts[1].length() != n || parts[2].length() != n) return null;
		Check[] ch = new Check[n];
		Arrays.fill(ch, Check.NONE);
		if (parts.length >= 4 && parts[3].length() == n) {
			for (int i = 0; i < n; i++) {
				int ci = ".wgx".indexOf(parts[3].charAt(i));
				if (ci >= 0) ch[i] = Check.values()[ci];
			}
		}
		Tile[] t = new Tile[n];
		Link[] e = new Link[n];
		Link[] so = new Link[n];
		for (int i = 0; i < n; i++) {
			int ti = "._ENPTYBF".indexOf(parts[0].charAt(i));
			int ei = ".MDWB".indexOf(parts[1].charAt(i));
			int si = ".MDWB".indexOf(parts[2].charAt(i));
			if (ti < 0 || ei < 0 || si < 0) return null;
			t[i] = Tile.values()[ti];
			e[i] = Link.values()[ei];
			so[i] = Link.values()[si];
		}
		return new MapGrid(floor, t, e, so, ch);
	}

	/** Builder used by the decoder and by tests. */
	public static final class Builder {
		private final Floor floor;
		private final Tile[] tiles;
		private final Link[] east;
		private final Link[] south;
		private final Check[] checks;

		public Builder(Floor floor) {
			this.floor = floor;
			int n = floor.cells();
			tiles = new Tile[n];
			east = new Link[n];
			south = new Link[n];
			Arrays.fill(tiles, Tile.EMPTY);
			Arrays.fill(east, Link.NONE);
			Arrays.fill(south, Link.NONE);
			checks = new Check[n];
			Arrays.fill(checks, Check.NONE);
		}

		public Builder check(int x, int z, Check c) {
			checks[z * floor.width + x] = c;
			return this;
		}

		public Builder tile(int x, int z, Tile t) {
			tiles[z * floor.width + x] = t;
			return this;
		}

		public Builder east(int x, int z, Link l) {
			east[z * floor.width + x] = l;
			return this;
		}

		public Builder south(int x, int z, Link l) {
			south[z * floor.width + x] = l;
			return this;
		}

		public MapGrid build() {
			return new MapGrid(floor, tiles, east, south, checks);
		}
	}
}

package com.github.roompredictor.core;

import java.util.ArrayList;
import java.util.List;

/** Room footprints. Every shape except 1x1 can appear at most twice per dungeon. */
public enum Shape {
	S1x1("1x1", new int[][]{{0, 0}}),
	S1x2("1x2", new int[][]{{0, 0}, {1, 0}}),
	S1x3("1x3", new int[][]{{0, 0}, {1, 0}, {2, 0}}),
	S1x4("1x4", new int[][]{{0, 0}, {1, 0}, {2, 0}, {3, 0}}),
	L("L", new int[][]{{0, 0}, {1, 0}, {0, 1}}),
	S2x2("2x2", new int[][]{{0, 0}, {1, 0}, {0, 1}, {1, 1}});

	public static final Shape[] ALL = values();
	public static final int COUNT = ALL.length;
	public static final int MAX_PER_DUNGEON = 2;

	public final String display;
	private final int[][] cells;
	private List<int[][]> orientations;

	Shape(String display, int[][] cells) {
		this.display = display;
		this.cells = cells;
	}

	public int size() {
		return cells.length;
	}

	/** All distinct rotations/reflections, each normalised so its minimum x and z are 0. */
	public synchronized List<int[][]> orientations() {
		if (orientations != null) return orientations;
		List<int[][]> out = new ArrayList<>();
		List<String> seen = new ArrayList<>();
		for (int r = 0; r < 8; r++) {
			int[][] o = new int[cells.length][];
			int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
			for (int i = 0; i < cells.length; i++) {
				int x = cells[i][0], z = cells[i][1];
				for (int k = 0; k < (r & 3); k++) {
					int t = x;
					x = -z;
					z = t;
				}
				if (r >= 4) x = -x;
				o[i] = new int[]{x, z};
				minX = Math.min(minX, x);
				minZ = Math.min(minZ, z);
			}
			for (int[] c : o) {
				c[0] -= minX;
				c[1] -= minZ;
			}
			java.util.Arrays.sort(o, (a, b) -> a[1] != b[1] ? a[1] - b[1] : a[0] - b[0]);
			StringBuilder key = new StringBuilder();
			for (int[] c : o) key.append(c[0]).append(',').append(c[1]).append(';');
			if (seen.contains(key.toString())) continue;
			seen.add(key.toString());
			out.add(o);
		}
		orientations = out;
		return out;
	}

	/** Classifies a set of grid cells (as {x,z}) into a shape, or null if it isn't one. */
	public static Shape classify(List<int[]> comp) {
		int n = comp.size();
		if (n == 0 || n > 4) return null;
		int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (int[] c : comp) {
			minX = Math.min(minX, c[0]);
			maxX = Math.max(maxX, c[0]);
			minZ = Math.min(minZ, c[1]);
			maxZ = Math.max(maxZ, c[1]);
		}
		int w = maxX - minX + 1, h = maxZ - minZ + 1;
		if (n == 1) return S1x1;
		if (n == 2) return S1x2;
		if (n == 3) return (w == 3 || h == 3) ? S1x3 : L;
		return (w == 4 || h == 4) ? S1x4 : (w == 2 && h == 2 ? S2x2 : null);
	}
}

package com.github.roompredictor.core;

/**
 * Dungeon floors and their room grids.
 *
 * <p>{@code width} is the full grid width as drawn on the map. On F4-F6 / M4-M6 the right-most
 * column ({@code x >= standardWidth}) is an extra column where only puzzles may generate.
 */
public enum Floor {
	E(0, false, 4, 4, 4),
	F1(1, false, 4, 5, 4),
	F2(2, false, 5, 5, 5),
	F3(3, false, 5, 5, 5),
	F4(4, false, 6, 5, 5),
	F5(5, false, 6, 6, 5),
	F6(6, false, 6, 6, 5),
	F7(7, false, 6, 6, 6),
	M1(1, true, 4, 5, 4),
	M2(2, true, 5, 5, 5),
	M3(3, true, 5, 5, 5),
	M4(4, true, 6, 5, 5),
	M5(5, true, 6, 6, 5),
	M6(6, true, 6, 6, 5),
	M7(7, true, 6, 6, 6);

	/** Number of distinct grid layouts ("brackets"); learned position priors are kept per bracket. */
	public static final int BRACKETS = 6;
	/** Largest grid side across all floors; position features use a 6x6 index. */
	public static final int MAX_DIM = 6;

	public final int number;
	public final boolean master;
	public final int width;
	public final int height;
	public final int standardWidth;

	Floor(int number, boolean master, int width, int height, int standardWidth) {
		this.number = number;
		this.master = master;
		this.width = width;
		this.height = height;
		this.standardWidth = standardWidth;
	}

	/** Trap rooms don't exist on Entrance, F1/M1 or F2/M2. */
	public boolean hasTrap() {
		return number >= 3;
	}

	/** Normal and master mode share generation rules, so they share a bracket. */
	public int bracket() {
		return switch (number) {
			case 0 -> 0;
			case 1 -> 1;
			case 2, 3 -> 2;
			case 4 -> 3;
			case 5, 6 -> 4;
			default -> 5;
		};
	}

	/** Used until the tab list reports the real puzzle count (and the model has no history yet). */
	public int defaultPuzzles() {
		return switch (bracket()) {
			case 0, 1 -> 2;
			case 2 -> 3;
			case 3, 4 -> 4;
			default -> 5;
		};
	}

	public int cells() {
		return width * height;
	}

	public boolean isExtraColumn(int x) {
		return x >= standardWidth;
	}

	/** Parses "F7", "M3", "E" (as shown in "The Catacombs (F7)"). Returns null if unknown. */
	public static Floor parse(String s) {
		if (s == null) return null;
		s = s.trim().toUpperCase();
		if (s.equals("E") || s.equals("ENTRANCE")) return E;
		for (Floor f : values()) if (f.name().equals(s)) return f;
		return null;
	}
}

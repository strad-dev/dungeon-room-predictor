package com.github.roompredictor.core;

/**
 * Feature layout for one candidate cell. Features are sparse: most are one-hot buckets.
 *
 * <p>The "soft" features carry the previous inference round's beliefs about other cells (e.g. how
 * much fairy probability sits 3 tiles away), which is how predictions for opened-adjacent "?"
 * rooms propagate to cells further out.
 */
public final class Features {
	public static final int BIAS = 0;
	public static final int BRACKET = 1;            // 6
	public static final int IS_UNKNOWN = 7;         // map shows "?"
	public static final int OBS_DOORS = 8;          // 4: 0,1,2,3+
	public static final int WITHER_DOOR = 12;
	public static final int BLOOD_DOOR = 13;
	public static final int WALLED = 14;            // 5: definite walls 0..4
	public static final int EDGE = 19;
	public static final int CORNER = 20;
	public static final int WALK_UNKNOWN = 21;
	public static final int FAIRY_KNOWN = 22;
	public static final int SOFT_END_ADJ = 23;      // end-room belief of direct neighbours
	public static final int MERGED_VISIBLE = 24;    // part of a visible multi-tile room
	public static final int EXTRA_COLUMN = 25;
	// Blood-rush path (Entrance -> wither doors -> normal door -> Fairy -> wither doors -> blood door -> Blood).
	public static final int PATH_FRONTIER = 26;     // the unopened room at the end of the known path
	public static final int FAIRY_CANDIDATE = 27;   // behind a normal door of the path's last room, Fairy unknown

	/**
	 * Distance clues, one block per floor size (distances differ between a 4x4 and a 6x6 dungeon,
	 * so each floor size learns its own). The constants below are positions within a block; use
	 * {@link #d(int, int)} for the actual index.
	 */
	public static final int DIST = 28;
	public static final int ENTR_MAN = 0;           // 13: manhattan to entrance 0..12+
	public static final int ENTR_WALK = 13;         // 13: walkable distance 0..12+ when known
	public static final int FAIRY_MAN = 26;         // 7: manhattan to found fairy 0..6+
	public static final int SOFT_BUCKETS = 5;       // manhattan 1,2,3,4,5+
	public static final int SOFT_FAIRY = 33;        // 5: fairy belief by manhattan distance
	public static final int SOFT_BLOOD = 38;        // 5: blood belief by manhattan distance
	public static final int FAIRY_WALK = 43;        // 7: walkable steps from the found Fairy 0..6+
	public static final int PATH_EST = 50;          // 13: estimated path steps from the Entrance 0..12+
	// "Tiles between" counting each room passed through by its full size (see MapGrid#roomBetween).
	public static final int ENTR_ROOM = 63;         // 13: from the Entrance 0..12+
	public static final int FAIRY_ROOM = 76;        // 7: from the found Fairy 0..6+
	public static final int DIST_BLOCK = 83;
	public static final int POS = DIST + Floor.BRACKETS * DIST_BLOCK; // 6 brackets x 36 cells
	public static final int DIM = POS + Floor.BRACKETS * Floor.MAX_DIM * Floor.MAX_DIM;

	/** Index of distance clue {@code offset} for floor size {@code bracket}. */
	public static int d(int bracket, int offset) {
		return DIST + bracket * DIST_BLOCK + offset;
	}

	private static final int MAX = 40;
	public final int[] index = new int[MAX];
	public final double[] value = new double[MAX];
	public int size;

	public void add(int i, double v) {
		if (v == 0) return;
		index[size] = i;
		value[size] = v;
		size++;
	}

	public void oneHot(int base, int buckets, int bucket) {
		add(base + Math.max(0, Math.min(buckets - 1, bucket)), 1);
	}

	public Features copy() {
		Features f = new Features();
		System.arraycopy(index, 0, f.index, 0, size);
		System.arraycopy(value, 0, f.value, 0, size);
		f.size = size;
		return f;
	}

	public double dot(double[] w) {
		double s = 0;
		for (int k = 0; k < size; k++) s += w[index[k]] * value[k];
		return s;
	}

	public static int posIndex(int bracket, int x, int z) {
		return POS + bracket * Floor.MAX_DIM * Floor.MAX_DIM + z * Floor.MAX_DIM + x;
	}
}

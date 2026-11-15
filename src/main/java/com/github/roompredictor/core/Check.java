package com.github.roompredictor.core;

/** The checkmark the map item draws on an opened room. */
public enum Check {
	/** Opened but not cleared. */
	NONE,
	/** White check: cleared. */
	WHITE,
	/** Green check: cleared with all secrets. */
	GREEN,
	/** Red cross: failed (puzzles). */
	FAILED;

	/** Map colours of the check icons, in priority order. */
	static Check fromIconColor(byte c, Tile tile) {
		if (c == 30 && tile != Tile.ENTRANCE) return GREEN;
		if (c == 34) return WHITE;
		if (c == 18 && tile != Tile.BLOOD) return FAILED;
		return null;
	}
}

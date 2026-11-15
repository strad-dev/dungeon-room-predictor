package com.github.roompredictor.core;

/** What the map shows between two orthogonally adjacent cells. */
public enum Link {
	NONE,
	/** Both cells belong to the same (multi-tile) room. */
	MERGED,
	DOOR,
	WITHER_DOOR,
	BLOOD_DOOR;

	public boolean isDoor() {
		return this == DOOR || this == WITHER_DOOR || this == BLOOD_DOOR;
	}

	/** Walkable: same room or a door between rooms. */
	public boolean walkable() {
		return this != NONE;
	}
}

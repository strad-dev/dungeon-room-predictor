package com.github.roompredictor.core;

/** What the dungeon map item shows for one grid cell. */
public enum Tile {
	/** Nothing drawn: either no room, or a room nobody has seen yet. */
	EMPTY,
	/** Grey "?" room: known to exist, not opened. */
	UNKNOWN,
	ENTRANCE,
	NORMAL,
	PUZZLE,
	TRAP,
	YELLOW,
	BLOOD,
	FAIRY;

	public boolean explored() {
		return this != EMPTY && this != UNKNOWN;
	}

	/** Tile colours of the vanilla map palette as Hypixel draws them. */
	public static Tile fromMapColor(byte c) {
		return switch (c) {
			case 0 -> EMPTY;
			case 85 -> UNKNOWN;
			case 30 -> ENTRANCE;
			case 63 -> NORMAL;
			case 66 -> PUZZLE;
			case 62 -> TRAP;
			case 74 -> YELLOW;
			case 18 -> BLOOD;
			case 82 -> FAIRY;
			default -> null;
		};
	}
}

package com.github.roompredictor.core;

/** Prediction classes for an unopened cell. */
public enum Label {
	NORMAL("Room", "N"),
	PUZZLE("Puzzle", "Pz"),
	TRAP("Trap", "Tr"),
	YELLOW("Yellow", "Y"),
	BLOOD("Blood", "B"),
	FAIRY("Fairy", "F"),
	RARE("Rare", "R"),
	/** Only possible in the extra puzzle column of F4-F6 / M4-M6. */
	EMPTY("Empty", "-");

	public static final Label[] ALL = values();
	public static final int COUNT = ALL.length;

	public final String display;
	public final String abbrev;

	Label(String display, String abbrev) {
		this.display = display;
		this.abbrev = abbrev;
	}

	/** 1x1 rooms with exactly one entrance. */
	public boolean isEndRoom() {
		return this == PUZZLE || this == TRAP || this == YELLOW || this == BLOOD || this == RARE;
	}

	/** The training label for a cell the map now shows as explored, or null if it teaches nothing. */
	public static Label fromTile(Tile t) {
		return switch (t) {
			case NORMAL -> NORMAL;
			case PUZZLE -> PUZZLE;
			case TRAP -> TRAP;
			case YELLOW -> YELLOW;
			case BLOOD -> BLOOD;
			case FAIRY -> FAIRY;
			default -> null;
		};
	}
}

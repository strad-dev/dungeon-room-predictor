package com.github.roompredictor.engine;

import com.github.roompredictor.core.Floor;
import com.github.roompredictor.core.MapGrid;

/**
 * One line of the map database ({@code maps.jsonl}).
 *
 * <p>Format, chosen to be small and readable: {@code map} is {@link MapGrid#encode()} - one char
 * per cell ({@code .}=empty {@code _}=? {@code E}ntrance {@code N}ormal {@code P}uzzle {@code T}rap
 * {@code Y}ellow {@code B}lood {@code F}airy), then the east links and the south links of every
 * cell ({@code .}=wall {@code M}=same room {@code D}=door {@code W}=wither door {@code B}=blood door).
 * {@code reveal} holds the second (from run start) each cell was first seen opened, or -1.
 */
public final class RunRecord {
	public long time;
	public String floor;
	public int puzzles;
	public String map;
	public int[] reveal;
	/** The predictor's Blood guess when the run started, and whether it was right. */
	public int bloodGuess = -1;
	public double bloodGuessP;
	public int bloodActual = -1;
	public long seconds;
	/** Room name per cell for opened rooms (from rooms.json), null where unknown. */
	public String[] names;
	/** Facing when joining (0 N .. 3 W) and the side the Entrance door turned out to be on, or -1. */
	public int spawnDir = -1;
	public int entranceDoor = -1;
	/** From the map's position: 0 no side puzzles, 1 at least one, -1 unknown / no extra column. */
	public int sidePuzzles = -1;

	public Floor floor() {
		return Floor.parse(floor);
	}

	public MapGrid grid() {
		Floor f = floor();
		if (f == null || map == null) return null;
		return MapGrid.decode(f, map);
	}
}

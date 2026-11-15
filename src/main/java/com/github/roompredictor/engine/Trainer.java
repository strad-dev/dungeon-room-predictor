package com.github.roompredictor.engine;

import com.github.roompredictor.core.*;

/**
 * Trains a {@link Model} from stored runs. A run is replayed in the order its rooms opened: at
 * each step the model predicts on the map as it looked just before, then learns from the rooms
 * that opened. Every update, and the run's shape counts, are weighted by how much of the map the
 * run opened, so partially opened maps still count, just less.
 */
public final class Trainer {
	private Trainer() {
	}

	/** Opened cells / room cells of the normal grid (the extra puzzle column doesn't count). */
	public static double openedFraction(MapGrid g) {
		int total = 0, open = 0;
		for (int i = 0; i < g.size(); i++) {
			if (g.floor.isExtraColumn(g.x(i))) continue;
			total++;
			if (g.tile(i).explored()) open++;
		}
		return total == 0 ? 0 : (double) open / total;
	}

	/** What an opened cell teaches. A normal-coloured 1x1 with a single door is a Rare room. */
	public static Label labelFor(MapGrid g, int cell, int puzzles) {
		Label l = Label.fromTile(g.tile(cell));
		if (l == Label.NORMAL && g.componentSize(cell) == 1 && g.roomDoorCount(cell) == 1 && puzzles >= 5) {
			return Label.RARE;
		}
		return l;
	}

	/** Replays one stored run into {@code m}. Returns the number of updates made. */
	public static int train(Model m, RunRecord r) {
		MapGrid fin = r.grid();
		if (fin == null || fin.entrance() < 0 || r.reveal == null || r.reveal.length != fin.size()) return 0;
		double w = openedFraction(fin);
		if (w <= 0) return 0;
		Floor floor = fin.floor;

		// What was known before the first room opened: the spawn guess, or nothing.
		MapGrid prev = null;
		if (r.spawnDir >= 0) prev = MapGrid.spawnGrid(floor, fin.x(fin.entrance()), fin.z(fin.entrance()), r.spawnDir);
		if (prev == null) prev = MapGrid.empty(floor);

		int updates = 0;
		for (int t : Replay.steps(r.reveal)) {
			Prediction p = Predictor.predict(prev, m, r.puzzles, -1, r.sidePuzzles);
			for (int i = 0; i < fin.size(); i++) {
				if (r.reveal[i] != t || p.features[i] == null) continue;
				Label label = labelFor(fin, i, r.puzzles);
				if (label == null) continue;
				m.update(p.features[i], p.masks[i], label, w);
				updates++;
			}
			prev = Replay.at(fin, r.reveal, t);
		}
		int[] shapes = Predictor.exploredShapeCounts(fin);
		m.recordShapes(floor.bracket(), shapes, w);
		// Big-room totals are only meaningful when every room was seen.
		if (w >= 0.999) {
			int rooms = 0, tiles = 0;
			for (Shape s : Shape.ALL) {
				if (s == Shape.S1x1) continue;
				rooms += shapes[s.ordinal()];
				tiles += shapes[s.ordinal()] * s.size();
			}
			m.recordBigRooms(floor.bracket(), rooms, tiles);
		}
		if (r.puzzles > 0) m.recordPuzzles(floor, r.puzzles);
		// This floor size's distances, from any map where the rooms were opened.
		int e = fin.entrance(), f = fin.find(Tile.FAIRY), b = fin.find(Tile.BLOOD);
		if (b >= 0) {
			int fb = f >= 0 ? fin.walkDistances(f)[b] : -1;
			int eb = e >= 0 ? fin.walkDistances(e)[b] : -1;
			m.recordDistances(floor.bracket(), fb, eb);
		}
		return updates;
	}
}

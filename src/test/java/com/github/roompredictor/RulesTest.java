package com.github.roompredictor;

import com.github.roompredictor.core.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RulesTest {
	/** The screenshot case: Entrance and Fairy side by side under a 2x2 that connects them. */
	@Test
	void twoByTwoCountsAllFourTilesBetween() {
		MapGrid g = new MapGrid.Builder(Floor.M7)
			.tile(1, 0, Tile.NORMAL).tile(2, 0, Tile.NORMAL).tile(1, 1, Tile.NORMAL).tile(2, 1, Tile.NORMAL)
			.east(1, 0, Link.MERGED).east(1, 1, Link.MERGED).south(1, 0, Link.MERGED).south(2, 0, Link.MERGED)
			.tile(1, 2, Tile.ENTRANCE).tile(2, 2, Tile.FAIRY)
			.south(1, 1, Link.DOOR).south(2, 1, Link.DOOR)
			.build();
		int fairy = g.idx(2, 2);
		assertEquals(2, g.walkDistances(g.entrance())[fairy] - 1, "shortest walk: 2 tiles between");
		assertEquals(4, g.roomBetween(g.entrance())[fairy], "whole rooms: the 2x2 counts 4");
	}

	@Test
	void f7BigRoomLimitsStopBigShapes() {
		// Seven opened big rooms on M7 (two 1x4, two 1x3... built as separate horizontal strips).
		MapGrid.Builder b = new MapGrid.Builder(Floor.M7);
		int[][] strips = {{0, 0, 4}, {0, 1, 4}, {0, 2, 3}, {0, 3, 3}, {0, 4, 2}, {0, 5, 2}, {4, 2, 2}};
		for (int[] st : strips) {
			for (int k = 0; k < st[2]; k++) {
				b.tile(st[0] + k, st[1], Tile.NORMAL);
				if (k > 0) b.east(st[0] + k - 1, st[1], Link.MERGED);
			}
		}
		b.tile(5, 0, Tile.ENTRANCE).tile(5, 1, Tile.UNKNOWN).south(5, 0, Link.DOOR);
		MapGrid g = b.build();
		Model m = Model.seeded();
		Prediction p = Predictor.predict(g, m, 5);
		double[] sh = p.shapes[g.idx(5, 1)];
		assertNotNull(sh);
		assertEquals(1.0, sh[Shape.S1x1.ordinal()], 1e-9, "7 big rooms already: only 1x1 left");
	}

	@Test
	void otherFloorsLearnBigRoomLimitsFromFullMaps() {
		Model m = Model.seeded();
		assertEquals(Integer.MAX_VALUE, m.bigRoomLimit(2), "unknown before any full maps");
		m.recordBigRooms(2, 4, 11);
		m.recordBigRooms(2, 5, 12);
		m.recordBigRooms(2, 3, 9);
		assertEquals(5, m.bigRoomLimit(2));
		assertEquals(12, m.bigTileLimit(2));
		assertEquals(32 / 3.0, m.bigTileTarget(2), 1e-9);
		assertEquals(Model.F7_BIG_ROOMS, m.bigRoomLimit(5));
	}

	@Test
	void openedWitherDoorsStayWitherDoors() {
		MapGrid before = new MapGrid.Builder(Floor.F7)
			.tile(0, 5, Tile.ENTRANCE).tile(0, 4, Tile.NORMAL).tile(0, 3, Tile.UNKNOWN)
			.south(0, 4, Link.DOOR).south(0, 3, Link.WITHER_DOOR).build();
		// After opening, the map item draws the wither door as a normal door.
		MapGrid after = new MapGrid.Builder(Floor.F7)
			.tile(0, 5, Tile.ENTRANCE).tile(0, 4, Tile.NORMAL).tile(0, 3, Tile.NORMAL)
			.south(0, 4, Link.DOOR).south(0, 3, Link.DOOR).build();
		MapGrid kept = after.rememberDoors(before);
		assertEquals(Link.WITHER_DOOR, kept.link(kept.idx(0, 3), 2));
		assertEquals(Link.DOOR, kept.link(kept.idx(0, 4), 2), "the Entrance door stays normal");
		assertEquals(Tile.NORMAL, kept.tile(kept.idx(0, 3)));
		assertSame(after, after.rememberDoors(after), "nothing to remember");
	}

	@Test
	void secondUnknownRoomIsNeverOnTheBloodPath() {
		// Entrance -> 1x4 along row 5... the path continues through a wither door into (3,3);
		// another "?" at (0,3) hangs off the 1x4 by a normal door: a different branch.
		MapGrid g = new MapGrid.Builder(Floor.F7)
			.tile(0, 5, Tile.ENTRANCE)
			.tile(0, 4, Tile.NORMAL).tile(1, 4, Tile.NORMAL).tile(2, 4, Tile.NORMAL).tile(3, 4, Tile.NORMAL)
			.east(0, 4, Link.MERGED).east(1, 4, Link.MERGED).east(2, 4, Link.MERGED).south(0, 4, Link.DOOR)
			.tile(3, 3, Tile.UNKNOWN).south(3, 3, Link.WITHER_DOOR)
			.tile(0, 3, Tile.UNKNOWN).south(0, 3, Link.DOOR)
			.build();
		Prediction p = Predictor.predict(g, Model.seeded(), 4);
		int other = g.idx(0, 3), frontier = g.idx(3, 3);
		assertEquals(0, p.labels[other][Label.BLOOD.ordinal()], 1e-12);
		assertEquals(0, p.labels[other][Label.FAIRY.ordinal()], 1e-12, "a second ? is never on the path");
		assertEquals(0, p.labels[frontier][Label.FAIRY.ordinal()], 1e-12, "Fairy's door in is normal, not wither");
		double blood = 0, fairy = 0;
		for (double[] l : p.labels) if (l != null) {
			blood += l[Label.BLOOD.ordinal()];
			fairy += l[Label.FAIRY.ordinal()];
		}
		assertEquals(1, blood, 0.02);
		assertEquals(1, fairy, 0.02);
	}

	@Test
	void lastUnknownRoomWithBlankCellsIsNeverOneByOne() {
		// Full M7 map with one 1x3 (Wizard, (2,3)-(2,5)) not yet opened: its door tile shows as "?",
		// the other two tiles are blank. That "?" can't be a 1x1 or a special room.
		MapGrid full = TestMaps.fullM7();
		MapGrid.Builder b = new MapGrid.Builder(Floor.M7);
		for (int i = 0; i < full.size(); i++) {
			int x = full.x(i), z = full.z(i);
			boolean wizard = x == 2 && z >= 3;
			if (!wizard) b.tile(x, z, full.tile(i));
			if (!wizard && x + 1 < 6 && !(x + 1 == 2 && z >= 3)) b.east(x, z, full.link(i, 1));
			if (!wizard && z + 1 < 6) b.south(x, z, full.link(i, 2));
		}
		b.tile(2, 3, Tile.UNKNOWN).east(2, 3, Link.DOOR); // Wizard's door to Fairy at (3,3)
		MapGrid g = b.build();
		assertEquals(g.idx(2, 3), Predictor.soleUnknownWithBlanks(g));
		Prediction p = Predictor.predict(g, Model.seeded(), 2);
		int q = g.idx(2, 3);
		assertEquals(1.0, p.labels[q][Label.NORMAL.ordinal()], 1e-9);
		assertEquals(0, p.shapes[q][Shape.S1x1.ordinal()], 1e-12);
		assertTrue(p.shapes[q][Shape.S1x3.ordinal()] > 0, "the real shape is still possible");
	}

	@Test
	void tabUndiscoveredPuzzleCountCapsPuzzleGuesses() {
		MapGrid g = MapGrid.spawnGrid(Floor.M7, 3, 5, 0);
		double withoutTab = 0, withTab = 0;
		Prediction a = Predictor.predict(g, Model.seeded(), 5);
		Prediction b = Predictor.predict(g, Model.seeded(), 5, 2);
		for (int i = 0; i < g.size(); i++) {
			if (a.labels[i] != null) withoutTab += a.labels[i][Label.PUZZLE.ordinal()];
			if (b.labels[i] != null) withTab += b.labels[i][Label.PUZZLE.ordinal()];
		}
		assertEquals(5, withoutTab, 0.1);
		assertEquals(2, withTab, 0.1, "tab says only 2 puzzles are still undiscovered");
	}
}

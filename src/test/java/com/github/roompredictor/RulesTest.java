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
}

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

	@Test
	void completionFillsTheMapWithinTheRules() {
		MapGrid g = MapGrid.spawnGrid(Floor.M7, 3, 5, 0);
		Prediction p = Predictor.predict(g, Model.seeded(), 5);
		int[] specials = new int[Label.COUNT];
		int bigRooms = 0, bigTiles = 0;
		int[] perShape = new int[Shape.COUNT];
		for (int i = 0; i < g.size(); i++) {
			if (g.tile(i).explored()) {
				assertEquals(-1, p.roomOf[i]);
				continue;
			}
			assertTrue(p.roomOf[i] >= 0, "every unopened cell is part of a predicted room");
		}
		for (int r = 0; r < p.roomLabel.length; r++) {
			if (p.roomLabel[r] != Label.NORMAL) specials[p.roomLabel[r].ordinal()]++;
			perShape[p.roomShape[r].ordinal()]++;
			if (p.roomShape[r] != Shape.S1x1) {
				bigRooms++;
				bigTiles += p.roomShape[r].size();
			}
			int size = 0;
			for (int c : p.roomOf) if (c == r) size++;
			assertEquals(p.roomShape[r].size(), size, "room " + r + " covers its shape");
		}
		assertTrue(specials[Label.BLOOD.ordinal()] <= 1 && specials[Label.FAIRY.ordinal()] <= 1);
		assertTrue(specials[Label.PUZZLE.ordinal()] <= 5);
		assertTrue(bigRooms <= Model.F7_BIG_ROOMS, "big rooms " + bigRooms);
		assertTrue(bigTiles <= Model.F7_BIG_TILES_MAX, "big tiles " + bigTiles);
		for (Shape sh : Shape.ALL) if (sh != Shape.S1x1) assertTrue(perShape[sh.ordinal()] <= 2, sh + " x" + perShape[sh.ordinal()]);
		assertTrue(bigRooms >= 3, "it actually predicts big rooms: " + bigRooms);
	}

	@Test
	void openingBloodRedrawsThePathAsWitherDoors() {
		// The fixture's path: Entrance (3,5) -> Red Blue (3,4) -> Fairy (3,3) -> Deathmite (3,2) -> Blood (3,1).
		// Pretend the map only shows normal doors everywhere (opened before the mod saw them).
		MapGrid full = TestMaps.fullM7();
		MapGrid.Builder b = new MapGrid.Builder(Floor.M7);
		for (int i = 0; i < full.size(); i++) {
			int x = full.x(i), z = full.z(i);
			b.tile(x, z, full.tile(i));
			Link e = full.link(i, 1), s = full.link(i, 2);
			b.east(x, z, e.isDoor() ? Link.DOOR : e);
			b.south(x, z, s.isDoor() ? Link.DOOR : s);
		}
		MapGrid g = b.build().markRushPath();
		assertEquals(Link.WITHER_DOOR, g.link(g.idx(3, 5), 0), "Entrance -> Red Blue");
		assertEquals(Link.DOOR, g.link(g.idx(3, 4), 0), "the door into Fairy stays normal");
		assertEquals(Link.WITHER_DOOR, g.link(g.idx(3, 3), 0), "Fairy -> Deathmite");
		assertEquals(Link.BLOOD_DOOR, g.link(g.idx(3, 2), 0), "the door into Blood");
		assertEquals(Link.DOOR, g.link(g.idx(0, 0), 2), "doors off the path are untouched");
	}

	@Test
	void boxedInUnknownRoomIsCertainlyOneByOne() {
		// "?" at (1,5): opened rooms left, right and above, map edge below.
		MapGrid g = new MapGrid.Builder(Floor.M7)
			.tile(0, 5, Tile.PUZZLE).tile(2, 5, Tile.NORMAL).tile(3, 5, Tile.ENTRANCE).tile(1, 4, Tile.NORMAL)
			.east(2, 5, Link.DOOR).south(1, 4, Link.DOOR)
			.tile(1, 5, Tile.UNKNOWN).east(1, 5, Link.DOOR)
			.build();
		Prediction p = Predictor.predict(g, Model.seeded(), 5);
		int q = g.idx(1, 5);
		int r = p.roomOf[q];
		assertEquals(Shape.S1x1, p.roomShape[r]);
		assertEquals(1.0, p.roomShapeP[r], 1e-9, "no other shape can fit");
	}

	@Test
	void navFindsTheDoorsBetweenPlayerAndBlood() {
		MapGrid full = TestMaps.fullM7();
		int blood = full.knownBlood();
		assertEquals(full.idx(3, 1), blood);
		// From the Entrance: Entrance -> Red Blue -> Fairy -> Deathmite -> Blood = 4 doors.
		java.util.List<int[]> doors = full.routeDoors(full.entrance(), blood);
		assertEquals(4, doors.size());
		// From inside Blood there's nothing to walk through.
		assertTrue(full.routeDoors(blood, blood).isEmpty());
		// Before Blood is opened, the "?" behind the red blood door is the target.
		MapGrid g = Replay.at(full, TestMaps.revealOrder(full), 30);
		if (g.tile(full.idx(3, 2)).explored()) assertEquals(full.idx(3, 1), g.knownBlood());
	}

	@Test
	void onlyWayIntoBlankCellsIsNeverAnEndRoom() {
		// Entrance + a 1x2 open; one "?" at (2,4); everything else blank and reachable only through it.
		MapGrid g = new MapGrid.Builder(Floor.M7)
			.tile(0, 5, Tile.ENTRANCE).tile(1, 5, Tile.NORMAL).tile(2, 5, Tile.NORMAL)
			.east(0, 5, Link.DOOR).east(1, 5, Link.MERGED)
			.tile(2, 4, Tile.UNKNOWN).south(2, 4, Link.DOOR)
			.build();
		assertTrue(Predictor.mustLeadOnward(g)[g.idx(2, 4)]);
		Prediction p = Predictor.predict(g, Model.seeded(), 5);
		double[] l = p.labels[g.idx(2, 4)];
		for (Label k : Label.ALL) if (k.isEndRoom()) assertEquals(0, l[k.ordinal()], 1e-12, k + " is impossible");
	}

	@Test
	void sharedBlankAreaDoesNotForceEitherUnknown() {
		// Two "?" that can both reach the same blank area: neither is forced.
		MapGrid g = new MapGrid.Builder(Floor.M7)
			.tile(0, 5, Tile.ENTRANCE).tile(1, 5, Tile.NORMAL).tile(2, 5, Tile.NORMAL)
			.east(0, 5, Link.DOOR).east(1, 5, Link.MERGED)
			.tile(1, 4, Tile.UNKNOWN).south(1, 4, Link.DOOR)
			.tile(2, 4, Tile.UNKNOWN).south(2, 4, Link.DOOR)
			.build();
		boolean[] m = Predictor.mustLeadOnward(g);
		assertFalse(m[g.idx(1, 4)] || m[g.idx(2, 4)]);
	}

	@Test
	void boxedInUnknownForcesTheOtherToLeadOnward() {
		// Like the screenshot: "?" at (1,5) boxed in by opened rooms and the edge; "?" at (3,4) next
		// to blank cells above it. The boxed one reaches no blank cell, so (3,4) is the only way in.
		MapGrid.Builder b = new MapGrid.Builder(Floor.M7);
		for (int z = 0; z < 6; z++) for (int x = 0; x < 6; x++) {
			boolean blank = z <= 3 && x >= 3; // a blank block top-right, reachable only from (3,4)
			if (!blank) b.tile(x, z, Tile.NORMAL);
		}
		b.tile(0, 5, Tile.ENTRANCE).tile(1, 5, Tile.UNKNOWN).tile(3, 4, Tile.UNKNOWN);
		b.east(0, 5, Link.DOOR).south(3, 4, Link.DOOR);
		MapGrid g = b.build();
		boolean[] m = Predictor.mustLeadOnward(g);
		assertFalse(m[g.idx(1, 5)], "the boxed-in ? reaches nothing");
		assertTrue(m[g.idx(3, 4)], "the other ? is the only way into the blank cells");
		Prediction p = Predictor.predict(g, Model.seeded(), 5);
		for (Label k : Label.ALL) if (k.isEndRoom()) assertEquals(0, p.labels[g.idx(3, 4)][k.ordinal()], 1e-12);
		int boxed = p.roomOf[g.idx(1, 5)];
		assertEquals(Shape.S1x1, p.roomShape[boxed]);
	}

	@Test
	void boxedInOneDoorUnknownIsCertainlyAnEndRoom() {
		// Like the screenshot: "?" at (4,5) between two opened rooms, Blood above, edge below,
		// with its single door into the room on the left.
		MapGrid g = new MapGrid.Builder(Floor.M7)
			.tile(0, 5, Tile.ENTRANCE).tile(1, 5, Tile.NORMAL).tile(2, 5, Tile.NORMAL).tile(3, 5, Tile.NORMAL)
			.east(0, 5, Link.DOOR).east(1, 5, Link.MERGED).east(2, 5, Link.MERGED)
			.tile(5, 5, Tile.NORMAL).tile(4, 4, Tile.BLOOD)
			.tile(4, 5, Tile.UNKNOWN).east(3, 5, Link.DOOR)
			.build();
		Prediction p = Predictor.predict(g, Model.seeded(), 5);
		double[] l = p.labels[g.idx(4, 5)];
		double end = 0;
		for (Label k : Label.ALL) if (k.isEndRoom()) end += l[k.ordinal()];
		assertEquals(1.0, end, 1e-9, "End 100%");
		assertEquals(0, l[Label.NORMAL.ordinal()], 1e-12);
		assertEquals(0, l[Label.FAIRY.ordinal()], 1e-12);
	}

	@Test
	void twoUnknownTilesAreNeverOneRoom() {
		// Two opened rooms, already connected, each with a door to a "?": (3,0) and (3,1) side by side.
		MapGrid g = new MapGrid.Builder(Floor.M7)
			.tile(0, 0, Tile.NORMAL).tile(1, 0, Tile.NORMAL).tile(2, 0, Tile.NORMAL)
			.east(0, 0, Link.MERGED).east(1, 0, Link.MERGED)
			.tile(2, 1, Tile.ENTRANCE).south(2, 0, Link.DOOR)
			.tile(3, 0, Tile.UNKNOWN).east(2, 0, Link.DOOR)
			.tile(3, 1, Tile.UNKNOWN).east(2, 1, Link.DOOR)
			.build();
		Prediction p = Predictor.predict(g, Model.seeded(), 5);
		assertNotEquals(p.roomOf[g.idx(3, 0)], p.roomOf[g.idx(3, 1)], "two \"?\" tiles are two rooms");
		for (int r = 0; r < p.roomLabel.length; r++) {
			int unknowns = 0;
			for (int c = 0; c < g.size(); c++) if (p.roomOf[c] == r && g.tile(c) == Tile.UNKNOWN) unknowns++;
			assertTrue(unknowns <= 1, "room " + r + " has " + unknowns + " ? tiles");
		}
	}

	@Test
	void navFallsBackToTheNextUnopenedWitherDoor() {
		MapGrid full = TestMaps.fullM7();
		int[] reveal = TestMaps.revealOrder(full);
		// Step where Fairy (3,3) is open but Deathmite behind its wither door isn't.
		MapGrid g = null;
		for (int t : Replay.steps(reveal)) {
			MapGrid at = Replay.at(full, reveal, t);
			if (at.tile(at.idx(3, 3)).explored() && !at.tile(at.idx(3, 2)).explored()) {
				g = at;
				break;
			}
		}
		assertNotNull(g);
		assertEquals(-1, g.knownBlood(), "Blood isn't known yet");
		assertTrue(g.unopenedWitherRooms().contains(g.idx(3, 3)), "Fairy is in front of the unopened wither door");
		assertFalse(g.routeDoors(g.entrance(), g.idx(3, 3)).isEmpty());
	}

	@Test
	void predictedDoorsJoinTheLayoutIntoATree() {
		MapGrid g = MapGrid.spawnGrid(Floor.M7, 3, 5, 0);
		Prediction p = Predictor.predict(g, Model.seeded(), 5);
		int rooms = p.roomLabel.length;
		// Union-find over predicted rooms through the predicted doors.
		int[] parent = new int[rooms];
		for (int r = 0; r < rooms; r++) parent[r] = r;
		java.util.function.IntUnaryOperator find = new java.util.function.IntUnaryOperator() {
			public int applyAsInt(int x) { while (parent[x] != x) x = parent[x] = parent[parent[x]]; return x; }
		};
		int[] doorsPerRoom = new int[rooms];
		for (int[] d : p.roomDoors) {
			int a = p.roomOf[d[0]], b = p.roomOf[g.neighbor(d[0], d[1])];
			assertNotEquals(a, b);
			int ra = find.applyAsInt(a), rb = find.applyAsInt(b);
			assertNotEquals(ra, rb, "a predicted door would close a loop");
			parent[ra] = rb;
			doorsPerRoom[a]++;
			doorsPerRoom[b]++;
		}
		assertEquals(rooms - 1, p.roomDoors.size(), "one tree: every predicted room connected, no loops");
		for (int r = 0; r < rooms; r++) {
			if (p.roomLabel[r].isEndRoom()) assertTrue(doorsPerRoom[r] <= 1, "end room " + r + " has " + doorsPerRoom[r] + " doors");
		}
	}

	@Test
	void twoNeighbouringUnknownsBoxedInAreBothEndRooms() {
		// Like the screenshot: everything open except "?" at (4,5) and (5,5), side by side in the
		// bottom-right corner, each with one door into the opened room above it.
		MapGrid.Builder b = new MapGrid.Builder(Floor.M7);
		for (int z = 0; z < 6; z++) for (int x = 0; x < 6; x++) b.tile(x, z, Tile.NORMAL);
		b.tile(0, 0, Tile.ENTRANCE).tile(4, 5, Tile.UNKNOWN).tile(5, 5, Tile.UNKNOWN);
		b.south(4, 4, Link.DOOR).south(5, 4, Link.DOOR);
		MapGrid g = b.build();
		Prediction p = Predictor.predict(g, Model.seeded(), 5);
		for (int q : new int[]{g.idx(4, 5), g.idx(5, 5)}) {
			double end = 0;
			for (Label k : Label.ALL) if (k.isEndRoom()) end += p.labels[q][k.ordinal()];
			assertEquals(1.0, end, 1e-9, "cell " + q + " is End 100%");
		}
	}
}

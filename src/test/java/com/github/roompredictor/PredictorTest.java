package com.github.roompredictor;

import com.github.roompredictor.core.*;
import com.github.roompredictor.engine.PredictorService;
import com.github.roompredictor.engine.RunRecord;
import com.github.roompredictor.engine.Storage;
import com.github.roompredictor.engine.Trainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PredictorTest {
	@Test
	void decodesPaintedMap() {
		MapGrid full = TestMaps.fullM7();
		byte[] colors = TestMaps.paint(full);
		MapDecoder.Geometry g = MapDecoder.geometry(colors, Floor.M7);
		assertNotNull(g);
		assertEquals(16, g.roomSize());
		assertEquals(6, g.offsetX());
		assertEquals(6, g.offsetZ());
		MapGrid decoded = MapDecoder.decode(colors, Floor.M7, g);
		assertTrue(decoded.sameAs(full), () -> "\n" + decoded.encode() + "\n" + full.encode());
	}

	@Test
	void decodesCheckmarksOverWholeRooms() {
		MapGrid full = TestMaps.fullM7();
		byte[] colors = TestMaps.paint(full);
		// A white check in the middle of Red Blue's first tile (3,4); room size 16, origin 6, pitch 20.
		for (int z = 6 + 4 * 20 + 6; z < 6 + 4 * 20 + 10; z++) for (int x = 6 + 3 * 20 + 6; x < 6 + 3 * 20 + 10; x++) colors[z * 128 + x] = 34;
		MapDecoder.Geometry g = MapDecoder.geometry(colors, Floor.M7);
		MapGrid d = MapDecoder.decode(colors, Floor.M7, g);
		assertEquals(Check.WHITE, d.check(d.idx(3, 4)));
		assertEquals(Check.WHITE, d.check(d.idx(5, 4)), "spread to the rest of the 1x3");
		assertEquals(Check.NONE, d.check(d.idx(3, 5)), "the Entrance's own green is not a check");
		assertEquals(Check.NONE, d.check(d.idx(3, 1)), "Blood's own red is not a failure");
		assertTrue(MapGrid.decode(Floor.M7, d.encode()).sameAs(d), "checks survive the map database format");
	}

	@Test
	void spawnGridMatchesTheStartingMap() {
		MapGrid full = TestMaps.fullM7();
		MapGrid start = Replay.at(full, TestMaps.revealOrder(full), 0);
		// Fixture Entrance is (3,5) with its door to the north.
		MapGrid spawn = MapGrid.spawnGrid(Floor.M7, 3, 5, 0);
		assertNotNull(spawn);
		assertTrue(spawn.sameAs(start), spawn.encode() + " vs " + start.encode());
		assertEquals(0, full.entranceDoorDir());
		assertNull(MapGrid.spawnGrid(Floor.M7, 6, 0, 1), "off-grid spawn");
	}

	@Test
	void serviceUsesSpawnBeforeTheMapArrives(@TempDir Path dir) throws Exception {
		PredictorService s = new PredictorService(new Storage(dir), m -> {});
		MapGrid full = TestMaps.fullM7();
		int[] reveal = TestMaps.revealOrder(full);
		s.submit(new PredictorService.Observation(7, 1000, Floor.M7, null, 5, -1, null, 3, 5, 0));
		barrier(s);
		Prediction pre = s.latest();
		assertEquals(Tile.ENTRANCE, pre.grid.tile(pre.grid.idx(3, 5)));
		assertEquals(Tile.UNKNOWN, pre.grid.tile(pre.grid.idx(3, 4)));
		// Then the map item shows up; the Entrance must count as revealed at that moment.
		s.submit(new PredictorService.Observation(7, 3000, Floor.M7, TestMaps.paint(Replay.at(full, reveal, 0)), 5, -1, null, 3, 5, 0));
		barrier(s);
		s.submit(new PredictorService.Observation(7, 90_000, Floor.M7, TestMaps.paint(full), 5, -1, null, 3, 5, 0));
		barrier(s);
		s.endRun();
		barrier(s);
		RunRecord r = s.runs().getFirst();
		assertTrue(r.reveal[full.idx(3, 5)] >= 0, "Entrance has a reveal time");
		assertEquals(0, r.spawnDir);
		assertEquals(0, r.entranceDoor);
		assertTrue(r.bloodGuess >= 0, "a Blood guess was made before the map arrived");
		s.shutdown();
	}

	@Test
	void decodesSmallerFloorGeometry() {
		MapGrid g = new MapGrid.Builder(Floor.E).tile(1, 3, Tile.ENTRANCE).tile(1, 2, Tile.UNKNOWN)
			.south(1, 2, Link.DOOR).build();
		byte[] colors = TestMaps.paint(g);
		MapDecoder.Geometry geo = MapDecoder.geometry(colors, Floor.E);
		assertNotNull(geo);
		assertEquals(18, geo.roomSize());
		assertTrue(MapDecoder.decode(colors, Floor.E, geo).sameAs(g));
	}

	@Test
	void encodeRoundTrip() {
		MapGrid full = TestMaps.fullM7();
		assertTrue(MapGrid.decode(Floor.M7, full.encode()).sameAs(full));
	}

	@Test
	void startOfRunProbabilitiesAreConsistent() {
		MapGrid full = TestMaps.fullM7();
		MapGrid start = Replay.at(full, TestMaps.revealOrder(full), 0);
		assertEquals(Tile.ENTRANCE, start.tile(start.idx(3, 5)));
		assertEquals(Tile.UNKNOWN, start.tile(start.idx(3, 4)));
		Prediction p = Predictor.predict(start, Model.seeded(), 5);
		double blood = 0, puzzles = 0;
		for (int i = 0; i < start.size(); i++) {
			if (p.labels[i] == null) continue;
			double s = 0;
			for (double v : p.labels[i]) {
				assertTrue(v >= -1e-9 && v <= 1 + 1e-9);
				s += v;
			}
			assertEquals(1, s, 1e-6);
			blood += p.labels[i][Label.BLOOD.ordinal()];
			puzzles += p.labels[i][Label.PUZZLE.ordinal()];
		}
		assertEquals(1, blood, 0.02, "exactly one Blood is still missing");
		assertEquals(5, puzzles, 0.1, "five puzzles are still missing");
		assertTrue(p.bloodCell >= 0, "a Blood guess exists with almost no information");
		assertNotEquals(start.idx(3, 4), p.bloodCell, "Blood is not adjacent to the Entrance");
	}

	@Test
	void zeroInformationStillGuessesBlood() {
		Prediction p = Predictor.predict(MapGrid.empty(Floor.F7), Model.seeded(), 0);
		assertTrue(p.bloodCell >= 0);
		assertTrue(p.bloodProbability > 0);
	}

	@Test
	void bloodDoorPinsBlood() {
		MapGrid full = TestMaps.fullM7();
		int[] reveal = TestMaps.revealOrder(full);
		// The step where Deathmite (behind the wither door) is open: Blood shows as "?" behind a red door.
		MapGrid g = null;
		for (int t : Replay.steps(reveal)) {
			MapGrid at = Replay.at(full, reveal, t);
			if (at.tile(at.idx(3, 2)).explored()) {
				g = at;
				break;
			}
		}
		assertNotNull(g);
		assertEquals(Tile.UNKNOWN, g.tile(g.idx(3, 1)));
		Prediction p = Predictor.predict(g, Model.seeded(), 5);
		assertTrue(p.labels[g.idx(3, 1)][Label.BLOOD.ordinal()] > 0.99);
		assertEquals(g.idx(3, 1), p.bloodCell);
	}

	@Test
	void fairyPullsBloodToDistanceTwoOrFour() {
		MapGrid.Builder b = new MapGrid.Builder(Floor.F7)
			.tile(3, 5, Tile.ENTRANCE).tile(3, 4, Tile.NORMAL).tile(3, 3, Tile.FAIRY)
			.south(3, 4, Link.DOOR).south(3, 3, Link.DOOR);
		MapGrid g = b.build();
		Prediction p = Predictor.predict(g, Model.seeded(), 4);
		int fairy = g.idx(3, 3);
		double near = 0;
		for (int i = 0; i < g.size(); i++) {
			if (p.labels[i] == null) continue;
			int d = g.manhattan(i, fairy);
			if (d == 2 || d == 4) near += p.labels[i][Label.BLOOD.ordinal()];
		}
		assertTrue(near > 0.75, "Blood mass at manhattan 2 or 4 from Fairy: " + near);
	}

	@Test
	void shortM7RushStillFollowsFairyRule() {
		// The M7 case: Fairy 3 steps from the Entrance, so Blood is only 7 steps out. The Fairy rule
		// must still win over the usual "Blood is 10-11 steps away" preference.
		MapGrid g = new MapGrid.Builder(Floor.M7)
			.tile(0, 5, Tile.ENTRANCE).tile(0, 4, Tile.NORMAL).tile(0, 3, Tile.NORMAL).tile(1, 3, Tile.FAIRY)
			.south(0, 4, Link.DOOR).south(0, 3, Link.MERGED).east(0, 3, Link.DOOR).build();
		assertEquals(3, g.walkDistances(g.entrance())[g.idx(1, 3)]);
		Prediction p = Predictor.predict(g, Model.seeded(), 5);
		int fairy = g.idx(1, 3);
		double near = 0;
		for (int i = 0; i < g.size(); i++) {
			if (p.labels[i] == null) continue;
			int d = g.manhattan(i, fairy);
			if (d == 2 || d == 4) near += p.labels[i][Label.BLOOD.ordinal()];
		}
		assertTrue(near > 0.75, "Blood mass at manhattan 2 or 4 from Fairy: " + near);
	}

	@Test
	void pathRulesPlaceFairyAndBlood() {
		// The path so far: Entrance -> a 1x4. A "?" behind a WITHER door is the next path room
		// (not Fairy). Behind a NORMAL door, with no wither door out of the 1x4, it must be Fairy.
		int q = MapGrid.empty(Floor.F7).idx(3, 3);
		Prediction viaWither = Predictor.predict(row4Map(Link.WITHER_DOOR), Model.seeded(), 4);
		Prediction viaNormal = Predictor.predict(row4Map(Link.DOOR), Model.seeded(), 4);
		double fairyWither = viaWither.labels[q][Label.FAIRY.ordinal()];
		double fairyNormal = viaNormal.labels[q][Label.FAIRY.ordinal()];
		assertTrue(fairyNormal > 0.4, "fairy behind the only normal door: " + fairyNormal);
		assertTrue(fairyWither < 0.1, "fairy behind a wither door: " + fairyWither);
		assertEquals(0, viaNormal.labels[q][Label.BLOOD.ordinal()], 1e-12, "Blood's only door is red");
		assertEquals(0, viaWither.labels[q][Label.BLOOD.ordinal()], 1e-12);
		// A likely Fairy pulls Blood to cells 2 or 4 away from it.
		assertTrue(bloodNear(row4Map(Link.DOOR), q) > bloodNear(row4Map(Link.WITHER_DOOR), q));
	}

	@Test
	void predictsWitherDoorsAlongTheRestOfThePath() {
		MapGrid full = TestMaps.fullM7();
		int[] reveal = TestMaps.revealOrder(full);
		// Entrance and Red Blue open; Fairy is the "?" north of Red Blue's west tile.
		MapGrid g = Replay.at(full, reveal, 10);
		assertEquals(Tile.UNKNOWN, g.tile(g.idx(3, 3)));
		Prediction p = Predictor.predict(g, Model.seeded(), 5);
		double sum = 0;
		for (int k = 0; k < 2; k++) {
			for (int i = 0; i < g.size(); i++) {
				double v = p.witherDoors[k][i];
				assertTrue(v >= 0 && v <= 1 + 1e-9);
				if (v > 0) assertEquals(Link.NONE, g.link(i, k == 0 ? 1 : 2), "only doors the map doesn't show");
				sum += v;
			}
		}
		assertTrue(sum > 0.5, "some wither doors predicted: " + sum);
		// The real path continues (3,3) Fairy -> wither -> (3,2) -> blood door -> (3,1).
		assertTrue(p.witherDoors[1][g.idx(3, 2)] > 0.1, "door between (3,2) and (3,3): " + p.witherDoors[1][g.idx(3, 2)]);
	}

	@Test
	void partialMapsTrainWithLessWeight() {
		MapGrid full = TestMaps.fullM7();
		int[] reveal = TestMaps.revealOrder(full);
		assertEquals(1.0, Trainer.openedFraction(full), 1e-9);
		MapGrid half = Replay.at(full, reveal, 30);
		double frac = Trainer.openedFraction(half);
		assertTrue(frac > 0.2 && frac < 0.9, "fraction " + frac);

		RunRecord fullRun = run(full, reveal);
		int[] cut = reveal.clone();
		for (int i = 0; i < cut.length; i++) if (cut[i] > 30) cut[i] = -1;
		RunRecord partialRun = run(half, cut);

		Model a = Model.seeded(), b = Model.seeded();
		assertTrue(Trainer.train(a, fullRun) > 0);
		assertTrue(Trainer.train(b, partialRun) > 0);
		// Same first example, smaller step for the partial run.
		Model a1 = Model.seeded(), b1 = Model.seeded();
		Prediction p = Predictor.predict(MapGrid.spawnGrid(Floor.M7, 3, 5, 0), a1, 5);
		int cell = full.idx(3, 4);
		a1.update(p.features[cell], p.masks[cell], Label.NORMAL, 1.0);
		b1.update(p.features[cell], p.masks[cell], Label.NORMAL, frac);
		assertTrue(Math.abs(b1.weights[0][0] - Model.seeded().weights[0][0]) < Math.abs(a1.weights[0][0] - Model.seeded().weights[0][0]));
	}

	@Test
	void freshModelIsRebuiltFromStoredMaps(@TempDir Path dir) throws Exception {
		PredictorService s = new PredictorService(new Storage(dir), m -> {});
		MapGrid full = TestMaps.fullM7();
		int[] reveal = TestMaps.revealOrder(full);
		for (int step : Replay.steps(reveal)) {
			s.submit(new PredictorService.Observation(1, 1000 + step * 1000L, Floor.M7, TestMaps.paint(Replay.at(full, reveal, step)), 5, -1, null, 3, 5, 0));
			barrier(s);
		}
		s.endRun();
		barrier(s);
		s.shutdown();
		java.nio.file.Files.delete(dir.resolve("model.json"));

		CountDownLatch rebuilt = new CountDownLatch(1);
		PredictorService s2 = new PredictorService(new Storage(dir), m -> {
			if (m.startsWith("Model rebuilt")) rebuilt.countDown();
		});
		assertTrue(rebuilt.await(20, TimeUnit.SECONDS), "rebuild finished");
		assertTrue(java.nio.file.Files.isRegularFile(dir.resolve("model.json")));
		s2.shutdown();
	}

	private static RunRecord run(MapGrid g, int[] reveal) {
		RunRecord r = new RunRecord();
		r.floor = g.floor.name();
		r.puzzles = 5;
		r.map = g.encode();
		r.reveal = reveal;
		r.spawnDir = 0;
		return r;
	}

	private static MapGrid row4Map(Link toUnknown) {
		return new MapGrid.Builder(Floor.F7)
			.tile(0, 5, Tile.ENTRANCE)
			.tile(0, 4, Tile.NORMAL).tile(1, 4, Tile.NORMAL).tile(2, 4, Tile.NORMAL).tile(3, 4, Tile.NORMAL)
			.east(0, 4, Link.MERGED).east(1, 4, Link.MERGED).east(2, 4, Link.MERGED)
			.south(0, 4, Link.DOOR)
			.tile(3, 3, Tile.UNKNOWN).south(3, 3, toUnknown)
			.build();
	}

	private static double bloodNear(MapGrid g, int cell) {
		Prediction p = Predictor.predict(g, Model.seeded(), 4);
		double s = 0;
		for (int i = 0; i < g.size(); i++) {
			if (p.labels[i] == null || i == cell) continue;
			int d = g.manhattan(i, cell);
			if (d == 2 || d == 4) s += p.labels[i][Label.BLOOD.ordinal()];
		}
		return s;
	}

	@Test
	void multiTileUnknownIsNormalAndShapesRespectCaps() {
		MapGrid full = TestMaps.fullM7();
		Prediction p = Predictor.predict(Replay.at(full, TestMaps.revealOrder(full), 20), Model.seeded(), 5);
		for (int i = 0; i < p.grid.size(); i++) {
			if (p.shapes[i] == null) continue;
			double s = 0;
			for (double v : p.shapes[i]) s += v;
			assertEquals(1, s, 1e-6);
		}
		// The full fixture already has two 1x3s explored -> a third 1x3 gets no probability.
		int[] counts = Predictor.exploredShapeCounts(full);
		assertTrue(counts[Shape.S1x3.ordinal()] >= 2);
	}

	@Test
	void onlineLearningReducesLoss() {
		Model m = Model.seeded();
		MapGrid g = Replay.at(TestMaps.fullM7(), TestMaps.revealOrder(TestMaps.fullM7()), 0);
		Prediction p = Predictor.predict(g, m, 5);
		int cell = g.idx(3, 4);
		Features f = p.features[cell];
		boolean[] mask = p.masks[cell];
		double first = m.update(f, mask, Label.FAIRY);
		double last = first;
		for (int i = 0; i < 30; i++) last = m.update(f, mask, Label.FAIRY);
		assertTrue(last < first * 0.5, first + " -> " + last);
		assertEquals(31, m.updates);
	}

	@Test
	void predictionIsFast() {
		Model m = Model.seeded();
		MapGrid g = Replay.at(TestMaps.fullM7(), TestMaps.revealOrder(TestMaps.fullM7()), 30);
		for (int i = 0; i < 50; i++) Predictor.predict(g, m, 5);
		long t = System.nanoTime();
		int n = 200;
		for (int i = 0; i < n; i++) Predictor.predict(g, m, 5);
		double ms = (System.nanoTime() - t) / 1e6 / n;
		assertTrue(ms < 10, "average inference " + ms + " ms");
	}

	@Test
	void serviceLearnsStoresAndReplays(@TempDir Path dir) throws Exception {
		List<String> msgs = new ArrayList<>();
		PredictorService s = new PredictorService(new Storage(dir), msgs::add);
		MapGrid full = TestMaps.fullM7();
		int[] reveal = TestMaps.revealOrder(full);
		long t0 = 1_000_000;
		for (int step : Replay.steps(reveal)) {
			MapGrid at = Replay.at(full, reveal, step);
			s.submit(new PredictorService.Observation(1, t0 + step * 1000L, Floor.M7, TestMaps.paint(at), 5, -1, null, -1, -1, -1));
			barrier(s);
			// A later observation of the same map lets the delayed labels be learned.
			s.submit(new PredictorService.Observation(1, t0 + step * 1000L + 5000, Floor.M7, TestMaps.paint(at), 5, -1, null, -1, -1, -1));
			barrier(s);
		}
		assertNotNull(s.latest());
		s.endRun();
		barrier(s);
		assertNull(s.lastError(), s.lastError());
		assertTrue(Files.isRegularFile(dir.resolve("model.json")));
		assertTrue(Files.isRegularFile(dir.resolve("maps.jsonl")));
		assertEquals(1, s.runs().size());
		RunRecord r = s.runs().getFirst();
		assertEquals("M7", r.floor);
		assertTrue(r.grid().sameAs(full.markRushPath()), "stored with the Blood-rush path as wither doors");
		assertEquals(full.find(Tile.BLOOD), r.bloodActual);

		// A new service instance reads both back, with the learned updates.
		PredictorService s2 = new PredictorService(new Storage(dir), msgs::add);
		List<String> stats = new ArrayList<>();
		CountDownLatch l = new CountDownLatch(1);
		s2.stats(line -> {
			stats.add(line);
			if (line.startsWith("Stored maps")) l.countDown();
		});
		assertTrue(l.await(5, TimeUnit.SECONDS));
		assertTrue(stats.getFirst().matches("Model updates: [1-9]\\d*.*"), stats.getFirst());
		assertEquals(1, s2.runs().size());
		s.shutdown();
		s2.shutdown();
	}

	private static void barrier(PredictorService s) throws InterruptedException {
		CountDownLatch l = new CountDownLatch(1);
		s.predictAsync(MapGrid.empty(Floor.F7), 0).thenRun(l::countDown);
		assertTrue(l.await(5, TimeUnit.SECONDS));
	}
}

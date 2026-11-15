package com.github.roompredictor.engine;

import com.github.roompredictor.core.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Owns the model and does all decoding, inference, learning and file IO on one background thread.
 *
 * <p>The client thread only hands over {@link Observation}s (a copy of the map colours plus a few
 * values read from the scoreboard / tab list) and reads the latest {@link Prediction}. Observations
 * are coalesced: if several arrive while the worker is busy, only the newest is processed.
 */
public final class PredictorService {
	private static final Logger LOG = Logger.getLogger("roompredictor");
	/** Wait this long after a room opens before learning from it, so its doors are on the map. */
	private static final long LABEL_DELAY_MS = 1200;

	/** Everything the client thread knows at one moment. {@code colors} is a private copy. */
	public record Observation(int runId, long timeMs, Floor floor, byte[] colors, int puzzles, String[] names,
							  int spawnX, int spawnZ, int spawnDir) {
	}

	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "RoomPredictor-Worker");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY + 1);
		return t;
	});
	private final AtomicReference<Observation> pending = new AtomicReference<>();
	private final AtomicBoolean scheduled = new AtomicBoolean();
	private volatile Prediction latest;
	private volatile List<RunRecord> runs = List.of();
	private volatile String lastError;
	private volatile String status = "idle (not in a dungeon)";

	private final Storage storage;
	private final Consumer<String> notify;

	// ---- worker-thread state ----
	/** The saved model: changed only by weighted end-of-run training and rebuilds. */
	private Model base;
	/** Copy of {@link #base} for the current run, updated live as rooms open. */
	private Model model;
	private final ExecutorService rebuilder = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "RoomPredictor-Rebuild");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
	private volatile boolean rebuilding;
	private int runId = Integer.MIN_VALUE;
	private int finishedRunId = Integer.MIN_VALUE;
	private long runStartMs;
	private Floor floor;
	private volatile MapDecoder.Geometry geometry;
	private byte[] lastColors;
	private int lastPuzzles;
	private MapGrid grid;
	private Prediction prediction;
	private long predictedAtUpdates = -1;
	private final Map<Integer, Example> examples = new HashMap<>();
	private final Map<Integer, Long> toLabel = new HashMap<>();
	private boolean[] trained;
	private int[] reveal;
	private int bloodGuess = -1;
	private double bloodGuessP;
	private int puzzles;
	private String[] names;
	/** Spawn cell and facing (0 N .. 3 W) when the player joined this dungeon, or -1. */
	private int spawnX = -1, spawnZ = -1, spawnDir = -1;
	/** False while {@link #grid} is the pre-map guess built from the spawn. */
	private boolean gridFromMap;

	private record Example(Features features, boolean[] mask) {
	}

	/** @param notify called (on the worker thread) with short status messages for the player */
	public PredictorService(Storage storage, Consumer<String> notify) {
		this.storage = storage;
		this.notify = notify;
		worker.execute(guard(() -> {
			base = storage.loadModel();
			model = base.copy();
			runs = Collections.unmodifiableList(new ArrayList<>(storage.loadRuns()));
			// A fresh model (first launch, or the feature layout changed) is rebuilt from the maps.
			if (storage.lastLoadWasFresh() && !runs.isEmpty()) startRebuild(false);
		}));
	}

	/** Retrains the saved model from scratch on every stored map (weighted by how much was opened). */
	public void rebuild() {
		worker.execute(guard(() -> startRebuild(true)));
	}

	private void startRebuild(boolean announce) {
		if (rebuilding) {
			if (announce) notify.accept("A rebuild is already running.");
			return;
		}
		rebuilding = true;
		List<RunRecord> snapshot = runs;
		double lr = base.learningRate;
		if (announce) notify.accept("Rebuilding the model from " + snapshot.size() + " stored maps...");
		rebuilder.execute(() -> {
			try {
				Model m = Model.seeded();
				m.learningRate = lr;
				for (RunRecord r : snapshot) Trainer.train(m, r);
				worker.execute(guard(() -> {
					// Runs that finished while rebuilding.
					for (int i = snapshot.size(); i < runs.size(); i++) Trainer.train(m, runs.get(i));
					base = m;
					model = base.copy();
					storage.saveModel(base);
					predictedAtUpdates = -1;
					repredict();
					rebuilding = false;
					notify.accept("Model rebuilt from " + runs.size() + " maps (" + base.updates + " updates).");
				}));
			} catch (Throwable t) {
				rebuilding = false;
				lastError = t.toString();
				t.printStackTrace();
			}
		});
	}

	// ======================= client-thread API =======================

	public void submit(Observation o) {
		pending.set(o);
		if (scheduled.compareAndSet(false, true)) worker.execute(this::drain);
	}

	public Prediction latest() {
		return latest;
	}

	/** Every stored run, oldest first. */
	public List<RunRecord> runs() {
		return runs;
	}

	/** Where decoding has got to, for {@code /srp debug}. */
	public String status() {
		Prediction p = latest;
		return status + (p == null ? ", no prediction yet" : String.format(", predicting (%.2f ms, %d model updates)", p.computeNanos / 1e6, p.modelUpdates));
	}

	public String lastError() {
		return lastError;
	}

	/** Pixel layout of the current map item, or null before the Entrance has been found. */
	public MapDecoder.Geometry geometry() {
		return geometry;
	}

	/**
	 * Writes the last map item image (4x, with the decoder's sample points marked) and a text dump
	 * to {@code config/roompredictor/debug/}, for diagnosing decoding problems.
	 */
	public void dumpMap(Consumer<String> out) {
		worker.execute(guard(() -> {
			try {
				writeDump(out);
			} catch (java.io.IOException e) {
				out.accept("Couldn't write the map dump: " + e);
			}
		}));
	}

	private void writeDump(Consumer<String> out) throws java.io.IOException {
		{
			byte[] c = lastColors;
			if (c == null || floor == null) {
				out.accept("No map item seen in this run yet.");
				return;
			}
			java.nio.file.Path dir = storage.dir().resolve("debug");
			java.nio.file.Files.createDirectories(dir);
			String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
			int scale = 4;
			java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(128 * scale, 128 * scale, java.awt.image.BufferedImage.TYPE_INT_RGB);
			for (int z = 0; z < 128; z++) {
				for (int x = 0; x < 128; x++) {
					int rgb = debugColor(c[z * 128 + x]);
					for (int dz = 0; dz < scale; dz++) for (int dx = 0; dx < scale; dx++) img.setRGB(x * scale + dx, z * scale + dz, rgb);
				}
			}
			MapDecoder.Geometry g = geometry;
			if (g != null) {
				for (int[] p : MapDecoder.samplePoints(floor, g)) {
					int color = p[2] == 0 ? 0x00FFFF : p[2] == 1 ? 0xFF00FF : 0xFFFFFF;
					for (int d = 0; d < scale; d++) {
						img.setRGB(p[0] * scale + d, p[1] * scale + scale / 2, color);
						img.setRGB(p[0] * scale + scale / 2, p[1] * scale + d, color);
					}
				}
			}
			java.nio.file.Path png = dir.resolve("map-" + stamp + ".png");
			javax.imageio.ImageIO.write(img, "png", png.toFile());
			StringBuilder sb = new StringBuilder();
			sb.append("floor ").append(floor).append("\ngeometry ").append(g).append('\n');
			if (grid != null) sb.append("grid ").append(grid.encode()).append('\n');
			sb.append("sample marks: cyan = room tile, magenta = gap edge, white = gap middle (door)\n");
			for (int z = 0; z < 128; z++) {
				for (int x = 0; x < 128; x++) {
					int v = c[z * 128 + x] & 0xFF;
					sb.append(v == 0 ? "  ." : String.format("%3d", v));
				}
				sb.append('\n');
			}
			java.nio.file.Files.writeString(dir.resolve("map-" + stamp + ".txt"), sb.toString());
			out.accept("Saved " + png);
		}
	}

	/** Rough real-map colours for the palette entries Hypixel uses; others as grey by value. */
	private static int debugColor(byte b) {
		return switch (b) {
			case 0 -> 0x101010;
			case 30 -> 0x148500;
			case 63 -> 0x6B3A11;
			case 85 -> 0x414141;
			case 66 -> 0x750085;
			case 62 -> 0xD87F33;
			case 74 -> 0xFEDF00;
			case 18 -> 0xFF0000;
			case 82 -> 0xE000FF;
			case 119 -> 0x000000;
			case 34 -> 0xFFFFFF;
			default -> {
				int v = 60 + ((b & 0xFF) * 3) % 180;
				yield (v << 16) | (v << 8) | v;
			}
		};
	}

	/** The run is over (boss entered, left the dungeon): learn from it and store the map. */
	public void endRun() {
		worker.execute(guard(this::finishRun));
	}

	public void resetModel() {
		worker.execute(guard(() -> {
			base = Model.seeded();
			model = base.copy();
			storage.saveModel(base);
			predictedAtUpdates = -1;
			repredict();
			notify.accept("Model reset to the rule-based starting weights.");
		}));
	}

	/** Re-reads model.json, e.g. after editing it by hand. */
	public void reloadModel() {
		worker.execute(guard(() -> {
			base = storage.loadModel();
			model = base.copy();
			predictedAtUpdates = -1;
			repredict();
			notify.accept("Model reloaded (" + base.updates + " updates).");
		}));
	}

	public void save() {
		worker.execute(guard(() -> {
			storage.saveModel(base);
			notify.accept("Saved to " + storage.dir());
		}));
	}

	public void setLearningRate(double lr) {
		worker.execute(guard(() -> {
			base.learningRate = lr;
			model.learningRate = lr;
			storage.saveModel(base);
			notify.accept("Learning rate set to " + lr);
		}));
	}

	public void stats(Consumer<String> out) {
		worker.execute(guard(() -> {
			int guessed = 0, right = 0;
			for (RunRecord r : runs) {
				if (r.bloodGuess < 0 || r.bloodActual < 0) continue;
				guessed++;
				if (r.bloodGuess == r.bloodActual) right++;
			}
			out.accept("Model updates: " + base.updates + " saved (+" + (model.updates - base.updates) + " live this run), learning rate " + base.learningRate
				+ (rebuilding ? ", rebuilding..." : ""));
			double opened = 0;
			for (RunRecord r : runs) {
				MapGrid rg = r.grid();
				if (rg != null) opened += Trainer.openedFraction(rg);
			}
			if (!runs.isEmpty()) out.accept(String.format("Average map opened: %.0f%% (each run's training weight)", 100 * opened / runs.size()));
			out.accept("Stored maps: " + runs.size());
			int facing = 0, facingRight = 0;
			for (RunRecord r : runs) {
				if (r.spawnDir < 0 || r.entranceDoor < 0) continue;
				facing++;
				if (r.spawnDir == r.entranceDoor) facingRight++;
			}
			if (facing > 0) out.accept("Spawn facing = Entrance door: " + facingRight + "/" + facing + " runs");
			if (guessed > 0) {
				out.accept(String.format("Blood guessed at run start: %d/%d right (%.0f%%)", right, guessed, 100.0 * right / guessed));
			}
			String[] bracketNames = {"E", "F1/M1", "F2-3/M2-3", "F4/M4", "F5-6/M5-6", "F7/M7"};
			for (int b = 0; b < Floor.BRACKETS; b++) {
				if (base.bigRuns[b] == 0) continue;
				out.accept(String.format("%s big rooms (full maps, n=%d): avg %.1f rooms / %.1f tiles, max %d / %d",
					bracketNames[b], base.bigRuns[b], base.bigRoomTotals[b] / base.bigRuns[b],
					base.bigTileTotals[b] / base.bigRuns[b], base.bigRoomMax[b], base.bigTileMax[b]));
			}
			distanceReport(out);
			Prediction p = prediction;
			if (p != null) out.accept(String.format("Last inference: %.2f ms", p.computeNanos / 1e6));
		}));
	}

	/**
	 * "Tiles between" Entrance/Fairy/Blood on every stored map where all three were opened,
	 * measured two ways: shortest walk, and counting each room passed through by its full size.
	 * Whichever matches the known rules (Fairy 4-5 from Entrance, Blood 3 from Fairy) is how
	 * Hypixel counts.
	 */
	private void distanceReport(Consumer<String> out) {
		List<int[]> ef = new ArrayList<>(), fb = new ArrayList<>(), eb = new ArrayList<>();
		for (RunRecord r : runs) {
			MapGrid g = r.grid();
			if (g == null) continue;
			int e = g.entrance(), f = g.find(Tile.FAIRY), b = g.find(Tile.BLOOD);
			if (e < 0 || f < 0 || b < 0) continue;
			int[] we = g.walkDistances(e), wf = g.walkDistances(f), re = g.roomBetween(e), rf = g.roomBetween(f);
			if (we[f] > 0 && re[f] >= 0) ef.add(new int[]{we[f] - 1, re[f]});
			if (wf[b] > 0 && rf[b] >= 0) fb.add(new int[]{wf[b] - 1, rf[b]});
			if (we[b] > 0 && re[b] >= 0) eb.add(new int[]{we[b] - 1, re[b]});
		}
		if (ef.isEmpty() && fb.isEmpty()) return;
		out.accept("Tiles between (shortest walk | whole rooms), from stored maps:");
		report(out, "  Entrance-Fairy", ef);
		report(out, "  Fairy-Blood", fb);
		report(out, "  Entrance-Blood", eb);
	}

	private static void report(Consumer<String> out, String label, List<int[]> v) {
		if (v.isEmpty()) return;
		int[] lo = {99, 99}, hi = {-1, -1};
		double[] sum = new double[2];
		for (int[] x : v) {
			for (int k = 0; k < 2; k++) {
				lo[k] = Math.min(lo[k], x[k]);
				hi[k] = Math.max(hi[k], x[k]);
				sum[k] += x[k];
			}
		}
		out.accept(String.format("%s: %d-%d (avg %.1f) | %d-%d (avg %.1f), %d maps", label,
			lo[0], hi[0], sum[0] / v.size(), lo[1], hi[1], sum[1] / v.size(), v.size()));
	}

	/** Runs the predictor on a stored map (for the map viewer), off the client thread. */
	public CompletableFuture<Prediction> predictAsync(MapGrid g, int puzzles) {
		return CompletableFuture.supplyAsync(() -> Predictor.predict(g, model, puzzles), worker);
	}

	/** Finishes the current run and saves, waiting briefly. Called when the game closes. */
	public void shutdown() {
		worker.execute(guard(() -> {
			finishRun();
			storage.saveModel(base);
		}));
		worker.shutdown();
		rebuilder.shutdownNow();
		try {
			worker.awaitTermination(3, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// ======================= worker thread =======================

	private Runnable guard(Runnable r) {
		return () -> {
			try {
				r.run();
			} catch (Throwable t) {
				lastError = t.toString();
				LOG.warning("RoomPredictor worker error: " + t);
				t.printStackTrace();
			}
		};
	}

	private void drain() {
		Observation o;
		while ((o = pending.getAndSet(null)) != null) {
			Observation obs = o;
			guard(() -> process(obs)).run();
		}
		scheduled.set(false);
		if (pending.get() != null && scheduled.compareAndSet(false, true)) worker.execute(this::drain);
	}

	private void process(Observation o) {
		if (model == null) return;
		if (o.runId() == finishedRunId) return;
		if (o.runId() != runId) {
			finishRun();
			startRun(o);
		}
		if (o.floor() == null) return;
		if (o.floor() != floor) {
			floor = o.floor();
			geometry = null;
			grid = null;
			gridFromMap = false;
			trained = new boolean[floor.cells()];
			reveal = new int[floor.cells()];
			Arrays.fill(reveal, -1);
			examples.clear();
			toLabel.clear();
			bloodGuess = -1;
		}
		if (o.puzzles() > 0) puzzles = o.puzzles();
		if (o.names() != null) names = o.names();
		if (o.spawnDir() >= 0) {
			spawnX = o.spawnX();
			spawnZ = o.spawnZ();
			spawnDir = o.spawnDir();
		}

		boolean mapChanged = o.colors() != null && !Arrays.equals(o.colors(), lastColors);
		if (o.colors() == null && geometry == null) status = "no map item in hotbar slot 9 yet";
		if (mapChanged) {
			lastColors = o.colors();
			if (geometry == null) geometry = MapDecoder.geometry(lastColors, floor);
			if (geometry != null) {
				MapGrid next = MapDecoder.decode(lastColors, floor, geometry);
				if (!gridFromMap) grid = null; // the spawn guess isn't something the map revealed
				next = next.rememberDoors(grid); // opened wither/blood doors stay wither/blood doors
				if (!next.sameAs(grid)) onNewGrid(next, o.timeMs());
				gridFromMap = true;
				status = "map decoded (room " + geometry.roomSize() + "px at " + geometry.offsetX() + "," + geometry.offsetZ() + ")";
			} else {
				status = "map item found, but no Entrance square on it yet";
			}
		}
		if (!gridFromMap) {
			// No map yet (pre-start, or the map item isn't held). The spawn gives the Entrance and,
			// from the facing direction, its door; otherwise predict on an empty grid so the
			// zero-information Blood guess still shows.
			MapGrid pre = spawnDir >= 0 ? MapGrid.spawnGrid(floor, spawnX, spawnZ, spawnDir) : null;
			if (pre == null) pre = MapGrid.empty(floor);
			if (!pre.sameAs(grid)) grid = pre;
		}

		learnPending(o.timeMs());

		if (prediction == null || prediction.grid != grid || puzzles != lastPuzzles || model.updates != predictedAtUpdates) {
			repredict();
		}
	}

	private void startRun(Observation o) {
		runId = o.runId();
		runStartMs = o.timeMs();
		floor = null;
		geometry = null;
		lastColors = null;
		grid = null;
		prediction = null;
		latest = null;
		examples.clear();
		toLabel.clear();
		puzzles = 0;
		lastPuzzles = 0;
		bloodGuess = -1;
		trained = null;
		reveal = null;
		spawnX = spawnZ = spawnDir = -1;
		gridFromMap = false;
	}

	private void onNewGrid(MapGrid next, long now) {
		MapGrid prev = grid;
		for (int i = 0; i < next.size(); i++) {
			boolean was = prev != null && prev.tile(i).explored();
			if (!next.tile(i).explored() || was) continue;
			if (reveal[i] < 0) reveal[i] = (int) Math.max(0, (now - runStartMs) / 1000);
			if (!trained[i] && examples.containsKey(i)) toLabel.putIfAbsent(i, now);
		}
		grid = next;
	}

	private void learnPending(long now) {
		if (toLabel.isEmpty() || grid == null) return;
		boolean learned = false;
		var it = toLabel.entrySet().iterator();
		while (it.hasNext()) {
			var e = it.next();
			if (now - e.getValue() < LABEL_DELAY_MS) continue;
			it.remove();
			int cell = e.getKey();
			Example ex = examples.get(cell);
			Label label = Trainer.labelFor(grid, cell, puzzles);
			if (ex == null || label == null || trained[cell]) continue;
			// Live learning, for this run's predictions only. The saved model learns from the
			// whole run when it ends, weighted by how much of the map was opened.
			model.update(ex.features(), ex.mask(), label);
			trained[cell] = true;
		}
	}

	private void repredict() {
		if (floor == null || grid == null || model == null) return;
		prediction = Predictor.predict(grid, model, puzzles);
		predictedAtUpdates = model.updates;
		lastPuzzles = puzzles;
		for (int i = 0; i < grid.size(); i++) {
			if (prediction.features[i] != null && !trained[i]) {
				examples.put(i, new Example(prediction.features[i], prediction.masks[i]));
			}
		}
		if (bloodGuess < 0 && grid.entrance() >= 0 && prediction.bloodCell >= 0 && grid.count(Tile.BLOOD) == 0) {
			bloodGuess = prediction.bloodCell;
			bloodGuessP = prediction.bloodProbability;
		}
		latest = prediction;
	}

	private void finishRun() {
		if (runId != Integer.MIN_VALUE) finishedRunId = runId;
		if (floor == null || grid == null || grid.entrance() < 0 || model == null) {
			startRunReset();
			return;
		}


		RunRecord r = new RunRecord();
		r.time = runStartMs;
		r.floor = floor.name();
		r.puzzles = puzzles;
		r.map = grid.encode();
		r.reveal = reveal.clone();
		r.bloodGuess = bloodGuess;
		r.bloodGuessP = Math.round(bloodGuessP * 1000) / 1000.0;
		r.bloodActual = grid.find(Tile.BLOOD);
		if (names != null && names.length == grid.size()) r.names = names.clone();
		r.spawnDir = spawnDir;
		r.entranceDoor = gridFromMap ? grid.entranceDoorDir() : -1;
		r.seconds = Math.max(0, (System.currentTimeMillis() - runStartMs) / 1000);
		storage.appendRun(r);
		List<RunRecord> all = new ArrayList<>(runs);
		all.add(r);
		runs = Collections.unmodifiableList(all);

		// The saved model learns from the whole run, weighted by how much of the map was opened;
		// the live changes made during the run are dropped. (A running rebuild picks it up too.)
		Trainer.train(base, r);
		model = base.copy();
		storage.saveModel(base);
		startRunReset();
	}

	private void startRunReset() {
		floor = null;
		grid = null;
		geometry = null;
		lastColors = null;
		prediction = null;
		latest = null;
		examples.clear();
		toLabel.clear();
		trained = null;
		reveal = null;
		bloodGuess = -1;
		names = null;
		spawnX = spawnZ = spawnDir = -1;
		gridFromMap = false;
	}
}

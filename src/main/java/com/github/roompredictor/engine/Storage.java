package com.github.roompredictor.engine;

import com.github.roompredictor.core.Model;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** Files under {@code config/roompredictor/}. Only called from the worker thread. */
public final class Storage {
	private static final Logger LOG = Logger.getLogger("roompredictor");
	private static final Gson GSON = new GsonBuilder().serializeSpecialFloatingPointValues().create();

	private final Path dir;
	private boolean lastLoadWasFresh;

	public Storage(Path dir) {
		this.dir = dir;
	}

	public Path dir() {
		return dir;
	}

	public Path modelFile() {
		return dir.resolve("model.json");
	}

	public Path mapsFile() {
		return dir.resolve("maps.jsonl");
	}

	/** True if the last {@link #loadModel()} found no usable model and returned the rule-based one. */
	public boolean lastLoadWasFresh() {
		return lastLoadWasFresh;
	}

	public Model loadModel() {
		lastLoadWasFresh = false;
		Path f = modelFile();
		if (Files.isRegularFile(f)) {
			try {
				Model m = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Model.class);
				if (m != null && m.version == Model.VERSION) {
					m.sanitize();
					return m;
				}
				if (m != null) {
					LOG.info("model.json is from an older feature layout (v" + m.version + "); starting from the rules again.");
					Files.copy(f, dir.resolve("model.v" + m.version + ".json"), StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (IOException | JsonParseException e) {
				LOG.warning("Could not read " + f + ", starting from the rule-based model: " + e);
				try {
					Files.copy(f, dir.resolve("model.broken.json"), StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException ignored) {
				}
			}
		}
		lastLoadWasFresh = true;
		return Model.seeded();
	}

	public void saveModel(Model m) {
		try {
			Files.createDirectories(dir);
			Path tmp = dir.resolve("model.json.tmp");
			Files.writeString(tmp, GSON.toJson(m), StandardCharsets.UTF_8);
			Files.move(tmp, modelFile(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			LOG.warning("Could not save model: " + e);
		}
	}

	private static final String BUNDLED = "/assets/roompredictor/default/";

	/** The default model shipped in the jar (trained by the mod's author), or null. */
	public Model loadBundledModel() {
		try (java.io.InputStream in = Storage.class.getResourceAsStream(BUNDLED + "model.json")) {
			if (in == null) return null;
			Model m = GSON.fromJson(new java.io.InputStreamReader(in, StandardCharsets.UTF_8), Model.class);
			if (m == null || m.version != Model.VERSION) return null;
			m.sanitize();
			return m;
		} catch (IOException | JsonParseException e) {
			return null;
		}
	}

	/** The map database shipped in the jar, used for rebuilds. Empty if none. */
	public List<RunRecord> loadBundledRuns() {
		List<RunRecord> out = new ArrayList<>();
		try (java.io.InputStream in = Storage.class.getResourceAsStream(BUNDLED + "maps.jsonl")) {
			if (in == null) return out;
			BufferedReader r = new BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isBlank()) continue;
				try {
					RunRecord rec = GSON.fromJson(line, RunRecord.class);
					if (rec != null && rec.grid() != null) out.add(rec);
				} catch (JsonParseException ignored) {
				}
			}
		} catch (IOException ignored) {
		}
		return out;
	}

	/**
	 * Writes a shareable copy of the training to {@code export/}: the model with its AdaGrad
	 * history softened (so players' own runs still move it at a useful pace) and the map database
	 * without timestamps. Nothing else about the player is in either file.
	 */
	public Path exportTraining(Model m, List<RunRecord> runs) throws IOException {
		Path out = dir.resolve("export");
		Files.createDirectories(out);
		Model copy = m.copy();
		for (double[] row : copy.gradSq) for (int i = 0; i < row.length; i++) row[i] *= 0.25;
		Files.writeString(out.resolve("model.json"), GSON.toJson(copy), StandardCharsets.UTF_8);
		StringBuilder sb = new StringBuilder();
		for (RunRecord r : runs) {
			RunRecord c = GSON.fromJson(GSON.toJson(r), RunRecord.class);
			c.time = 0;
			sb.append(GSON.toJson(c)).append((char) 10);
		}
		Files.writeString(out.resolve("maps.jsonl"), sb.toString(), StandardCharsets.UTF_8);
		return out;
	}

	/** Identity of a stored run, for skipping bundled maps that are also in the local database. */
	public static String key(RunRecord r) {
		return r.floor + "|" + r.map + "|" + java.util.Arrays.toString(r.reveal);
	}

	public void appendRun(RunRecord r) {
		try {
			Files.createDirectories(dir);
			try (Writer w = Files.newBufferedWriter(mapsFile(), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
				w.write(GSON.toJson(r));
				w.write('\n');
			}
		} catch (IOException e) {
			LOG.warning("Could not append to map database: " + e);
		}
	}

	public List<RunRecord> loadRuns() {
		List<RunRecord> out = new ArrayList<>();
		Path f = mapsFile();
		if (!Files.isRegularFile(f)) return out;
		try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isBlank()) continue;
				try {
					RunRecord rec = GSON.fromJson(line, RunRecord.class);
					if (rec != null && rec.grid() != null) out.add(rec);
				} catch (JsonParseException ignored) {
					// A torn last line after a crash; skip it.
				}
			}
		} catch (IOException e) {
			LOG.warning("Could not read map database: " + e);
		}
		return out;
	}
}

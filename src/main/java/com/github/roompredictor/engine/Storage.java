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

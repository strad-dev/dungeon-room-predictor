package com.github.roompredictor.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hypixel dungeon rooms keyed by their fingerprint ("core"), from {@code assets/roompredictor/rooms.json}.
 *
 * <p>The room data comes from Odin (https://github.com/odtheking/Odin), BSD 3-Clause License,
 * Copyright (c) 2025, odtheking. See LICENSE-Odin.
 */
public final class RoomDatabase {
	/** One room as listed in rooms.json. */
	public static final class Room {
		public String name;
		public String type;
		public String shape;
		public int crypts;
		public int maxSecrets;
		public List<Integer> cores;
	}

	private final Map<Integer, Room> byCore;

	private RoomDatabase(Map<Integer, Room> byCore) {
		this.byCore = byCore;
	}

	public static RoomDatabase load() {
		Map<Integer, Room> map = new HashMap<>();
		try (InputStream in = RoomDatabase.class.getResourceAsStream("/assets/roompredictor/rooms.json")) {
			if (in != null) {
				List<Room> rooms = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
					new TypeToken<List<Room>>() {
					}.getType());
				if (rooms != null) {
					for (Room r : rooms) {
						if (r == null || r.cores == null) continue;
						for (Integer c : r.cores) if (c != null) map.put(c, r);
					}
				}
			}
		} catch (Exception ignored) {
			// No names rather than a crash.
		}
		return new RoomDatabase(Collections.unmodifiableMap(map));
	}

	public Room byCore(int core) {
		return byCore.get(core);
	}

	public int size() {
		return byCore.size();
	}
}

package com.github.roompredictor.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads Devonian's map settings from {@code config/devonianConfig.json} (read-only; the file is
 * never written) and copies them into this mod's {@link Config.Style}. Devonian is not needed at
 * runtime; this is a plain file read.
 */
public final class DvImport {
	private static final String P = "dungeonMap$";
	/** Bump when the importer reads new settings, so existing installs re-import once. */
	public static final int VERSION = 4;

	/** What was found in the file, applied later on the client thread. */
	public record Result(Config.Style style, Integer hudX, Integer hudY, Float hudSize,
						 Boolean renderNames, Boolean namesRequireLeap, Boolean renderRoomNames,
						 Boolean hideCommonRoomNames, Boolean colorRoomNames, List<String> imported) {
	}

	private DvImport() {
	}

	public static Path file(Path gameDir) {
		return gameDir.resolve("config").resolve("devonianConfig.json");
	}

	/** @throws IOException if the file is missing or unreadable */
	public static Result read(Path file, Config.Style base) throws IOException {
		JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
		JsonObject c = root.has("config") ? root.getAsJsonObject("config") : new JsonObject();
		Config.Style s = copy(base);
		List<String> got = new ArrayList<>();

		s.background = color(c, "backgroundColor", s.background, got);
		s.border = color(c, "borderColor", s.border, got);
		s.entrance = color(c, "roomEntranceColor", s.entrance, got);
		s.normal = color(c, "roomNormalColor", s.normal, got);
		s.puzzle = color(c, "roomPuzzleColor", s.puzzle, got);
		s.trap = color(c, "roomTrapColor", s.trap, got);
		s.yellow = color(c, "roomYellowColor", s.yellow, got);
		s.blood = color(c, "roomBloodColor", s.blood, got);
		s.fairy = color(c, "roomFairyColor", s.fairy, got);
		s.rare = color(c, "roomRareColor", s.rare, got);
		s.unknown = color(c, "roomUnknownColor", s.unknown, got);
		s.witherDoor = color(c, "doorWitherColor", s.witherDoor, got);
		s.bloodDoor = color(c, "doorBloodColor", s.bloodDoor, got);
		s.normalDoor = color(c, "doorNormalColor", s.normalDoor, got);
		JsonElement dyn = c.get(P + "doorNormalDynamicColor");
		if (dyn != null && dyn.isJsonPrimitive()) {
			s.dynamicDoors = dyn.getAsBoolean();
			got.add("doorNormalDynamicColor");
		}
		s.roomSize = num(c, "roomSize", s.roomSize, got);
		s.doorSize = num(c, "doorSize", s.doorSize, got);
		s.borderWidth = num(c, "border", s.borderWidth, got);
		// Devonian's text size is relative to its default of 1.33.
		float dvText = num(c, "textSize", -1, got);
		if (dvText > 0) s.textScale = dvText / 1.33f;
		s.sanitize();

		Integer x = null, y = null;
		Float size = null;
		JsonObject huds = root.has("huds") && root.get("huds").isJsonObject() ? root.getAsJsonObject("huds") : null;
		if (huds != null && huds.has("DungeonMap") && huds.get("DungeonMap").isJsonObject()) {
			JsonObject h = huds.getAsJsonObject("DungeonMap");
			try {
				x = (int) Math.round(h.get("x").getAsDouble());
				y = (int) Math.round(h.get("y").getAsDouble());
				// Devonian's map box is 100 * scale pixels.
				size = (float) (100 * h.get("scale").getAsDouble());
			} catch (RuntimeException ignored) {
				x = null;
				y = null;
				size = null;
			}
		}
		return new Result(s, x, y, size, bool(c, "renderNames", got), bool(c, "namesRequireLeap", got),
			bool(c, "renderRoomNames", got), bool(c, "dontRenderCommonRoomNames", got), bool(c, "colorRoomName", got), got);
	}

	private static Boolean bool(JsonObject c, String key, List<String> got) {
		JsonElement e = c.get(P + key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) return null;
		got.add(key);
		return e.getAsBoolean();
	}

	private static int color(JsonObject c, String key, int def, List<String> got) {
		JsonElement e = c.get(P + key);
		if (e == null || !e.isJsonPrimitive()) return def;
		try {
			got.add(key);
			return (int) e.getAsLong();
		} catch (RuntimeException ex) {
			got.remove(key);
			return def;
		}
	}

	private static float num(JsonObject c, String key, float def, List<String> got) {
		JsonElement e = c.get(P + key);
		if (e == null || !e.isJsonPrimitive()) return def;
		try {
			float v = (float) e.getAsDouble();
			got.add(key);
			return v;
		} catch (RuntimeException ex) {
			return def;
		}
	}

	private static Config.Style copy(Config.Style o) {
		Config.Style s = new Config.Style();
		s.background = o.background;
		s.border = o.border;
		s.borderWidth = o.borderWidth;
		s.textScale = o.textScale;
		s.headSize = o.headSize;
		s.roomSize = o.roomSize;
		s.doorSize = o.doorSize;
		s.entrance = o.entrance;
		s.normal = o.normal;
		s.puzzle = o.puzzle;
		s.trap = o.trap;
		s.yellow = o.yellow;
		s.blood = o.blood;
		s.fairy = o.fairy;
		s.rare = o.rare;
		s.unknown = o.unknown;
		s.witherDoor = o.witherDoor;
		s.bloodDoor = o.bloodDoor;
		s.dynamicDoors = o.dynamicDoors;
		s.normalDoor = o.normalDoor;
		return s;
	}
}

package com.github.roompredictor.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** User settings, stored in {@code config/roompredictor/config.json}. Client thread only. */
public final class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public boolean enabled = true;
	public int hudX = 6;
	public int hudY = 6;
	public float hudSize = 110;
	/** Show guesses on blank cells (rooms nobody has seen yet), dimmed. */
	public boolean showBlank = true;
	/** Red overlay proportional to the Blood probability on every unopened cell. */
	public boolean bloodHeat = true;
	/** Hide labels below this confidence on blank cells. */
	public float blankMinConfidence = 0.35f;
	/** Teammate names under their heads. */
	public boolean showNames = true;
	/** Like Devonian's "Render Names When Holding Leap". */
	public boolean namesOnlyWithLeap = true;
	/** Names of opened rooms (identified from rooms.json). */
	public boolean showRoomNames = true;
	/** Like Devonian's "Don't Render Names for Entrance/Fairy/Blood". */
	public boolean hideCommonNames;
	/** Room name colour follows the map checkmark: grey, white (cleared), green (all secrets), red (failed). */
	public boolean colorRoomNames = true;
	/** Checkmark icons on opened rooms, like Devonian's "Render Checkmarks". */
	public boolean showChecks = true;
	/** Predicted wither doors (rest of the Blood-rush path) at or above this probability. */
	public boolean showWitherDoors = true;
	public float witherMinConfidence = 0.3f;
	/** Shows a status line on screen even when no prediction is drawn. */
	public boolean debug;
	/** Which version of the quiet Devonian import last ran (see {@link DvImport#VERSION}). */
	public int dvImportVersion;
	public Style style = new Style();

	/** Map look. Colours are ARGB. Defaults match Devonian's default map style. */
	public static final class Style {
		public int background = 0x9A0B0B0B;
		public int border = 0xFF000000;
		/** Border width in pixels per 100 pixels of map size. */
		public float borderWidth = 0;
		public float roomSize = 0.8f;
		/** Label text size; 1 = as large as fits in the room. */
		public float textScale = 0.75f;
		/** Player head size as a fraction of a cell. */
		public float headSize = 0.5f;
		/** Checkmark icon size as a fraction of a room (Hypixel: 9 of 16 pixels). */
		public float iconSize = 0.5625f;
		public float doorSize = 0.32f;
		public int entrance = 0xFF148500;
		public int normal = 0xFF6B3A11;
		public int puzzle = 0xFF750085;
		public int trap = 0xFFD87F33;
		public int yellow = 0xFFFEDF00;
		public int blood = 0xFFFF0000;
		public int fairy = 0xFFE000FF;
		public int rare = 0xFFFFCB59;
		public int unknown = 0xFF414141;
		public int witherDoor = 0xFF000000;
		public int bloodDoor = 0xFFFF0000;
		/** Normal doors take the colour of the room they lead from, unless this is off. */
		public boolean dynamicDoors = true;
		public int normalDoor = 0xFF6B3A11;

		void sanitize() {
			roomSize = clamp(roomSize, 0.1f, 1f);
			doorSize = clamp(doorSize, 0.05f, 1f);
			borderWidth = clamp(borderWidth, 0f, 20f);
			textScale = textScale == 0 ? 0.75f : clamp(textScale, 0.2f, 2f);
			headSize = headSize == 0 ? 0.5f : clamp(headSize, 0.15f, 1.5f);
			iconSize = iconSize == 0 ? 0.5625f : clamp(iconSize, 0.1f, 1.5f);
		}

		private static float clamp(float v, float lo, float hi) {
			return Float.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
		}
	}

	private transient Path file;

	public static Config load(Path file) {
		Config c = null;
		if (Files.isRegularFile(file)) {
			try {
				c = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Config.class);
			} catch (Exception ignored) {
			}
		}
		if (c == null) c = new Config();
		if (c.style == null) c.style = new Style();
		c.style.sanitize();
		c.file = file;
		return c;
	}

	public boolean exists() {
		return Files.isRegularFile(file);
	}

	public void save() {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException ignored) {
		}
	}
}

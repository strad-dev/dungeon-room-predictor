package com.github.roompredictor;

import com.github.roompredictor.client.Config;
import com.github.roompredictor.client.DvImport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DvImportTest {
	@Test
	void readsDevonianMapSettings(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("devonianConfig.json");
		Files.writeString(f, """
			{"config": {
			  "dungeonMap": true,
			  "dungeonMap$backgroundColor": 0,
			  "dungeonMap$border": 3.01,
			  "dungeonMap$borderColor": -16777216,
			  "dungeonMap$roomEntranceColor": -16745728,
			  "dungeonMap$roomNormalColor": -9749999,
			  "dungeonMap$roomPuzzleColor": -5223467,
			  "dungeonMap$roomRareColor": -16760037,
			  "dungeonMap$roomSize": 0.8,
			  "dungeonMap$doorSize": 0.4,
			  "dungeonMap$doorNormalDynamicColor": false,
			  "dungeonMap$doorNormalColor": -11193581
			 },
			 "huds": {"DungeonMap": {"x": 2.0, "y": 18.5, "scale": 2.2}}}
			""");
		Config.Style base = new Config.Style();
		DvImport.Result r = DvImport.read(f, base);
		assertEquals(0, r.style().background);
		assertEquals(-16745728, r.style().entrance);
		assertEquals(-9749999, r.style().normal);
		assertEquals(-5223467, r.style().puzzle);
		assertEquals(0.4f, r.style().doorSize, 1e-6);
		assertEquals(3.01f, r.style().borderWidth, 1e-6);
		assertFalse(r.style().dynamicDoors);
		assertEquals(-11193581, r.style().normalDoor);
		// Keys missing from the file keep the current value.
		assertEquals(base.trap, r.style().trap);
		assertEquals(2, r.hudX());
		assertEquals(19, r.hudY());
		assertEquals(220f, r.hudSize(), 1e-3);
		// The source style object is not modified.
		assertNotEquals(base.puzzle, r.style().puzzle);
	}

	@Test
	void missingFileThrows(@TempDir Path dir) {
		assertThrows(Exception.class, () -> DvImport.read(dir.resolve("nope.json"), new Config.Style()));
	}
}

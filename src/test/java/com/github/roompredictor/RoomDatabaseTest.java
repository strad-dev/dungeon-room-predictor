package com.github.roompredictor;

import com.github.roompredictor.core.RoomDatabase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoomDatabaseTest {
	@Test
	void loadsBundledRooms() {
		RoomDatabase db = RoomDatabase.load();
		assertEquals(272, db.size(), "every core of the 140 rooms is indexed");
		RoomDatabase.Room admin = db.byCore(518379920);
		assertNotNull(admin);
		assertEquals("Admin", admin.name);
		assertEquals("1x1", admin.shape);
		assertNull(db.byCore(12345));
	}
}

package com.github.roompredictor.client;

import com.github.roompredictor.core.MapGrid;
import com.github.roompredictor.core.RoomDatabase;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * Names rooms the map item shows as <b>opened</b>, by fingerprinting the block column at the
 * centre of each of their cells and looking it up in {@link RoomDatabase}. Unopened rooms are
 * never fingerprinted, even if their chunks are loaded.
 *
 * <p>The fingerprint must equal the one used to build rooms.json (Odin's): scanning from y=140
 * down to y=12, air (and gold blocks) above the top block count as "0"; below that each block's
 * name is appended, except chests, trapped chests and oak planks (which change during a run);
 * once two consecutive bedrock layers have been passed below y=69,
 * the first air ends the column, padded with one "0" per remaining layer. The result is the
 * Java hash of that string.
 */
public final class RoomNamer {
	private static final int TOP = 140;
	private static final int BOTTOM = 12;

	private final RoomDatabase db;
	/** Cell index -> identified room, for the current run. */
	private final Map<Integer, RoomDatabase.Room> found = new HashMap<>();
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

	public RoomNamer(RoomDatabase db) {
		this.db = db;
	}

	public void reset() {
		found.clear();
	}

	/** Tries to identify every opened, not yet named cell whose chunk is loaded. Client thread. */
	public void update(ClientLevel level, MapGrid g) {
		for (int i = 0; i < g.size(); i++) {
			if (!g.tile(i).explored() || found.containsKey(i)) continue;
			int wx = -185 + 32 * g.x(i), wz = -185 + 32 * g.z(i);
			if (!level.hasChunk(wx >> 4, wz >> 4)) continue;
			RoomDatabase.Room r = db.byCore(fingerprint(level, wx, wz));
			if (r != null) found.put(i, r);
		}
	}

	/** Room name per cell (spread over every tile of a multi-tile room), null where unknown. */
	public String[] names(MapGrid g) {
		String[] out = new String[g.size()];
		for (var e : found.entrySet()) {
			int cell = e.getKey();
			if (cell >= g.size() || !g.tile(cell).explored()) continue;
			int comp = g.component(cell);
			for (int j = 0; j < g.size(); j++) {
				if (j == cell || (comp >= 0 && g.component(j) == comp)) out[j] = e.getValue().name;
			}
		}
		return out;
	}

	int fingerprint(ClientLevel level, int x, int z) {
		StringBuilder sb = new StringBuilder(1024);
		boolean foundTop = false;
		int bedrock = 0;
		for (int y = TOP; y >= BOTTOM; y--) {
			BlockState state = level.getBlockState(pos.set(x, y, z));
			Block block = state.getBlock();
			if (!foundTop) {
				if (!state.isAir() && block != Blocks.GOLD_BLOCK) foundTop = true;
				else sb.append('0');
			}
			if (!foundTop) continue;
			if (state.isAir() && bedrock >= 2 && y < 69) {
				sb.append("0".repeat(y - (BOTTOM - 1)));
				break;
			}
			if (block == Blocks.BEDROCK) {
				bedrock++;
			} else {
				bedrock = 0;
				if (block == Blocks.OAK_PLANKS || block == Blocks.TRAPPED_CHEST || block == Blocks.CHEST) continue;
			}
			sb.append(block);
		}
		return sb.toString().hashCode();
	}
}

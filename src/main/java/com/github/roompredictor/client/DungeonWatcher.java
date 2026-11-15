package com.github.roompredictor.client;

import com.github.roompredictor.core.Floor;
import com.github.roompredictor.engine.PredictorService;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs on the client thread every tick, but does only cheap reads of state the client already
 * has (sidebar, tab list, the held map item, chat) and hands a copy to the worker. Never sends
 * anything to the server.
 */
public final class DungeonWatcher {
	private static final Pattern FLOOR = Pattern.compile("The Catacombs \\((\\w+)\\)");
	/** "[MVP++] Name entered MM The Catacombs, Floor VII!" - sent to the party just before the transfer. */
	private static final Pattern ENTERED = Pattern.compile("entered (MM )?The Catacombs, (Floor ([IVX]+)|Entrance)");
	private static final Pattern PUZZLES = Pattern.compile("Puzzles: \\((\\d+)\\)");
	private static final Pattern BOSS = Pattern.compile("^\\[BOSS] ([^:]+):");
	private static final int MAP_SLOT = 8;
	private static final int SUBMIT_EVERY = 2;
	private static final int SCAN_EVERY = 20;
	/** How long a floor announced in chat stays valid while waiting for the dungeon server. */
	private static final long CHAT_FLOOR_TTL_MS = 90_000;

	private final PredictorService service;
	private ClientLevel level;
	private int runId;
	private int ticks;
	private Floor floor;
	private String floorSource = "none";
	private Floor chatFloor;
	private long chatFloorAt;
	private boolean chatFloorUsed;
	private int puzzles;
	/** Puzzles the tab list still shows as "???" (undiscovered), or -1 if no puzzle lines seen. */
	private int puzzlesUnknown = -1;
	private List<String> puzzleLines = List.of();
	private boolean inBoss;
	private byte[] lastSent;
	private boolean mapItemSeen;
	private List<String> lastSidebar = List.of();
	/** Teammate markers from the map item: {mapPixelX, mapPixelZ, yawDegrees}. */
	private volatile List<float[]> teammates = List.of();

	/** Living party members in tab-list order (includes you). */
	private volatile List<String> party = List.of();

	public List<float[]> teammates() {
		return teammates;
	}

	public List<String> party() {
		return party;
	}

	/** Dungeon tab lines look like "[400] [MVP++] Name (Archer L)"; "(DEAD)" for dead players. */
	private static final Pattern PARTY_LINE = Pattern.compile("^\\[\\d+\\] (?:\\[[A-Z+]+\\] )?(\\w{1,16})\\b.*\\((\\w+)");

	private static List<String> readParty(ClientPacketListener conn) {
		if (conn == null) return List.of();
		List<String> out = new ArrayList<>();
		for (PlayerInfo info : conn.getListedOnlinePlayers()) {
			Component name = info.getTabListDisplayName();
			if (name == null) continue;
			Matcher m = PARTY_LINE.matcher(clean(name.getString()));
			if (!m.find() || m.group(2).equals("DEAD") || m.group(2).equals("EMPTY")) continue;
			if (!out.contains(m.group(1))) out.add(m.group(1));
		}
		return out;
	}

	/** Cell and facing (0 N .. 3 W) at the first tick in this world inside the dungeon area. */
	private int spawnX = -1, spawnZ = -1, spawnDir = -1;

	private final RoomNamer namer;
	/** Room names per cell for the current map (opened rooms only), or null. */
	private volatile String[] names;

	public DungeonWatcher(PredictorService service, RoomNamer namer) {
		this.service = service;
		this.namer = namer;
	}

	public String[] names() {
		return names;
	}

	public Floor floor() {
		return floor;
	}

	/** True while a dungeon run (outside the boss room) is being tracked. */
	public boolean active() {
		return floor != null && !inBoss;
	}

	public void tick(Minecraft mc) {
		if (mc.level != level) {
			// Leaving the dungeon world ends the run (unless the boss already did).
			if (floor != null && !inBoss) service.endRun();
			level = mc.level;
			runId++;
			floor = null;
			floorSource = "none";
			puzzles = 0;
			puzzlesUnknown = -1;
			puzzleLines = List.of();
			inBoss = false;
			lastSent = null;
			mapItemSeen = false;
			teammates = List.of();
			party = List.of();
			namer.reset();
			names = null;
			spawnX = spawnZ = spawnDir = -1;
			// The chat announcement arrives in the Dungeon Hub; it applies to the next world only.
			if (chatFloor != null && chatFloorUsed) chatFloor = null;
		}
		if (level == null || mc.player == null) return;
		ticks++;
		if (spawnDir < 0) captureSpawn(mc);

		// Until the floor is known, check every tick so the map shows up right away.
		if (floor == null || ticks % SCAN_EVERY == 0) {
			Scoreboard sb = level.getScoreboard();
			lastSidebar = sidebarLines(sb);
			Floor f = match(lastSidebar);
			if (f != null) {
				floor = f;
				floorSource = "sidebar";
			} else if (floor == null && chatFloor != null && !chatFloorUsed && level != null
				&& System.currentTimeMillis() - chatFloorAt < CHAT_FLOOR_TTL_MS && sidebarMentionsDungeon()) {
				floor = chatFloor;
				floorSource = "chat";
				chatFloorUsed = true;
			}
			if (floor != null) {
				int p = readPuzzles(mc.getConnection());
				if (p > 0) puzzles = p;
				readPuzzleLines(mc.getConnection());
				party = readParty(mc.getConnection());
				if (!inBoss) {
					com.github.roompredictor.core.Prediction lp = service.latest();
					if (lp != null) {
						namer.update(level, lp.grid);
						names = namer.names(lp.grid);
					}
				}
			}
		}
		if (floor == null || inBoss || ticks % SUBMIT_EVERY != 0) return;

		byte[] colors = null;
		ItemStack stack = mc.player.getInventory().getItem(MAP_SLOT);
		if (stack.getItem() instanceof MapItem) {
			MapItemSavedData data = MapItem.getSavedData(stack, level);
			if (data != null && data.colors != null) {
				mapItemSeen = true;
				// Teammates: the map item's markers, except the frame marker (that one is you).
				List<float[]> marks = new ArrayList<>();
				for (MapDecoration d : data.getDecorations()) {
					if (d.type().is(MapDecorationTypes.FRAME)) continue;
					marks.add(new float[]{d.x() / 2f + 64f, d.y() / 2f + 64f, d.rot() * 22.5f});
				}
				teammates = marks;
				if (lastSent != null && Arrays.equals(data.colors, lastSent)) colors = lastSent;
				else colors = lastSent = data.colors.clone();
			}
		}
		service.submit(new PredictorService.Observation(runId, System.currentTimeMillis(), floor, colors, puzzles, puzzlesUnknown, names,
			spawnX, spawnZ, spawnDir));
	}

	/**
	 * Records where the player is and which way they face on the first tick inside the dungeon
	 * grid (world x/z from -200, rooms 32 blocks apart). On joining a dungeon you stand in the
	 * Entrance facing its door. Minecraft yaw: 0 = south, 90 = west, 180 = north, 270 = east.
	 */
	private void captureSpawn(Minecraft mc) {
		double x = mc.player.getX(), z = mc.player.getZ();
		if (x < -200 || z < -200 || x > -8 || z > -8) return;
		spawnX = (int) Math.floor((x + 200) / 32);
		spawnZ = (int) Math.floor((z + 200) / 32);
		float yaw = ((mc.player.getYRot() % 360) + 360) % 360;
		int q = Math.round(yaw / 90f) % 4; // 0 south, 1 west, 2 north, 3 east
		spawnDir = new int[]{2, 3, 0, 1}[q];
	}

	/** True when the sidebar looks like a dungeon instance, so a stale chat floor isn't applied elsewhere. */
	private boolean sidebarMentionsDungeon() {
		for (String l : lastSidebar) if (l.contains("Catacombs") || l.contains("Cleared:") || l.contains("Time Elapsed")) return true;
		return false;
	}

	public void onChat(Component message) {
		String s = clean(message.getString());
		Matcher e = ENTERED.matcher(s);
		if (e.find()) {
			chatFloor = parseEntered(e.group(1) != null, e.group(3));
			chatFloorAt = System.currentTimeMillis();
			chatFloorUsed = false;
			return;
		}
		if (floor == null || inBoss) return;
		Matcher m = BOSS.matcher(s);
		if (m.find() && !m.group(1).trim().equals("The Watcher")) {
			inBoss = true;
			service.endRun();
		}
	}

	static Floor parseEntered(boolean master, String roman) {
		if (roman == null) return Floor.E;
		int n = switch (roman) {
			case "I" -> 1;
			case "II" -> 2;
			case "III" -> 3;
			case "IV" -> 4;
			case "V" -> 5;
			case "VI" -> 6;
			case "VII" -> 7;
			default -> 0;
		};
		if (n == 0) return null;
		return Floor.parse((master ? "M" : "F") + n);
	}

	/** Everything this watcher knows, for {@code /srp debug}. */
	public List<String> debug() {
		List<String> out = new ArrayList<>();
		out.add("floor: " + floor + " (from " + floorSource + "), chat floor: " + chatFloor
			+ (chatFloor == null ? "" : chatFloorUsed ? " (used)" : " (waiting)"));
		out.add("spawn cell: " + (spawnDir < 0 ? "not captured" : (spawnX + 1) + "," + (spawnZ + 1) + " facing " + "NESW".charAt(spawnDir)));
		out.add("tab puzzles: " + puzzleLines + (puzzlesUnknown >= 0 ? " (" + puzzlesUnknown + " undiscovered)" : ""));
		out.add("in boss: " + inBoss + ", puzzles: " + puzzles + ", map item in slot 9: " + mapItemSeen + ", run #" + runId);
		out.add("party (alive): " + party + ", map markers: " + teammates.size());
		String[] n = names;
		int named = 0;
		if (n != null) for (String x : n) if (x != null) named++;
		out.add("room tiles named: " + named + (n == null ? " (no map yet)" : ""));
		out.add("sidebar lines read: " + lastSidebar.size());
		int shown = 0;
		for (String l : lastSidebar) {
			if (shown++ >= 16) break;
			out.add("  | " + l);
		}
		return out;
	}

	private static Floor match(List<String> lines) {
		for (String l : lines) {
			Matcher m = FLOOR.matcher(l);
			if (m.find()) {
				Floor f = Floor.parse(m.group(1));
				if (f != null) return f;
			}
		}
		return null;
	}

	/**
	 * Sidebar text: for each displayed entry and each of Hypixel's {@code team_N} teams, the team's
	 * prefix + suffix (which is the visible line).
	 */
	private static List<String> sidebarLines(Scoreboard sb) {
		List<String> out = new ArrayList<>();
		Objective obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (obj != null) {
			for (PlayerScoreEntry e : sb.listPlayerScores(obj)) {
				PlayerTeam team = sb.getPlayersTeam(e.owner());
				if (team == null) continue;
				// Hypixel puts the visible text in the team prefix/suffix; the entry name is hidden filler.
				String line = clean(team.getPlayerPrefix().getString() + team.getPlayerSuffix().getString());
				if (!line.isEmpty() && !out.contains(line)) out.add(line);
			}
		}
		for (PlayerTeam t : sb.getPlayerTeams()) {
			if (!t.getName().startsWith("team_")) continue;
			String line = clean(t.getPlayerPrefix().getString() + t.getPlayerSuffix().getString());
			if (!line.isEmpty() && !out.contains(line)) out.add(line);
		}
		return out;
	}

	/** Puzzle lines under "Puzzles: (N)", e.g. " Ice Fill: [x]" or " ???: [x]" for undiscovered ones. */
	private static final Pattern PUZZLE_LINE = Pattern.compile("^([\\w ?']+): \\[");

	private void readPuzzleLines(ClientPacketListener conn) {
		if (conn == null) return;
		List<String> lines = new ArrayList<>();
		int unknown = 0;
		boolean inPuzzles = false;
		for (PlayerInfo info : conn.getListedOnlinePlayers()) {
			Component name = info.getTabListDisplayName();
			if (name == null) continue;
			String l = clean(name.getString());
			if (PUZZLES.matcher(l).find()) {
				inPuzzles = true;
				continue;
			}
			if (!inPuzzles) continue;
			Matcher m = PUZZLE_LINE.matcher(l);
			if (!m.find()) {
				if (!lines.isEmpty()) break; // past the puzzle section
				continue;
			}
			lines.add(l);
			if (m.group(1).trim().equals("???")) unknown++;
		}
		if (!lines.isEmpty()) {
			puzzleLines = lines;
			puzzlesUnknown = unknown;
		}
	}

	private static int readPuzzles(ClientPacketListener conn) {
		if (conn == null) return 0;
		for (PlayerInfo info : conn.getListedOnlinePlayers()) {
			Component name = info.getTabListDisplayName();
			if (name == null) continue;
			Matcher m = PUZZLES.matcher(clean(name.getString()));
			if (m.find()) return Integer.parseInt(m.group(1));
		}
		return 0;
	}

	/** Strips colour codes and the emoji/odd glyphs Hypixel sprinkles into scoreboard lines. */
	static String clean(String s) {
		String t = ChatFormatting.stripFormatting(s);
		if (t == null) return "";
		return t.replaceAll("[^\\x20-\\x7E]", "").replaceAll(" {2,}", " ").trim();
	}
}

package com.github.roompredictor.client;

import com.github.roompredictor.core.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Draws a {@link MapGrid} and, optionally, a {@link Prediction} into a square on screen. */
public final class MapPainter {
	public record Options(boolean showBlank, boolean bloodHeat, float blankMinConfidence, Config.Style style,
						  String[] roomNames, boolean hideCommonNames, boolean colorRoomNames, float witherMin,
						  boolean showChecks) {
	}

	private MapPainter() {
	}

	public static int tileColor(Config.Style s, Tile t) {
		return switch (t) {
			case ENTRANCE -> s.entrance;
			case NORMAL -> s.normal;
			case PUZZLE -> s.puzzle;
			case TRAP -> s.trap;
			case YELLOW -> s.yellow;
			case BLOOD -> s.blood;
			case FAIRY -> s.fairy;
			case UNKNOWN -> s.unknown;
			case EMPTY -> 0;
		};
	}

	/** Text colour for a guess: the room colour lightened so it reads on the grey "?" tile. */
	public static int labelColor(Config.Style s, Label l) {
		int base = switch (l) {
			case NORMAL -> s.normal;
			case PUZZLE -> s.puzzle;
			case TRAP -> s.trap;
			case YELLOW -> s.yellow;
			case BLOOD -> s.blood;
			case FAIRY -> s.fairy;
			case RARE -> s.rare;
			case EMPTY -> 0xFF9A9A9A;
		};
		int r = (base >> 16) & 0xFF, g = (base >> 8) & 0xFF, b = base & 0xFF;
		r += (255 - r) * 2 / 5;
		g += (255 - g) * 2 / 5;
		b += (255 - b) * 2 / 5;
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	/**
	 * @param p prediction for this exact grid, or null to draw just the map
	 */
	public static void draw(GuiGraphicsExtractor ctx, Font font, MapGrid g, Prediction p,
							float x, float y, float size, Options opt) {
		Config.Style st = opt.style();
		if ((st.background >>> 24) != 0) {
			ctx.fill(Math.round(x), Math.round(y), Math.round(x + size), Math.round(y + size), st.background);
		}
		// Devonian's border setting is in screen pixels (per 100 map units), not GUI units.
		float bw = st.borderWidth * size / 100f / (float) Math.max(1, net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale());
		if (bw >= 0.5f && (st.border >>> 24) != 0) {
			rect(ctx, x, y, size, bw, st.border);
			rect(ctx, x, y + size - bw, size, bw, st.border);
			rect(ctx, x, y + bw, bw, size - 2 * bw, st.border);
			rect(ctx, x + size - bw, y + bw, bw, size - 2 * bw, st.border);
		}
		Layout lay = layout(g, x, y, size, st);
		float cell = lay.cell, ox = lay.ox, oz = lay.oz, tile = lay.tile, inset = lay.inset;
		float door = Math.max(1, Math.round(cell * st.doorSize));

		for (int i = 0; i < g.size(); i++) {
			Tile t = g.tile(i);
			float tx = ox + g.x(i) * cell + inset;
			float tz = oz + g.z(i) * cell + inset;
			if (t != Tile.EMPTY) rect(ctx, tx, tz, tile, tile, tileColor(st, t));
			if (t == Tile.UNKNOWN) questionMark(ctx, tx, tz, tile);
			// Same-room joins and doors, drawn from the left/top cell.
			for (int d = 1; d <= 2; d++) {
				Link l = g.link(i, d);
				if (l == Link.NONE) continue;
				int nb = g.neighbor(i, d);
				Tile from = g.tile(i).explored() ? g.tile(i) : g.tile(nb);
				int color = switch (l) {
					case WITHER_DOOR -> st.witherDoor;
					case BLOOD_DOOR -> st.bloodDoor;
					case MERGED -> tileColor(st, from);
					default -> st.dynamicDoors ? tileColor(st, from) : st.normalDoor;
				};
				float span = l == Link.MERGED ? tile : door;
				if (d == 1) rect(ctx, tx + tile, tz + (tile - span) / 2, cell - tile, span, color);
				else rect(ctx, tx + (tile - span) / 2, tz + tile, span, cell - tile, color);
			}
			// Centre square where four tiles of one room meet (2x2 rooms).
			int e = g.neighbor(i, 1), s = g.neighbor(i, 2);
			if (e >= 0 && s >= 0 && g.link(i, 1) == Link.MERGED && g.link(i, 2) == Link.MERGED
				&& g.link(e, 2) == Link.MERGED && g.link(s, 1) == Link.MERGED) {
				rect(ctx, tx + tile, tz + tile, cell - tile, cell - tile, tileColor(st, t));
			}
		}

		drawRoomLabels(ctx, font, g, opt, ox, oz, cell, tile, inset);

		// The prediction may be one pass behind the newest map; it's only drawn on cells that are
		// still unopened in the map being shown.
		if (p == null || p.grid.floor != g.floor) return;

		// The predicted completion of the map: each predicted room drawn as an outline in the colour
		// of its type ("?" tiles keep their own grey), with one label on its top-left tile: its
		// category and chance ("L 46%", "End 98%", "Blood 40%", "1x1 30%" = a normal 1x1), plus the
		// runner-up if it's at least 10%.
		boolean[] covered = new boolean[g.size()];
		if (opt.showBlank() && p.roomOf != null) {
			// Rooms with a "?" tile already have the grey tile and "?" drawn; their outline is fainter.
			boolean[] hasUnknown = new boolean[p.roomLabel.length];
			for (int i = 0; i < g.size(); i++) if (p.roomOf[i] >= 0 && g.tile(i) == Tile.UNKNOWN) hasUnknown[p.roomOf[i]] = true;
			for (int i = 0; i < g.size(); i++) {
				int r = p.roomOf[i];
				if (r < 0 || g.tile(i).explored()) continue;
				covered[i] = true;
				float gx = ox + g.x(i) * cell + inset, gz = oz + g.z(i) * cell + inset;
				boolean east = sameRoom(p, g, i, 1), south = sameRoom(p, g, i, 2);
				boolean west = sameRoom(p, g, i, 3), north = sameRoom(p, g, i, 0);
				int edge = labelColor(st, groupColor(p.roomLabel[r]));
				if (hasUnknown[r]) edge = (edge & 0x00FFFFFF) | 0x66000000;
				// Outer edges of the room, running through the gaps between its own tiles.
				float x0 = west ? gx - (cell - tile) : gx, x1 = east ? gx + cell : gx + tile;
				float z0 = north ? gz - (cell - tile) : gz, z1 = south ? gz + cell : gz + tile;
				if (!north) rect(ctx, x0, gz, x1 - x0, 1, edge);
				if (!south) rect(ctx, x0, gz + tile - 1, x1 - x0, 1, edge);
				if (!west) rect(ctx, gx, z0, 1, z1 - z0, edge);
				if (!east) rect(ctx, gx + tile - 1, z0, 1, z1 - z0, edge);
			}
			boolean[] labelled = new boolean[p.roomLabel.length];
			for (int i = 0; i < g.size(); i++) {
				int r = p.roomOf[i];
				if (r < 0 || g.tile(i).explored() || labelled[r]) continue;
				labelled[r] = true; // cells run row by row, so this is the room's top-left tile
				float tx = ox + g.x(i) * cell + inset, tz = oz + g.z(i) * cell + inset;
				Label l = p.roomLabel[r];
				// One distribution per room over End / Blood / Fairy / 1x1 / 1x2 / ... (1x1 = a
				// normal 1x1 only), read where the room's evidence is: its "?" cell, else this cell.
				int at = i;
				for (int c = 0; c < g.size(); c++) if (p.roomOf[c] == r && g.tile(c) == Tile.UNKNOWN) at = c;
				java.util.Map<String, Double> cat = categories(p, at);
				String own = switch (l) {
					case BLOOD -> "Blood";
					case FAIRY -> "Fairy";
					case NORMAL -> p.roomShape[r].display;
					default -> "End";
				};
				String second = null;
				double secondP = 0;
				for (var e : cat.entrySet()) {
					if (e.getKey().equals(own) || e.getValue() <= secondP) continue;
					second = e.getKey();
					secondP = e.getValue();
				}
				String l2 = second != null && secondP >= 0.1 ? second + " " + Math.round(secondP * 100) + "%" : null;
				int color = (labelColor(st, groupColor(l)) & 0x00FFFFFF) | 0x80000000; // predictions at 50%
				String l1 = own + " " + Math.round(cat.getOrDefault(own, 0.0) * 100) + "%";
				if (g.tile(at) == Tile.UNKNOWN) {
					// On a "?" tile: one line in the strip above the "?" glyph.
					float qx = ox + g.x(at) * cell + inset, qz = oz + g.z(at) * cell + inset;
					aboveQuestionMark(ctx, font, qx, qz, tile, l1, color, st.textScale);
				} else {
					text(ctx, font, tx, tz, tile, l1, l2, color, st.textScale);
				}
			}
		}

		// Predicted doors joining the predicted rooms into a tree (likely wither doors are drawn
		// black below instead).
		if (opt.showBlank() && p.roomDoors != null) {
			int doorColor = labelColor(st, Label.NORMAL);
			for (int[] d : p.roomDoors) {
				int i = d[0], dir = d[1];
				if (g.link(i, dir) != Link.NONE) continue;
				int j = g.neighbor(i, dir);
				if (j < 0 || g.tile(i).explored() || g.tile(j).explored()) continue;
				if (p.witherDoors != null && p.witherDoors[dir - 1][i] >= opt.witherMin()) continue;
				float tx = ox + g.x(i) * cell + inset, tz = oz + g.z(i) * cell + inset;
				if (dir == 1) rect(ctx, tx + tile, tz + (tile - door) / 2, cell - tile, door, doorColor);
				else rect(ctx, tx + (tile - door) / 2, tz + tile, door, cell - tile, doorColor);
			}
		}

		// Predicted wither doors (the rest of the Blood-rush path): solid, with a light outline so
		// they read as guesses.
		if (p.witherDoors != null) {
			for (int i = 0; i < g.size(); i++) {
				float tx = ox + g.x(i) * cell + inset, tz = oz + g.z(i) * cell + inset;
				if (p.witherDoors[0][i] >= opt.witherMin() && g.link(i, 1) == Link.NONE) {
					float dx = tx + tile, dz = tz + (tile - door) / 2;
					rect(ctx, dx - 1, dz - 1, cell - tile + 2, door + 2, 0xFFDDDDDD);
					rect(ctx, dx, dz, cell - tile, door, st.witherDoor | 0xFF000000);
				}
				if (p.witherDoors[1][i] >= opt.witherMin() && g.link(i, 2) == Link.NONE) {
					float dx = tx + (tile - door) / 2, dz = tz + tile;
					rect(ctx, dx - 1, dz - 1, door + 2, cell - tile + 2, 0xFFDDDDDD);
					rect(ctx, dx, dz, door, cell - tile, st.witherDoor | 0xFF000000);
				}
			}
		}

		for (int i = 0; i < g.size(); i++) {
			double[] probs = p.labels[i];
			if (probs == null || g.tile(i).explored()) continue;
			Tile t = g.tile(i);
			float tx = ox + g.x(i) * cell + inset;
			float tz = oz + g.z(i) * cell + inset;
			double blood = probs[Label.BLOOD.ordinal()];
			if (opt.bloodHeat() && blood >= 0.04) {
				int a = (int) Math.round(Math.min(1.0, 0.3 + blood * 1.8) * 128); // at most 50% opaque
				rect(ctx, tx, tz, tile, tile, (a << 24) | 0xFF0000);
			}
			if (i == p.bloodCell && g.tile(i) != Tile.BLOOD) outline(ctx, tx, tz, tile, 0xFFFF3030);

			// Shown in four groups: Room (normal, with its likely shape), 1x1 (Puzzle, Trap, Yellow
			// and Rare together), Blood and Fairy. The model still keeps the types apart.
			Group[] groups = groups(probs);
			Group best = groups[0];
			double conf = best.p();
			boolean known = t == Tile.UNKNOWN;
			if (!known || covered[i]) continue; // shown by the predicted completion above

			String l1 = best.name() + " " + Math.round(conf * 100) + "%";
			String l2 = null;
			if (best.name().equals("Room") && p.shapes[i] != null) {
				Shape s = p.bestShape(i);
				l2 = s.display + " " + Math.round(p.shapes[i][s.ordinal()] * 100) + "%";
			} else if (groups.length > 1 && groups[1].p() >= 0.1) {
				// Runner-up, so the player sees how close the call is.
				l2 = groups[1].name() + " " + Math.round(groups[1].p() * 100) + "%";
			}
			int color = (labelColor(st, best.color()) & 0x00FFFFFF) | 0x80000000; // predictions at 50%
			aboveQuestionMark(ctx, font, tx, tz, tile, l1, color, st.textScale);
		}
	}

	/**
	 * What the room at a cell could be: End (Puzzle/Trap/Yellow/Rare), Blood, Fairy, or a normal
	 * room of each shape (P(normal) x the shape's odds). Sums to 1.
	 */
	private static java.util.Map<String, Double> categories(Prediction p, int at) {
		java.util.Map<String, Double> m = new java.util.LinkedHashMap<>();
		double[] l = p.labels[at];
		if (l == null) return m;
		m.put("End", l[Label.PUZZLE.ordinal()] + l[Label.TRAP.ordinal()] + l[Label.YELLOW.ordinal()] + l[Label.RARE.ordinal()]);
		m.put("Blood", l[Label.BLOOD.ordinal()]);
		m.put("Fairy", l[Label.FAIRY.ordinal()]);
		double normal = l[Label.NORMAL.ordinal()];
		double[] sh = p.shapes[at];
		for (Shape s : Shape.ALL) {
			double v = normal * (sh == null ? (s == Shape.S1x1 ? 1 : 0) : sh[s.ordinal()]);
			if (v > 0) m.put(s.display, v);
		}
		return m;
	}

	private static boolean sameRoom(Prediction p, MapGrid g, int i, int dir) {
		int nb = g.neighbor(i, dir);
		return nb >= 0 && p.roomOf[nb] >= 0 && p.roomOf[nb] == p.roomOf[i] && !g.tile(nb).explored();
	}

	/** Puzzle, Trap, Yellow and Rare share the "End" (one-door 1x1) look. */
	private static Label groupColor(Label l) {
		return switch (l) {
			case PUZZLE, TRAP, YELLOW, RARE -> Label.PUZZLE;
			default -> l;
		};
	}

		/** A display group of labels: name, combined probability, and the label whose colour it uses. */
	private record Group(String name, double p, Label color) {
	}

	/** Groups sorted by probability, highest first (empty groups dropped). */
	private static Group[] groups(double[] probs) {
		double end = probs[Label.PUZZLE.ordinal()] + probs[Label.TRAP.ordinal()]
			+ probs[Label.YELLOW.ordinal()] + probs[Label.RARE.ordinal()];
		java.util.List<Group> g = new java.util.ArrayList<>(java.util.List.of(
			new Group("Room", probs[Label.NORMAL.ordinal()], Label.NORMAL),
			new Group("End", end, Label.PUZZLE),
			new Group("Blood", probs[Label.BLOOD.ordinal()], Label.BLOOD),
			new Group("Fairy", probs[Label.FAIRY.ordinal()], Label.FAIRY),
			new Group("-", probs[Label.EMPTY.ordinal()], Label.EMPTY)));
		g.removeIf(x -> x.p() <= 0);
		g.sort((a, b) -> Double.compare(b.p(), a.p()));
		return g.toArray(new Group[0]);
	}

	/**
	 * Nav: the doors on the route from the player's cell to Blood if its location is known, else
	 * to the nearest room with an unopened wither door (the next door of the Blood rush), drawn as
	 * wither doors (same layout as {@link #draw}).
	 */
	public static void drawNav(GuiGraphicsExtractor ctx, MapGrid g, float x, float y, float size, Config.Style st,
							   int fromCell) {
		if (fromCell < 0) return;
		java.util.List<int[]> route = null;
		int blood = g.knownBlood();
		if (blood >= 0) {
			route = g.routeDoors(fromCell, blood);
		} else {
			for (int target : g.unopenedWitherRooms()) {
				if (target == fromCell || (g.component(target) >= 0 && g.component(target) == g.component(fromCell))) {
					return; // already standing in that room
				}
				java.util.List<int[]> r = g.routeDoors(fromCell, target);
				if (!r.isEmpty() && (route == null || r.size() < route.size())) route = r;
			}
		}
		if (route == null || route.isEmpty()) return;
		Layout lay = layout(g, x, y, size, st);
		float cell = lay.cell, tile = lay.tile, inset = lay.inset;
		float door = Math.max(1, Math.round(cell * st.doorSize));
		// Drawn exactly like wither doors: the route to Blood is the wither-door path.
		int color = st.witherDoor | 0xFF000000;
		for (int[] d : route) {
			int a = d[0], dir = d[1];
			int b = g.neighbor(a, dir);
			int left = (dir == 1 || dir == 2) ? a : b; // links are drawn from the left/top cell
			float tx = lay.ox + g.x(left) * cell + inset, tz = lay.oz + g.z(left) * cell + inset;
			boolean horizontal = dir == 1 || dir == 3;
			float dx = horizontal ? tx + tile : tx + (tile - door) / 2;
			float dz = horizontal ? tz + (tile - door) / 2 : tz + tile;
			rect(ctx, dx, dz, horizontal ? cell - tile : door, horizontal ? door : cell - tile, color);
		}
	}

	/** A player to draw: position in grid-cell units (cell centre = i + 0.5), or a skin for a head. */
	public record Marker(float cellX, float cellZ, float yawDeg, net.minecraft.world.entity.player.PlayerSkin skin, String name) {
	}

	/**
	 * Map geometry snapped to whole GUI pixels. With fractional cells, a gap's right edge and the
	 * next tile's left edge can land on x.5 and round in opposite directions, leaving a 1-pixel
	 * seam through rooms; integer sizes make every edge exact.
	 */
	record Layout(float ox, float oz, float cell, float tile, float inset) {
	}

	static Layout layout(MapGrid g, float x, float y, float size, Config.Style st) {
		int dim = Math.max(g.w, g.h);
		int cell = Math.max(4, (int) Math.floor(size / dim));
		int tile = Math.max(2, Math.round(cell * st.roomSize));
		int inset = (cell - tile) / 2;
		int ox = Math.round(x + (size - g.w * cell) / 2f);
		int oz = Math.round(y + (size - g.h * cell) / 2f);
		return new Layout(ox, oz, cell, tile, inset);
	}

	/** Player markers on top of a map drawn with the same x/y/size. */
	public static void drawPlayers(GuiGraphicsExtractor ctx, Font font, MapGrid g, float x, float y, float size,
								   Config.Style st, java.util.List<Marker> markers, boolean names) {
		Layout lay = layout(g, x, y, size, st);
		float cell = lay.cell, ox = lay.ox, oz = lay.oz;
		for (Marker m : markers) {
			if (m.cellX() < 0 || m.cellZ() < 0 || m.cellX() > g.w || m.cellZ() > g.h) continue; // off the grid
			float px = ox + m.cellX() * cell;
			float pz = oz + m.cellZ() * cell;
			if (m.skin() != null) {
				// The head turns with the player: its top points where they look. Yaw 180 (north)
				// is upright; screen y points down, so a positive angle turns it clockwise.
				int hs = Math.max(4, Math.round(cell * st.headSize));
				ctx.pose().pushMatrix();
				ctx.pose().translate(px, pz);
				ctx.pose().rotate((float) Math.toRadians(m.yawDeg() - 180));
				int h0 = -hs / 2;
				ctx.fill(h0 - 1, h0 - 1, h0 + hs + 1, h0 + hs + 1, 0xFF000000);
				net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(ctx, m.skin(), h0, h0, hs);
				ctx.pose().popMatrix();
			} else {
				float d = Math.max(3, cell * st.headSize * 0.45f);
				rect(ctx, px - d / 2 - 1, pz - d / 2 - 1, d + 2, d + 2, 0xFF000000);
				rect(ctx, px - d / 2, pz - d / 2, d, d, 0xFF55FFFF);
			}
			if (names && m.name() != null) {
				float s = Math.max(0.35f, Math.min(0.8f, cell / 40f));
				float hs = Math.max(4, cell * st.headSize);
				line(ctx, font, m.name(), px, pz + hs / 2 + 1, s, 0xFFFFFFFF);
			}
		}
	}

	private static void text(GuiGraphicsExtractor ctx, Font font, float tx, float tz, float tile, String l1, String l2, int color, float scale) {
		int lines = l2 == null ? 1 : 2;
		// Same size as room names: what fits two lines in one tile, times the text scale.
		float s = tile / (font.lineHeight * 2 + 2) * scale;
		if (s <= 0.05f) return;
		float total = font.lineHeight * lines * s;
		float y0 = tz + (tile - total) / 2;
		line(ctx, font, l1, tx + tile / 2, y0, s, color);
		if (l2 != null) line(ctx, font, l2, tx + tile / 2, y0 + font.lineHeight * s, s, (color & 0xFF000000) | 0xDDDDDD);
	}

	private static void line(GuiGraphicsExtractor ctx, Font font, String str, float cx, float y, float s, int color) {
		ctx.pose().pushMatrix();
		ctx.pose().translate(cx - font.width(str) * s / 2, y);
		ctx.pose().scale(s, s);
		ctx.text(font, str, 0, 0, color, true);
		ctx.pose().popMatrix();
	}

	/**
	 * Hypixel's checkmark, pixel for pixel as the map item draws it: a 9x9 tick centred in the
	 * 16-pixel room tile (white = cleared, green = all secrets). The failed cross isn't captured
	 * yet, so it uses the same 9x9 grid and stroke width.
	 */
	private static final String[] TICK = {
		".......##",
		"......###",
		".....###.",
		"....###..",
		"##.###...",
		"#####....",
		"####.....",
		".###.....",
		".##......"};
	private static final String[] CROSS = {
		"##.....##",
		"###...###",
		".###.###.",
		"..#####..",
		"...###...",
		"..#####..",
		".###.###.",
		"###...###",
		"##.....##"};

	/**
	 * Per opened room, like Devonian's map: the checkmark icon in the middle of the room and the
	 * room name centred on its top-left tile. Rectangular rooms put the icon in the middle of their
	 * whole area; L rooms on their middle tile.
	 */
	private static void drawRoomLabels(GuiGraphicsExtractor ctx, Font font, MapGrid g, Options opt,
									   float ox, float oz, float cell, float tile, float inset) {
		String[] names = opt.roomNames() != null && opt.roomNames().length == g.size() ? opt.roomNames() : null;
		boolean[] done = new boolean[g.size() + 1];
		for (int i = 0; i < g.size(); i++) {
			Tile t = g.tile(i);
			if (!t.explored()) continue;
			int comp = g.component(i);
			if (comp >= 0) {
				if (done[comp]) continue;
				done[comp] = true;
			}
			java.util.List<int[]> cells = g.componentCells(i);
			int minX = 99, minZ = 99, maxX = -1, maxZ = -1;
			float sx = 0, sz = 0;
			for (int[] c : cells) {
				minX = Math.min(minX, c[0]);
				maxX = Math.max(maxX, c[0]);
				minZ = Math.min(minZ, c[1]);
				maxZ = Math.max(maxZ, c[1]);
				sx += c[0];
				sz += c[1];
			}
			float cx, cz;
			int[] nameTile = {minX, minZ};
			if ((maxX - minX + 1) * (maxZ - minZ + 1) == cells.size()) {
				cx = ox + (minX + maxX + 1) / 2f * cell;
				cz = oz + (minZ + maxZ + 1) / 2f * cell;
			} else {
				float mx = sx / cells.size(), mz = sz / cells.size();
				int[] best = cells.getFirst();
				for (int[] c : cells) {
					if (Math.hypot(c[0] - mx, c[1] - mz) < Math.hypot(best[0] - mx, best[1] - mz)) best = c;
				}
				cx = ox + (best[0] + 0.5f) * cell;
				cz = oz + (best[1] + 0.5f) * cell;
				// Top-left tile that actually belongs to the room.
				nameTile = cells.getFirst();
				for (int[] c : cells) if (c[1] < nameTile[1] || (c[1] == nameTile[1] && c[0] < nameTile[0])) nameTile = c;
			}

			Check check = g.check(i);
			if (opt.showChecks() && check != Check.NONE) {
				float size = tile * opt.style().iconSize;
				// The map palette colours Hypixel uses for the icons.
				int color = switch (check) {
					case GREEN -> 0xFF007C00;
					case FAILED -> 0xFFFF0000;
					default -> 0xFFFFFFFF;
				};
				icon(ctx, check == Check.FAILED ? CROSS : TICK, cx - size / 2, cz - size / 2, size, color);
			}

			String name = names == null ? null : names[i];
			if (name == null) continue;
			if (opt.hideCommonNames() && (t == Tile.ENTRANCE || t == Tile.FAIRY || t == Tile.BLOOD)) continue;
			// Same size for every room: what fits two lines in one tile, times the text scale.
			float s = tile / (font.lineHeight * 2 + 2) * opt.style().textScale;
			if (s <= 0.05f) continue;
			int color = !opt.colorRoomNames() ? 0xFFFFFFFF : switch (check) {
				case GREEN -> 0xFF55FF55;
				case WHITE -> 0xFFFFFFFF;
				case FAILED -> 0xFFFF5555;
				case NONE -> 0xFFAAAAAA;
			};
			// The Entrance never gets a check icon (its tile is the check's green), so its name
			// always uses the green-check colour.
			if (t == Tile.ENTRANCE && opt.colorRoomNames()) color = 0xFF55FF55;
			// Centred on the room's top-left tile.
			float ncx = ox + (nameTile[0] + 0.5f) * cell, ncz = oz + (nameTile[1] + 0.5f) * cell;
			String[] lines = wrap(font, name);
			float y0 = ncz - font.lineHeight * lines.length * s / 2;
			for (int k = 0; k < lines.length; k++) line(ctx, font, lines[k], ncx, y0 + k * font.lineHeight * s, s, color);
		}
	}

	/** The vanilla map item's "?" (5x9 pixels at (5,5) of a 16-pixel tile), in black. */
	private static final String[] QUESTION = {
		".###.",
		"#...#",
		"....#",
		"...#.",
		"..#..",
		"..#..",
		".....",
		"..#..",
		"..#.."};

	/**
	 * One line of text in the strip above the "?" glyph (the top 5 of the tile's 16 pixels): at the
	 * usual text size, or smaller if it wouldn't fit there.
	 */
	private static void aboveQuestionMark(GuiGraphicsExtractor ctx, Font font, float tx, float tz, float tile,
										  String str, int color, float scale) {
		float band = tile * 5f / 16f;
		float s = Math.min(tile / (font.lineHeight * 2 + 2) * scale, (band - 1) / font.lineHeight);
		s = Math.min(s, (tile - 2) / Math.max(1, font.width(str)));
		if (s <= 0.05f) return;
		line(ctx, font, str, tx + tile / 2, tz + (band - font.lineHeight * s) / 2 + 0.5f, s, color);
	}

	private static void questionMark(GuiGraphicsExtractor ctx, float tx, float tz, float tile) {
		float u = tile / 16f;
		for (int r = 0; r < QUESTION.length; r++) {
			for (int c = 0; c < QUESTION[r].length(); c++) {
				if (QUESTION[r].charAt(c) == '#') rect(ctx, tx + (5 + c) * u, tz + (5 + r) * u, u, u, 0xFF000000);
			}
		}
	}

	/** A 9x9 pixel icon, no outline (as on the map item). */
	private static void icon(GuiGraphicsExtractor ctx, String[] mask, float x, float y, float size, int color) {
		float u = size / mask.length;
		for (int r = 0; r < mask.length; r++) {
			for (int c = 0; c < mask[r].length(); c++) {
				if (mask[r].charAt(c) == '#') rect(ctx, x + c * u, y + r * u, u, u, color);
			}
		}
	}

	/** Splits a multi-word name into two lines of similar width. */
	private static String[] wrap(Font font, String name) {
		int sp = -1, bestDiff = Integer.MAX_VALUE;
		for (int i = name.indexOf(' '); i >= 0; i = name.indexOf(' ', i + 1)) {
			int diff = Math.abs(font.width(name.substring(0, i)) - font.width(name.substring(i + 1)));
			if (diff < bestDiff) {
				bestDiff = diff;
				sp = i;
			}
		}
		return sp < 0 ? new String[]{name} : new String[]{name.substring(0, sp), name.substring(sp + 1)};
	}

	private static boolean contains(int[] a, int v) {
		if (v < 0) return false;
		for (int x : a) if (x == v) return true;
		return false;
	}

	private static void rect(GuiGraphicsExtractor ctx, float x, float y, float w, float h, int color) {
		int x0 = Math.round(x), y0 = Math.round(y);
		int x1 = Math.round(x + w), y1 = Math.round(y + h);
		if (x1 > x0 && y1 > y0) ctx.fill(x0, y0, x1, y1, color);
	}

	private static void outline(GuiGraphicsExtractor ctx, float x, float y, float s, int color) {
		rect(ctx, x - 1, y - 1, s + 2, 1, color);
		rect(ctx, x - 1, y + s, s + 2, 1, color);
		rect(ctx, x - 1, y, 1, s, color);
		rect(ctx, x + s, y, 1, s, color);
	}
}

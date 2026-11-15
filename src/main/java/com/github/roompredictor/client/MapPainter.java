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

		if (p == null || p.grid != g) return;

		// Predicted room shapes: each "?" guessed to be a normal room is drawn as a solid grey room
		// of its most likely shape, outlined in the prediction colour so it reads as a guess.
		boolean[] underGhost = new boolean[g.size()];
		int ghost = st.unknown | 0xFF000000;
		int edge = labelColor(st, Label.NORMAL);
		for (int i = 0; i < g.size(); i++) {
			int[] fp = p.footprints[i];
			if (fp == null || fp.length < 2) continue;
			for (int j : fp) {
				if (j != i) underGhost[j] = true;
				float gx = ox + g.x(j) * cell + inset, gz = oz + g.z(j) * cell + inset;
				boolean east = contains(fp, g.neighbor(j, 1)), south = contains(fp, g.neighbor(j, 2));
				boolean west = contains(fp, g.neighbor(j, 3)), north = contains(fp, g.neighbor(j, 0));
				rect(ctx, gx, gz, tile, tile, ghost);
				if (east) rect(ctx, gx + tile, gz, cell - tile, tile, ghost);
				if (south) rect(ctx, gx, gz + tile, tile, cell - tile, ghost);
				int es = east ? g.neighbor(g.neighbor(j, 1), 2) : -1;
				if (east && south && contains(fp, es)) rect(ctx, gx + tile, gz + tile, cell - tile, cell - tile, ghost);
				// Outline the outer edges of the footprint.
				float w = east ? cell : tile, h = south ? cell : tile;
				if (!north) rect(ctx, gx, gz, w, 1, edge);
				if (!south) rect(ctx, gx, gz + tile - 1, w, 1, edge);
				if (!west) rect(ctx, gx, gz, 1, h, edge);
				if (!east) rect(ctx, gx + tile - 1, gz, 1, h, edge);
			}
		}

		// Predicted wither doors (the rest of the Blood-rush path): solid, with a light outline so
		// they read as guesses.
		if (p.witherDoors != null) {
			for (int i = 0; i < g.size(); i++) {
				float tx = ox + g.x(i) * cell + inset, tz = oz + g.z(i) * cell + inset;
				if (p.witherDoors[0][i] >= opt.witherMin()) {
					float dx = tx + tile, dz = tz + (tile - door) / 2;
					rect(ctx, dx - 1, dz - 1, cell - tile + 2, door + 2, 0xFFDDDDDD);
					rect(ctx, dx, dz, cell - tile, door, st.witherDoor | 0xFF000000);
				}
				if (p.witherDoors[1][i] >= opt.witherMin()) {
					float dx = tx + (tile - door) / 2, dz = tz + tile;
					rect(ctx, dx - 1, dz - 1, door + 2, cell - tile + 2, 0xFFDDDDDD);
					rect(ctx, dx, dz, door, cell - tile, st.witherDoor | 0xFF000000);
				}
			}
		}

		for (int i = 0; i < g.size(); i++) {
			double[] probs = p.labels[i];
			if (probs == null) continue;
			Tile t = g.tile(i);
			float tx = ox + g.x(i) * cell + inset;
			float tz = oz + g.z(i) * cell + inset;
			double blood = probs[Label.BLOOD.ordinal()];
			if (opt.bloodHeat() && blood >= 0.04) {
				int a = (int) Math.round(Math.min(1.0, 0.3 + blood * 1.8) * 210);
				rect(ctx, tx, tz, tile, tile, (a << 24) | 0xFF0000);
			}
			if (i == p.bloodCell && g.tile(i) != Tile.BLOOD) outline(ctx, tx, tz, tile, 0xFFFF3030);

			Label best = p.best(i);
			if (best == null) continue;
			double conf = probs[best.ordinal()];
			boolean known = t == Tile.UNKNOWN;
			if (!known && (underGhost[i] || !opt.showBlank() || conf < opt.blankMinConfidence() || best == Label.EMPTY)) continue;

			String l1 = best.abbrev + " " + Math.round(conf * 100) + "%";
			String l2 = null;
			if (best == Label.NORMAL && p.shapes[i] != null) {
				Shape s = p.bestShape(i);
				l2 = s.display + " " + Math.round(p.shapes[i][s.ordinal()] * 100) + "%";
			} else if (best != Label.NORMAL) {
				// Runner-up, so the player sees how close the call is.
				Label second = null;
				for (Label l : Label.ALL) {
					if (l == best || probs[l.ordinal()] <= 0) continue;
					if (second == null || probs[l.ordinal()] > probs[second.ordinal()]) second = l;
				}
				if (second != null && probs[second.ordinal()] >= 0.1) {
					l2 = second.abbrev + " " + Math.round(probs[second.ordinal()] * 100) + "%";
				}
			}
			int color = labelColor(st, best);
			if (!known) color = (color & 0x00FFFFFF) | 0xDD000000;
			text(ctx, font, tx, tz, tile, l1, l2, color, st.textScale);
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
			// Centred on the room's top-left tile.
			float ncx = ox + (nameTile[0] + 0.5f) * cell, ncz = oz + (nameTile[1] + 0.5f) * cell;
			String[] lines = wrap(font, name);
			float y0 = ncz - font.lineHeight * lines.length * s / 2;
			for (int k = 0; k < lines.length; k++) line(ctx, font, lines[k], ncx, y0 + k * font.lineHeight * s, s, color);
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

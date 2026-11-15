package com.github.roompredictor.core;

import java.util.List;

/**
 * Inference over every unexplored cell of the grid.
 *
 * <ol>
 *   <li>Each candidate cell gets features from the visible map and a masked softmax from the
 *       {@link Model} (hard rules remove impossible labels).</li>
 *   <li>A Sinkhorn-style balancing step makes the probabilities agree with how many of each
 *       special room are still missing (e.g. exactly one Blood across all candidate cells).</li>
 *   <li>Steps 1-2 repeat a few rounds; each round's beliefs about other cells feed the "soft"
 *       features of the next (e.g. a "?" that is probably Fairy pulls Blood toward cells 3 tiles
 *       away), so predictions extend beyond the rooms next to explored ones.</li>
 *   <li>Normal rooms also get a shape distribution from the expected shape counts, how many of
 *       each shape are already explored (max 2 per shape) and which shapes physically fit.</li>
 * </ol>
 */
public final class Predictor {
	public static final int ROUNDS = 4;
	private static final int BALANCE_ITERATIONS = 40;

	private Predictor() {
	}

	/**
	 * @param puzzlesFromTab total puzzle count from the tab list, or 0 if unknown
	 */
	public static Prediction predict(MapGrid g, Model model, int puzzlesFromTab) {
		return predict(g, model, puzzlesFromTab, -1);
	}

	/**
	 * @param puzzlesUnknown puzzles the tab list still shows as "???" (undiscovered), or -1
	 */
	public static Prediction predict(MapGrid g, Model model, int puzzlesFromTab, int puzzlesUnknown) {
		long start = System.nanoTime();
		Floor floor = g.floor;
		int n = g.size();
		int bracket = floor.bracket();

		int puzzleTarget = puzzlesFromTab > 0 ? puzzlesFromTab : (int) Math.round(model.expectedPuzzles(floor));
		boolean rareAllowed = puzzlesFromTab > 0 ? puzzlesFromTab >= 5 : puzzleTarget >= 5;

		// How many of each special room are still unaccounted for.
		double[] target = new double[Label.COUNT];
		target[Label.BLOOD.ordinal()] = g.count(Tile.BLOOD) > 0 ? 0 : 1;
		target[Label.YELLOW.ordinal()] = g.count(Tile.YELLOW) > 0 ? 0 : 1;
		target[Label.FAIRY.ordinal()] = g.count(Tile.FAIRY) > 0 ? 0 : 1;
		target[Label.TRAP.ordinal()] = floor.hasTrap() && g.count(Tile.TRAP) == 0 ? 1 : 0;
		target[Label.PUZZLE.ordinal()] = Math.max(0, puzzleTarget - g.countRooms(Tile.PUZZLE));
		// The tab list's "???" count is the server's own number of puzzles still undiscovered.
		if (puzzlesUnknown >= 0) target[Label.PUZZLE.ordinal()] = Math.min(target[Label.PUZZLE.ordinal()], puzzlesUnknown);
		boolean[] hard = new boolean[Label.COUNT];
		hard[Label.BLOOD.ordinal()] = hard[Label.YELLOW.ordinal()] = hard[Label.FAIRY.ordinal()] = true;
		hard[Label.TRAP.ordinal()] = hard[Label.PUZZLE.ordinal()] = true;

		boolean[] cand = new boolean[n];
		for (int i = 0; i < n; i++) cand[i] = !g.tile(i).explored();

		int entrance = g.entrance();
		int[] walk = g.walkDistances(entrance);
		int fairyCell = g.find(Tile.FAIRY);
		boolean bloodFound = target[Label.BLOOD.ordinal()] == 0;

		int[] fairyWalk = fairyCell >= 0 ? g.walkDistances(fairyCell) : null;
		int[] roomEntr = g.roomBetween(entrance);
		int[] roomFairy = fairyCell >= 0 ? g.roomBetween(fairyCell) : null;
		Path path = path(g);
		int soleUnknown = soleUnknownWithBlanks(g);
		boolean[] mustBranch = mustLeadOnward(g);

		boolean[][] masks = new boolean[n][];
		int[] walled = new int[n];
		int[] doors = new int[n];
		for (int i = 0; i < n; i++) {
			if (!cand[i]) continue;
			doors[i] = g.doorCount(i);
			walled[i] = walls(g, i);
			masks[i] = mask(g, i, floor, target, rareAllowed, doors[i], bloodFound);
			applyTreeRules(masks[i], g, i, path);
			if (i == soleUnknown) for (Label l : Label.ALL) masks[i][l.ordinal()] = l == Label.NORMAL;
			if (mustBranch[i]) for (Label l : Label.ALL) if (l.isEndRoom()) masks[i][l.ordinal()] = false;
		}

		double[][] p = new double[n][];
		Features[] feats = new Features[n];
		for (int round = 0; round < ROUNDS; round++) {
			double[][] next = new double[n][];
			for (int i = 0; i < n; i++) {
				if (!cand[i]) continue;
				Features f = new Features();
				fill(f, g, i, bracket, entrance, walk, fairyCell, walled[i], doors[i], round == 0 ? null : p, cand);
				addPathFeatures(f, g, i, path, walk, fairyWalk, roomEntr, roomFairy);
				feats[i] = f;
				next[i] = Model.softmax(model.logits(f), masks[i]);
			}
			balance(next, cand, target, hard);
			p = next;
		}

		double[][] shapes = shapes(g, model, cand, p, soleUnknown);
		int[][] footprints = new int[n][];
		for (int i = 0; i < n; i++) {
			if (g.tile(i) != Tile.UNKNOWN || shapes[i] == null || p[i] == null) continue;
			int bestLabel = 0;
			for (int k = 1; k < Label.COUNT; k++) if (p[i][k] > p[i][bestLabel]) bestLabel = k;
			if (bestLabel != Label.NORMAL.ordinal()) continue;
			int bestShape = 0;
			for (int k = 1; k < Shape.COUNT; k++) if (shapes[i][k] > shapes[i][bestShape]) bestShape = k;
			footprints[i] = bestPlacement(g, i, Shape.ALL[bestShape], cand, p);
		}

		int bloodCell = -1;
		double bloodP = 0;
		for (int i = 0; i < n; i++) {
			if (!cand[i]) continue;
			double b = p[i][Label.BLOOD.ordinal()];
			if (b > bloodP) {
				bloodP = b;
				bloodCell = i;
			}
		}
		if (bloodFound) {
			bloodCell = g.find(Tile.BLOOD);
			bloodP = 1;
		}

		double[][] wither = witherDoors(g, p, path, walk);
		Completion completion = complete(g, model, p, shapes, cand, target, rareAllowed);
		return new Prediction(g, p, shapes, footprints, completion, wither, feats, masks, bloodCell, bloodP, puzzleTarget, puzzlesFromTab > 0,
			model.updates, System.nanoTime() - start);
	}

	/**
	 * The placement of {@code s} covering cell {@code i} whose other cells are most likely to be
	 * normal rooms. Returns the covered cell indices, or null if the shape doesn't fit.
	 */
	static int[] bestPlacement(MapGrid g, int i, Shape s, boolean[] cand, double[][] p) {
		int ci = g.x(i), cz = g.z(i);
		List<int[]> own = g.componentCells(i);
		int[] best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (int[][] o : s.orientations()) {
			for (int[] anchor : o) {
				int ox = ci - anchor[0], oz = cz - anchor[1];
				if (!fits(g, o, ox, oz, cand, own)) continue;
				int[] cells = new int[o.length];
				double score = 0;
				for (int k = 0; k < o.length; k++) {
					int j = g.idx(ox + o[k][0], oz + o[k][1]);
					cells[k] = j;
					if (j != i && p[j] != null) score += Math.log(Math.max(1e-6, p[j][Label.NORMAL.ordinal()]));
				}
				if (score > bestScore) {
					bestScore = score;
					best = cells;
				}
			}
		}
		return best;
	}

	/** A predicted completion of the map (see {@link Prediction#roomOf}). */
	/** {@code typeP}: chance the room is its type; {@code shapeP}: chance it has its shape. */
	public record Completion(int[] roomOf, Label[] label, Shape[] shape, double[] typeP, double[] shapeP) {
	}

	/**
	 * Fills every unopened cell with a predicted room. Special rooms (the missing Blood, Fairy,
	 * Trap, Yellow, Puzzles and likely Rares) go on their most likely cells first, one per cell;
	 * then the expected big rooms are placed largest first where they fit best (never crossing a
	 * known door, at most 2 per shape, within the big-room limits); what's left is normal 1x1s.
	 */
	static Completion complete(MapGrid g, Model model, double[][] p, double[][] shapeP, boolean[] cand, double[] target, boolean rareAllowed) {
		int n = g.size();
		int[] roomOf = new int[n];
		java.util.Arrays.fill(roomOf, -1);
		List<Label> labels = new java.util.ArrayList<>();
		List<Shape> shapes = new java.util.ArrayList<>();
		List<Double> probs = new java.util.ArrayList<>();
		List<Double> shapeProbs = new java.util.ArrayList<>();
		boolean[] free = new boolean[n];
		for (int i = 0; i < n; i++) free[i] = cand[i] && p[i] != null;

		// Extra puzzle column: a puzzle or nothing.
		for (int i = 0; i < n; i++) {
			if (!free[i] || !g.floor.isExtraColumn(g.x(i))) continue;
			if (p[i][Label.PUZZLE.ordinal()] <= p[i][Label.EMPTY.ordinal()]) free[i] = false;
		}

		// 1) Special rooms, most confident first.
		int[] left = new int[Label.COUNT];
		for (Label l : new Label[]{Label.BLOOD, Label.FAIRY, Label.TRAP, Label.YELLOW, Label.PUZZLE}) {
			left[l.ordinal()] = (int) Math.round(target[l.ordinal()]);
		}
		if (rareAllowed) {
			double rare = 0;
			for (int i = 0; i < n; i++) if (free[i]) rare += p[i][Label.RARE.ordinal()];
			left[Label.RARE.ordinal()] = (int) Math.round(rare);
		}
		List<double[]> picks = new java.util.ArrayList<>();
		for (int i = 0; i < n; i++) {
			if (!free[i]) continue;
			for (Label l : new Label[]{Label.BLOOD, Label.FAIRY, Label.TRAP, Label.YELLOW, Label.PUZZLE, Label.RARE}) {
				if (p[i][l.ordinal()] > 0.02) picks.add(new double[]{p[i][l.ordinal()], i, l.ordinal()});
			}
		}
		picks.sort((a, b) -> Double.compare(b[0], a[0]));
		for (double[] pk : picks) {
			int i = (int) pk[1], k = (int) pk[2];
			if (!free[i] || left[k] <= 0) continue;
			// Only if it beats this cell being a normal room by enough to be worth committing.
			if (pk[0] < p[i][Label.NORMAL.ordinal()] * 0.25) continue;
			free[i] = false;
			left[k]--;
			roomOf[i] = labels.size();
			labels.add(Label.ALL[k]);
			shapes.add(Shape.S1x1);
			// Confidence shown for an end room is for the whole "End" group.
			probs.add(Label.ALL[k].isEndRoom() && k != Label.BLOOD.ordinal() ? endP(p[i]) : pk[0]);
			shapeProbs.add(shapeChance(p, shapeP, i, Shape.S1x1));
		}
		for (int i = 0; i < n; i++) if (g.floor.isExtraColumn(g.x(i))) free[i] = false;

		// 2) Big rooms, largest shapes first.
		int bracket = g.floor.bracket();
		double[] expected = model.expectedShapes(bracket);
		int[] seen = exploredShapeCounts(g);
		int bigRooms = 0, bigTiles = 0;
		for (Shape s : Shape.ALL) {
			if (s == Shape.S1x1) continue;
			bigRooms += seen[s.ordinal()];
			bigTiles += seen[s.ordinal()] * s.size();
		}
		int roomLimit = model.bigRoomLimit(bracket), tileLimit = model.bigTileLimit(bracket);
		double tileTarget = model.bigTileTarget(bracket);
		int[] quota = new int[Shape.COUNT];
		for (Shape s : Shape.ALL) {
			if (s == Shape.S1x1) continue;
			int q = (int) Math.round(Math.max(0, expected[s.ordinal()] - seen[s.ordinal()]));
			quota[s.ordinal()] = Math.min(q, Shape.MAX_PER_DUNGEON - seen[s.ordinal()]);
		}
		// Each step, place the single best-scoring big room of any shape that still has quota.
		// Score per tile: how likely its cells are normal rooms, plus, on "?" cells, how much that
		// cell's own shape odds favour this shape (so a "?" that looks like an L becomes one).
		while (true) {
			int[] best = null;
			Shape bestShape = null;
			double bestScore = Double.NEGATIVE_INFINITY;
			for (Shape s : Shape.ALL) {
				if (s == Shape.S1x1 || quota[s.ordinal()] <= 0) continue;
				if (bigRooms >= roomLimit || bigTiles + s.size() > tileLimit) continue;
				if (tileTarget > 0 && bigTiles + s.size() > tileTarget + 1) continue;
				for (int[][] o : s.orientations()) {
					for (int oz = 0; oz < g.h; oz++) {
						for (int ox = 0; ox < g.floor.standardWidth; ox++) {
							if (!fits(g, o, ox, oz, free, List.of())) continue;
							int[] cells = new int[o.length];
							double score = 0;
							for (int k = 0; k < o.length; k++) {
								int c = g.idx(ox + o[k][0], oz + o[k][1]);
								cells[k] = c;
								score += Math.log(Math.max(1e-6, p[c][Label.NORMAL.ordinal()]));
								if (g.tile(c) == Tile.UNKNOWN && shapeP[c] != null) {
									score += Math.log(Math.max(1e-3, shapeP[c][s.ordinal()] * Shape.COUNT));
								}
							}
							score /= o.length;
							if (score > bestScore) {
								bestScore = score;
								best = cells;
								bestShape = s;
							}
						}
					}
				}
			}
			if (best == null) break;
			for (int c : best) {
				free[c] = false;
				roomOf[c] = labels.size();
			}
			labels.add(Label.NORMAL);
			shapes.add(bestShape);
			int at = anchor(g, best);
			probs.add(p[at][Label.NORMAL.ordinal()]);
			shapeProbs.add(shapeChance(p, shapeP, at, bestShape));
			quota[bestShape.ordinal()]--;
			bigRooms++;
			bigTiles += bestShape.size();
		}

		// 3) The rest: normal 1x1s.
		for (int i = 0; i < n; i++) {
			if (!free[i]) continue;
			roomOf[i] = labels.size();
			labels.add(Label.NORMAL);
			shapes.add(Shape.S1x1);
			probs.add(p[i][Label.NORMAL.ordinal()]);
			shapeProbs.add(shapeChance(p, shapeP, i, Shape.S1x1));
		}
		double[] pa = new double[probs.size()], sa = new double[probs.size()];
		for (int i = 0; i < pa.length; i++) {
			pa[i] = probs.get(i);
			sa[i] = shapeProbs.get(i);
		}
		return new Completion(roomOf, labels.toArray(new Label[0]), shapes.toArray(new Shape[0]), pa, sa);
	}

	/** Puzzle + Trap + Yellow + Rare: the "End" (one-door 1x1) group. */
	static double endP(double[] p) {
		return p[Label.PUZZLE.ordinal()] + p[Label.TRAP.ordinal()] + p[Label.YELLOW.ordinal()] + p[Label.RARE.ordinal()];
	}

	/** Where a predicted room's evidence is: its "?" cell if it has one, otherwise its first cell. */
	static int anchor(MapGrid g, int[] cells) {
		int at = cells[0];
		for (int c : cells) if (g.tile(c) == Tile.UNKNOWN) at = c;
		return at;
	}

	/**
	 * Chance the room at cell {@code at} has shape {@code s}: special rooms (End, Blood, Fairy) are
	 * always 1x1, plus P(normal) times the normal room's shape odds. A cell boxed in on every side
	 * can only be a 1x1, so it comes out at 100%.
	 */
	static double shapeChance(double[][] p, double[][] shapeP, int at, Shape s) {
		double[] l = p[at];
		double shape = shapeP[at] == null ? (s == Shape.S1x1 ? 1 : 0) : shapeP[at][s.ordinal()];
		double special = endP(l) + l[Label.BLOOD.ordinal()] + l[Label.FAIRY.ordinal()];
		return Math.min(1, (s == Shape.S1x1 ? special : 0) + l[Label.NORMAL.ordinal()] * shape);
	}

	/** Sides of a cell that are certainly walls: off-grid, or an explored room with no link. */
	static int walls(MapGrid g, int i) {
		int c = 0;
		for (int d = 0; d < 4; d++) {
			int nb = g.neighbor(i, d);
			if (nb < 0) {
				c++;
				continue;
			}
			if (g.tile(nb).explored() && g.link(i, d) == Link.NONE) c++;
		}
		return c;
	}

	static boolean[] mask(MapGrid g, int i, Floor floor, double[] target, boolean rareAllowed, int doors, boolean bloodFound) {
		boolean[] m = new boolean[Label.COUNT];
		if (floor.isExtraColumn(g.x(i))) {
			m[Label.EMPTY.ordinal()] = true;
			m[Label.PUZZLE.ordinal()] = target[Label.PUZZLE.ordinal()] > 0 && doors <= 1;
			return m;
		}
		for (Label l : Label.ALL) m[l.ordinal()] = true;
		m[Label.EMPTY.ordinal()] = false;
		m[Label.RARE.ordinal()] = rareAllowed;
		for (Label l : new Label[]{Label.BLOOD, Label.YELLOW, Label.FAIRY, Label.TRAP, Label.PUZZLE}) {
			if (target[l.ordinal()] <= 0) m[l.ordinal()] = false;
		}
		boolean multiTile = g.componentSize(i) > 1;
		if (multiTile) {
			for (Label l : Label.ALL) m[l.ordinal()] = l == Label.NORMAL;
			return m;
		}
		if (doors >= 2) for (Label l : Label.ALL) if (l.isEndRoom()) m[l.ordinal()] = false;
		boolean bloodDoor = false, otherDoor = false;
		for (int d = 0; d < 4; d++) {
			Link l = g.link(i, d);
			if (l == Link.BLOOD_DOOR) bloodDoor = true;
			else if (l.isDoor()) otherDoor = true;
		}
		if (!bloodFound && bloodDoor) {
			for (Label l : Label.ALL) m[l.ordinal()] = l == Label.BLOOD;
		} else if (otherDoor) {
			// Blood's only door is the red blood door.
			m[Label.BLOOD.ordinal()] = false;
		}
		return m;
	}

	/**
	 * The known part of the Blood-rush path: Entrance, then rooms joined by wither doors, the
	 * normal door into Fairy, more wither doors, and the blood door into Blood. Every room on it
	 * has one door in and one out.
	 *
	 * @param frontier       the unopened cell the path continues into, or -1
	 * @param fairyCandidate cells behind a normal door of the path's last room while Fairy is
	 *                       unknown (the path must continue into Fairy through one of them)
	 */
	record Path(int frontier, boolean[] fairyCandidate, int lastRoom, boolean[] region) {
		Path(int frontier, boolean[] fairyCandidate) {
			this(frontier, fairyCandidate, -1, null);
		}

		Path(int frontier, boolean[] fairyCandidate, int lastRoom) {
			this(frontier, fairyCandidate, lastRoom, null);
		}

		boolean anyFairyCandidate() {
			for (boolean b : fairyCandidate) if (b) return true;
			return false;
		}
	}

	/**
	 * Dungeon layouts have no loops: every unopened room hangs off the opened part through its own
	 * door, and everything beyond it can only be reached through it. So the rest of the Blood-rush
	 * path lies beyond the room(s) the path continues into: those cells plus the blank cells
	 * reachable from them without passing an opened room or another "?" (each other "?" is the
	 * root of a different branch). Returns null when the path's continuation isn't known.
	 */
	static boolean[] pathRegion(MapGrid g, int frontier, boolean[] fairyCand) {
		int n = g.size();
		boolean[] start = new boolean[n];
		boolean any = false;
		if (frontier >= 0) {
			start[frontier] = true;
			any = true;
		} else {
			for (int i = 0; i < n; i++) if (fairyCand[i]) start[i] = any = true;
		}
		if (!any) return null;
		boolean[] region = new boolean[n];
		java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
		for (int i = 0; i < n; i++) {
			if (start[i]) {
				region[i] = true;
				q.add(i);
			}
		}
		while (!q.isEmpty()) {
			int c = q.poll();
			for (int d = 0; d < 4; d++) {
				int nb = g.neighbor(c, d);
				if (nb < 0 || region[nb] || g.floor.isExtraColumn(g.x(nb))) continue;
				if (g.tile(nb) != Tile.EMPTY) continue; // opened rooms, and other "?" branches
				region[nb] = true;
				q.add(nb);
			}
		}
		return region;
	}

	/** Hard rules from the path and the no-loops tree structure. */
	static void applyTreeRules(boolean[] m, MapGrid g, int i, Path path) {
		if (path.region() != null && !path.region()[i]) {
			m[Label.BLOOD.ordinal()] = false;
			m[Label.FAIRY.ordinal()] = false;
		}
		// Through a normal door into Fairy: Fairy is one of those cells.
		if (path.anyFairyCandidate() && !path.fairyCandidate()[i]) m[Label.FAIRY.ordinal()] = false;
		// The path's next room behind a wither door isn't Fairy (Fairy's door in is a normal door).
		if (i == path.frontier()) m[Label.FAIRY.ordinal()] = false;
	}

	private static int anyCell(MapGrid g, int comp) {
		for (int j = 0; j < g.size(); j++) if (g.component(j) == comp) return j;
		return -1;
	}

	static Path path(MapGrid g) {
		int n = g.size();
		boolean[] fairyCand = new boolean[n];
		int e = g.entrance();
		if (e < 0) return new Path(-1, fairyCand);
		boolean fairyFound = g.count(Tile.FAIRY) > 0;
		java.util.Set<Integer> visited = new java.util.HashSet<>();
		int cur = g.component(e);
		visited.add(cur);
		boolean first = true;
		for (int guard = 0; guard < n; guard++) {
			List<Integer> outs = new java.util.ArrayList<>();
			List<Integer> normalOut = new java.util.ArrayList<>();
			for (int j = 0; j < n; j++) {
				if (g.component(j) != cur) continue;
				for (int d = 0; d < 4; d++) {
					int nb = g.neighbor(j, d);
					Link l = g.link(j, d);
					if (nb < 0 || !l.isDoor() || g.tile(nb) == Tile.EMPTY) continue;
					int nc = g.component(nb);
					if (nc >= 0 && visited.contains(nc)) continue;
					boolean onPath = first || l == Link.WITHER_DOOR || l == Link.BLOOD_DOOR || g.tile(nb) == Tile.FAIRY;
					if (onPath) {
						if (!outs.contains(nb)) outs.add(nb);
					} else if (l == Link.DOOR && !g.tile(nb).explored()) {
						normalOut.add(nb);
					}
				}
			}
			if (outs.size() != 1) {
				if (outs.isEmpty() && !fairyFound) for (int c : normalOut) fairyCand[c] = true;
				return new Path(-1, fairyCand, anyCell(g, cur), pathRegion(g, -1, fairyCand));
			}
			int next = outs.getFirst();
			if (!g.tile(next).explored()) return new Path(next, fairyCand, anyCell(g, cur), pathRegion(g, next, fairyCand));
			cur = g.component(next);
			visited.add(cur);
			first = false;
		}
		return new Path(-1, fairyCand);
	}

	/**
	 * Probability that each not-yet-visible door is a wither door, i.e. lies on the rest of the
	 * Blood-rush path. Routes run from where the known path ends, through a likely Fairy cell, to
	 * a likely Blood cell 2 or 4 cells beyond it (4 steps), weighted by those probabilities and by
	 * a total length near 10-11 steps; each route's weight goes to the doors along it. The door
	 * into Fairy (a normal door) and into Blood (the blood door) are not counted.
	 *
	 * @return {east, south} per cell, indexed like {@link MapGrid} links
	 */
	static double[][] witherDoors(MapGrid g, double[][] p, Path path, int[] walk) {
		int n = g.size();
		double[][] out = new double[2][n];
		if (g.count(Tile.BLOOD) > 0) return out;
		int start = path.frontier() >= 0 ? path.frontier() : path.lastRoom();
		if (start < 0) return out;
		int fairyCell = g.find(Tile.FAIRY);
		boolean[] pass = new boolean[n];
		for (int i = 0; i < n; i++) pass[i] = p[i] != null && !g.floor.isExtraColumn(g.x(i));
		pass[start] = true;
		// The rest of the path can't leave the branch it continues into (no loops).
		if (path.region() != null) for (int i = 0; i < n; i++) if (!path.region()[i] && i != start) pass[i] = false;
		if (fairyCell >= 0) pass[fairyCell] = true;
		int[] fromStart = bfsParents(g, start, pass);
		int[] distStart = bfsDist(g, start, fromStart);
		int base = walk[start] >= 0 ? walk[start] : 0;

		double total = 0;
		double[][] acc = new double[2][n];
		if (fairyCell >= 0) {
			// Fairy known: the path runs from its end straight to Blood, 4 steps after Fairy.
			int[] fairyWalk = g.walkDistances(fairyCell);
			int fromFairyToStart = fairyWalk[start] >= 0 ? fairyWalk[start] : 0;
			for (int b = 0; b < n; b++) {
				if (p[b] == null || distStart[b] < 0) continue;
				double pb = p[b][Label.BLOOD.ordinal()];
				if (pb < 0.02) continue;
				int steps = fromFairyToStart + distStart[b];
				double w = pb * Math.exp(-(steps - 4) * (steps - 4) / 2.0);
				if (w <= 0) continue;
				total += w;
				addRoute(g, acc, fromStart, start, b, w, true);
			}
		}
		List<Integer> fairies = new java.util.ArrayList<>();
		if (fairyCell < 0) for (int i = 0; i < n; i++) if (p[i] != null && p[i][Label.FAIRY.ordinal()] > 0.02) fairies.add(i);
		for (int f : fairies) {
			double pf = p[f][Label.FAIRY.ordinal()];
			if (distStart[f] < 0) continue;
			int[] fromFairy = bfsParents(g, f, pass);
			int[] distFairy = bfsDist(g, f, fromFairy);
			for (int b = 0; b < n; b++) {
				if (p[b] == null || b == f) continue;
				double pb = p[b][Label.BLOOD.ordinal()];
				if (pb < 0.02 || distFairy[b] < 0) continue;
				int m = g.manhattan(f, b);
				double mw = switch (m) {
					case 2, 4 -> 1.0;
					case 1, 3, 5 -> 0.3;
					default -> 0;
				};
				if (mw == 0) continue;
				double len = base + distStart[f] + 4;
				double w = pf * pb * mw * Math.exp(-(len - 10.5) * (len - 10.5) / 8);
				if (w <= 0) continue;
				total += w;
				// start -> f: every door but the last (the normal door into Fairy) is a wither door.
				addRoute(g, acc, fromStart, start, f, w, true);
				// f -> b: every door but the last (the blood door) is a wither door.
				addRoute(g, acc, fromFairy, f, b, w, true);
			}
		}
		if (total <= 0) return out;
		for (int k = 0; k < 2; k++) {
			for (int i = 0; i < n; i++) {
				int nb = g.neighbor(i, k == 0 ? 1 : 2);
				// Only doors the map doesn't already show.
				if (nb < 0 || g.link(i, k == 0 ? 1 : 2) != Link.NONE) continue;
				if (g.tile(i).explored() && g.tile(nb).explored()) continue;
				out[k][i] = Math.min(1, acc[k][i] / total);
			}
		}
		return out;
	}

	private static void addRoute(MapGrid g, double[][] acc, int[] parent, int from, int to, double w, boolean skipLast) {
		int cur = to;
		boolean last = true;
		while (cur != from && parent[cur] >= 0) {
			int prev = parent[cur];
			if (!(skipLast && last)) {
				if (g.neighbor(prev, 1) == cur) acc[0][prev] += w;
				else if (g.neighbor(prev, 2) == cur) acc[1][prev] += w;
				else if (g.neighbor(cur, 1) == prev) acc[0][cur] += w;
				else if (g.neighbor(cur, 2) == prev) acc[1][cur] += w;
			}
			last = false;
			cur = prev;
		}
	}

	private static int[] bfsParents(MapGrid g, int s, boolean[] pass) {
		int[] parent = new int[g.size()];
		java.util.Arrays.fill(parent, -2);
		parent[s] = -1;
		java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
		q.add(s);
		while (!q.isEmpty()) {
			int c = q.poll();
			for (int d = 0; d < 4; d++) {
				int nb = g.neighbor(c, d);
				if (nb < 0 || parent[nb] != -2 || !pass[nb]) continue;
				parent[nb] = c;
				q.add(nb);
			}
		}
		return parent;
	}

	private static int[] bfsDist(MapGrid g, int s, int[] parent) {
		int[] dist = new int[g.size()];
		for (int i = 0; i < dist.length; i++) {
			if (parent[i] == -2) {
				dist[i] = -1;
				continue;
			}
			int d = 0;
			for (int c = i; c != s && parent[c] >= 0; c = parent[c]) d++;
			dist[i] = d;
		}
		return dist;
	}

	static void addPathFeatures(Features f, MapGrid g, int i, Path path, int[] walk, int[] fairyWalk,
								int[] roomEntr, int[] roomFairy) {
		if (roomEntr[i] >= 0) f.oneHot(Features.ENTR_ROOM, 13, roomEntr[i]);
		if (roomFairy != null && roomFairy[i] >= 0) f.oneHot(Features.FAIRY_ROOM, 7, roomFairy[i]);
		if (i == path.frontier()) f.add(Features.PATH_FRONTIER, 1);
		if (path.fairyCandidate()[i]) f.add(Features.FAIRY_CANDIDATE, 1);
		if (fairyWalk != null && fairyWalk[i] >= 0) f.oneHot(Features.FAIRY_WALK, 7, fairyWalk[i]);
		int fr = path.frontier();
		if (fr >= 0 && walk[fr] >= 0) {
			int est = i == fr ? walk[fr] : walk[fr] + g.manhattan(fr, i);
			f.oneHot(Features.PATH_EST, 13, est);
		}
	}

	static void fill(Features f, MapGrid g, int i, int bracket, int entrance, int[] walk, int fairyCell,
					 int walled, int doors, double[][] prev, boolean[] cand) {
		int x = g.x(i), z = g.z(i);
		f.add(Features.BIAS, 1);
		f.add(Features.BRACKET + bracket, 1);
		if (g.tile(i) == Tile.UNKNOWN) f.add(Features.IS_UNKNOWN, 1);
		f.oneHot(Features.OBS_DOORS, 4, doors);
		boolean wither = false, bloodDoor = false;
		for (int d = 0; d < 4; d++) {
			Link l = g.link(i, d);
			if (l == Link.WITHER_DOOR) wither = true;
			if (l == Link.BLOOD_DOOR) bloodDoor = true;
		}
		if (wither) f.add(Features.WITHER_DOOR, 1);
		if (bloodDoor) f.add(Features.BLOOD_DOOR, 1);
		f.oneHot(Features.WALLED, 5, walled);
		int edges = (x == 0 ? 1 : 0) + (z == 0 ? 1 : 0) + (x == g.floor.standardWidth - 1 ? 1 : 0) + (z == g.h - 1 ? 1 : 0);
		if (edges >= 1) f.add(Features.EDGE, 1);
		if (edges >= 2) f.add(Features.CORNER, 1);
		if (entrance >= 0) f.oneHot(Features.ENTR_MAN, 13, g.manhattan(i, entrance));
		if (walk[i] >= 0) f.oneHot(Features.ENTR_WALK, 13, walk[i]);
		else f.add(Features.WALK_UNKNOWN, 1);
		if (fairyCell >= 0) {
			f.add(Features.FAIRY_KNOWN, 1);
			f.oneHot(Features.FAIRY_MAN, 7, g.manhattan(i, fairyCell));
		}
		if (prev != null) {
			double[] softFairy = new double[Features.SOFT_BUCKETS];
			double[] softBlood = new double[Features.SOFT_BUCKETS];
			double endAdj = 0;
			for (int j = 0; j < g.size(); j++) {
				if (j == i || !cand[j] || prev[j] == null) continue;
				int d = g.manhattan(i, j);
				int b = Math.min(Features.SOFT_BUCKETS, d) - 1;
				softFairy[b] += prev[j][Label.FAIRY.ordinal()];
				softBlood[b] += prev[j][Label.BLOOD.ordinal()];
				if (d == 1) {
					for (Label l : Label.ALL) if (l.isEndRoom()) endAdj += prev[j][l.ordinal()];
				}
			}
			for (int b = 0; b < Features.SOFT_BUCKETS; b++) {
				f.add(Features.SOFT_FAIRY + b, softFairy[b]);
				f.add(Features.SOFT_BLOOD + b, softBlood[b]);
			}
			f.add(Features.SOFT_END_ADJ, endAdj);
		}
		if (g.componentSize(i) > 1) f.add(Features.MERGED_VISIBLE, 1);
		if (g.floor.isExtraColumn(x)) f.add(Features.EXTRA_COLUMN, 1);
		if (x < Floor.MAX_DIM && z < Floor.MAX_DIM) f.add(Features.posIndex(bracket, x, z), 1);
	}

	/**
	 * Alternately scales each constrained label so its total over candidates equals the number
	 * still missing, then renormalises each cell. Unconstrained labels (Normal, Rare, Empty) absorb
	 * the remainder.
	 */
	static void balance(double[][] p, boolean[] cand, double[] target, boolean[] hard) {
		for (int it = 0; it < BALANCE_ITERATIONS; it++) {
			for (int k = 0; k < Label.COUNT; k++) {
				if (!hard[k]) continue;
				double s = 0;
				for (int i = 0; i < p.length; i++) if (cand[i]) s += p[i][k];
				if (s < 1e-12) continue;
				double factor = target[k] / s;
				for (int i = 0; i < p.length; i++) if (cand[i]) p[i][k] *= factor;
			}
			for (int i = 0; i < p.length; i++) {
				if (!cand[i]) continue;
				double s = 0;
				for (double v : p[i]) s += v;
				if (s < 1e-12) continue;
				for (int k = 0; k < Label.COUNT; k++) p[i][k] /= s;
			}
		}
	}

	/**
	 * If the map is finished except for one unopened room (a single "?" and 1-3 blank normal-grid
	 * cells reachable from it), the blank cells belong to that room, so it's never a 1x1 (nor any
	 * special room, which are all 1x1). Returns that "?" cell, or -1.
	 */
	/**
	 * "?" cells that are the only way into some blank cells: no other "?" reaches those cells
	 * through blank space, and there are no loops, so the room must lead onward (or extend into
	 * them). It therefore has more than one door, or more than one tile: never an end room.
	 */
	public static boolean[] mustLeadOnward(MapGrid g) {
		int n = g.size();
		boolean[] out = new boolean[n];
		List<Integer> unknowns = new java.util.ArrayList<>();
		for (int i = 0; i < n; i++) if (g.tile(i) == Tile.UNKNOWN && !g.floor.isExtraColumn(g.x(i))) unknowns.add(i);
		int[] reachedBy = new int[n];      // which "?" reached a blank cell first
		int[] reachCount = new int[n];     // how many "?" can reach it
		java.util.Arrays.fill(reachedBy, -1);
		for (int q : unknowns) {
			boolean[] seen = new boolean[n];
			java.util.ArrayDeque<Integer> dq = new java.util.ArrayDeque<>();
			seen[q] = true;
			dq.add(q);
			while (!dq.isEmpty()) {
				int c = dq.poll();
				for (int d = 0; d < 4; d++) {
					int nb = g.neighbor(c, d);
					if (nb < 0 || seen[nb] || g.tile(nb) != Tile.EMPTY || g.floor.isExtraColumn(g.x(nb))) continue;
					seen[nb] = true;
					reachCount[nb]++;
					reachedBy[nb] = q;
					dq.add(nb);
				}
			}
		}
		for (int i = 0; i < n; i++) if (reachCount[i] == 1) out[reachedBy[i]] = true;
		return out;
	}

	public static int soleUnknownWithBlanks(MapGrid g) {
		int unknown = -1, unknowns = 0, blanks = 0;
		for (int i = 0; i < g.size(); i++) {
			if (g.floor.isExtraColumn(g.x(i))) continue;
			if (g.tile(i) == Tile.UNKNOWN) {
				unknowns++;
				unknown = i;
			} else if (g.tile(i) == Tile.EMPTY) {
				blanks++;
			}
		}
		// Only when the map is otherwise finished: the blank cells are few enough to be part of one
		// room with the "?" (rooms are at most 4 tiles) and all reachable from it.
		if (unknowns != 1 || blanks == 0 || blanks > 3) return -1;
		boolean[] seen = new boolean[g.size()];
		java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
		seen[unknown] = true;
		q.add(unknown);
		int reached = 0;
		while (!q.isEmpty()) {
			int c = q.poll();
			for (int d = 0; d < 4; d++) {
				int nb = g.neighbor(c, d);
				if (nb < 0 || seen[nb] || g.tile(nb) != Tile.EMPTY || g.floor.isExtraColumn(g.x(nb))) continue;
				seen[nb] = true;
				reached++;
				q.add(nb);
			}
		}
		return reached == blanks ? unknown : -1;
	}

	static double[][] shapes(MapGrid g, Model model, boolean[] cand, double[][] p, int soleUnknown) {
		int n = g.size();
		int bracket = g.floor.bracket();
		double[] expected = model.expectedShapes(bracket);
		int[] seen = exploredShapeCounts(g);
		// Big rooms (anything bigger than 1x1) already opened.
		int bigRooms = 0, bigTiles = 0;
		for (Shape s : Shape.ALL) {
			if (s == Shape.S1x1) continue;
			bigRooms += seen[s.ordinal()];
			bigTiles += seen[s.ordinal()] * s.size();
		}
		int roomLimit = model.bigRoomLimit(bracket), tileLimit = model.bigTileLimit(bracket);
		double target = model.bigTileTarget(bracket);
		double expectedBigLeft = 0;
		for (Shape s : Shape.ALL) {
			if (s != Shape.S1x1) expectedBigLeft += Math.max(0, expected[s.ordinal()] - seen[s.ordinal()]) * s.size();
		}
		// As the usual big-room tile total is used up, big shapes become less likely.
		double budget = 1;
		if (target > 0 && expectedBigLeft > 0) budget = Math.max(0.1, Math.min(2, (target - bigTiles) / expectedBigLeft));
		double[] weight = new double[Shape.COUNT];
		for (Shape s : Shape.ALL) {
			int k = s.ordinal();
			if (s != Shape.S1x1) {
				if (seen[k] >= Shape.MAX_PER_DUNGEON) continue;
				if (bigRooms >= roomLimit || bigTiles + s.size() > tileLimit) continue;
			}
			weight[k] = Math.max(0.05, expected[k] - seen[k]) * (s == Shape.S1x1 ? 1 : budget);
		}
		double[][] out = new double[n][];
		for (int i = 0; i < n; i++) {
			if (!cand[i] || g.floor.isExtraColumn(g.x(i))) continue;
			double[] w = new double[Shape.COUNT];
			double sum = 0;
			for (Shape s : Shape.ALL) {
				int k = s.ordinal();
				if (weight[k] == 0) continue;
				if (i == soleUnknown && s == Shape.S1x1) continue;
				int fits = placements(g, i, s, cand);
				if (fits == 0) continue;
				// More ways to fit = a bit more likely, with diminishing returns.
				w[k] = weight[k] * (1 + Math.log(fits)) / s.size();
				sum += w[k];
			}
			if (sum <= 0 && i == soleUnknown) {
				// The count limits ruled everything out (the map contradicts them), but this room
				// can't be a 1x1: fall back to whichever big shapes physically fit.
				for (Shape sh : Shape.ALL) {
					if (sh == Shape.S1x1) continue;
					int fits = placements(g, i, sh, cand);
					if (fits > 0) {
						w[sh.ordinal()] = Math.max(0.05, expected[sh.ordinal()]) * (1 + Math.log(fits)) / sh.size();
						sum += w[sh.ordinal()];
					}
				}
			}
			if (sum <= 0) {
				w[Shape.S1x1.ordinal()] = 1;
				sum = 1;
			}
			for (int k = 0; k < Shape.COUNT; k++) w[k] /= sum;
			out[i] = w;
		}
		return out;
	}

	/** Shapes of fully explored normal rooms. */
	public static int[] exploredShapeCounts(MapGrid g) {
		int[] counts = new int[Shape.COUNT];
		boolean[] done = new boolean[g.size() + 1];
		for (int i = 0; i < g.size(); i++) {
			if (g.tile(i) != Tile.NORMAL) continue;
			int c = g.component(i);
			if (c < 0 || done[c]) continue;
			done[c] = true;
			Shape s = Shape.classify(g.componentCells(i));
			if (s != null) counts[s.ordinal()]++;
		}
		return counts;
	}

	/** Number of placements of {@code s} covering cell {@code i} that agree with the visible map. */
	static int placements(MapGrid g, int i, Shape s, boolean[] cand) {
		int count = 0;
		int ci = g.x(i), cz = g.z(i);
		List<int[]> own = g.componentCells(i);
		for (int[][] o : s.orientations()) {
			for (int[] anchor : o) {
				int ox = ci - anchor[0], oz = cz - anchor[1];
				if (fits(g, o, ox, oz, cand, own)) count++;
			}
		}
		return count;
	}

	private static boolean fits(MapGrid g, int[][] cells, int ox, int oz, boolean[] cand, List<int[]> own) {
		int[] idx = new int[cells.length];
		for (int k = 0; k < cells.length; k++) {
			int x = ox + cells[k][0], z = oz + cells[k][1];
			if (x < 0 || z < 0 || x >= g.floor.standardWidth || z >= g.h) return false;
			int j = g.idx(x, z);
			if (!cand[j]) return false;
			idx[k] = j;
		}
		// Must contain the whole visible room this cell belongs to.
		for (int[] c : own) {
			boolean in = false;
			for (int k = 0; k < cells.length; k++) {
				if (ox + cells[k][0] == c[0] && oz + cells[k][1] == c[1]) in = true;
			}
			if (!in) return false;
		}
		// Must not cut through another visible "?" room, nor include a door between its own tiles.
		for (int k = 0; k < cells.length; k++) {
			int j = idx[k];
			if (g.componentSize(j) > 1) {
				for (int[] c : g.componentCells(j)) {
					boolean in = false;
					for (int m = 0; m < cells.length; m++) {
						if (ox + cells[m][0] == c[0] && oz + cells[m][1] == c[1]) in = true;
					}
					if (!in) return false;
				}
			}
			for (int d = 0; d < 4; d++) {
				int nb = g.neighbor(j, d);
				if (nb < 0) continue;
				for (int m = 0; m < cells.length; m++) {
					if (idx[m] == nb && g.link(j, d).isDoor()) return false;
				}
			}
		}
		return true;
	}
}

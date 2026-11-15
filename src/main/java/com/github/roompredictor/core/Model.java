package com.github.roompredictor.core;

import java.util.Arrays;

/**
 * The learnable part: a multinomial logistic regression over {@link Label}s trained online with
 * AdaGrad, plus running averages of room-shape counts and puzzle counts per floor bracket.
 *
 * <p>Not thread-safe: only the predictor worker thread touches a live Model.
 */
public final class Model {
	/** Bump when the feature layout changes; older model files are then reseeded, not misread. */
	public static final int VERSION = 5;

	public double[][] weights = new double[Label.COUNT][Features.DIM];
	public double[][] gradSq = new double[Label.COUNT][Features.DIM];
	public double learningRate = 0.15;
	public double l2 = 1e-4;
	public long updates;
	/** Sum of per-run shape counts, per bracket. */
	public double[][] shapeTotals = new double[Floor.BRACKETS][Shape.COUNT];
	/** Sum of run weights behind {@link #shapeTotals} (a fully opened map counts 1). */
	public double[] shapeRuns = new double[Floor.BRACKETS];
	public double[] puzzleTotals = new double[Floor.BRACKETS];
	public int[] puzzleRuns = new int[Floor.BRACKETS];
	/** Big rooms (bigger than 1x1) per fully opened map, per bracket: summed counts and tiles, runs, maxima. */
	public double[] bigRoomTotals = new double[Floor.BRACKETS];
	public double[] bigTileTotals = new double[Floor.BRACKETS];
	public int[] bigRuns = new int[Floor.BRACKETS];
	public int[] bigRoomMax = new int[Floor.BRACKETS];
	public int[] bigTileMax = new int[Floor.BRACKETS];
	/** Walking steps Fairy->Blood seen per bracket (counts 0..12+), and Entrance->Blood (sum, sum of squares, n). */
	public double[][] fairyBloodSteps = new double[Floor.BRACKETS][13];
	public double[] entranceBloodSum = new double[Floor.BRACKETS];
	public double[] entranceBloodSq = new double[Floor.BRACKETS];
	public int[] entranceBloodN = new int[Floor.BRACKETS];
	public int version = VERSION;

	public void recordDistances(int bracket, int fairyToBlood, int entranceToBlood) {
		if (fairyToBlood >= 0) fairyBloodSteps[bracket][Math.min(12, fairyToBlood)]++;
		if (entranceToBlood >= 0) {
			entranceBloodSum[bracket] += entranceToBlood;
			entranceBloodSq[bracket] += (double) entranceToBlood * entranceToBlood;
			entranceBloodN[bracket]++;
		}
	}

	/**
	 * How plausible it is that Blood is {@code steps} walking steps past Fairy, 0..1. Learned per
	 * floor size once there are 3 examples; before that F7/M7 uses the known rule (4 steps) and
	 * smaller floors accept 2-8 steps.
	 */
	public double fairyBloodWeight(int bracket, int steps) {
		double[] h = fairyBloodSteps[bracket];
		double n = 0, max = 0;
		for (double v : h) {
			n += v;
			max = Math.max(max, v);
		}
		if (n >= 3) return (h[Math.min(12, Math.max(0, steps))] + 0.25) / (max + 0.25);
		if (bracket == 5) return steps == 4 ? 1.0 : 0.1;
		return steps >= 2 && steps <= 8 ? 1.0 : 0.15;
	}

	/**
	 * How plausible a total Entrance->Blood route of {@code steps} is, 0..1 (a bell curve). Learned
	 * per floor size once there are 3 examples; before that F7/M7 centres on 9.5 steps and smaller
	 * floors scale that by grid size.
	 */
	public double entranceBloodWeight(int bracket, int cells, double steps) {
		double mean, sd;
		if (entranceBloodN[bracket] >= 3) {
			mean = entranceBloodSum[bracket] / entranceBloodN[bracket];
			double var = entranceBloodSq[bracket] / entranceBloodN[bracket] - mean * mean;
			sd = Math.max(1.0, Math.sqrt(Math.max(0, var)));
		} else if (bracket == 5) {
			mean = 9.5;
			sd = 1.5;
		} else {
			mean = 9.5 * Math.sqrt(cells / 36.0);
			sd = 3;
		}
		return Math.exp(-(steps - mean) * (steps - mean) / (2 * sd * sd));
	}

	/** Known F7/M7 rule: at most 7 big rooms covering at most 24 tiles, usually 22. */
	public static final int F7_BIG_ROOMS = 7, F7_BIG_TILES_MAX = 24, F7_BIG_TILES_USUAL = 22;
	private static final int MIN_RUNS_FOR_LIMITS = 3;

	public void recordBigRooms(int bracket, int rooms, int tiles) {
		bigRoomTotals[bracket] += rooms;
		bigTileTotals[bracket] += tiles;
		bigRuns[bracket]++;
		bigRoomMax[bracket] = Math.max(bigRoomMax[bracket], rooms);
		bigTileMax[bracket] = Math.max(bigTileMax[bracket], tiles);
	}

	/** Most big rooms a map of this bracket can have (Integer.MAX_VALUE if not known yet). */
	public int bigRoomLimit(int bracket) {
		if (bracket == 5) return F7_BIG_ROOMS;
		return bigRuns[bracket] >= MIN_RUNS_FOR_LIMITS ? bigRoomMax[bracket] : Integer.MAX_VALUE;
	}

	public int bigTileLimit(int bracket) {
		if (bracket == 5) return F7_BIG_TILES_MAX;
		return bigRuns[bracket] >= MIN_RUNS_FOR_LIMITS ? bigTileMax[bracket] : Integer.MAX_VALUE;
	}

	/** Typical big-room tiles per map, or -1 if not known yet. F7/M7 blends the rule (worth 5 maps) with data. */
	public double bigTileTarget(int bracket) {
		if (bracket == 5) return (F7_BIG_TILES_USUAL * 5 + bigTileTotals[bracket]) / (5.0 + bigRuns[bracket]);
		return bigRuns[bracket] >= MIN_RUNS_FOR_LIMITS ? bigTileTotals[bracket] / bigRuns[bracket] : -1;
	}

	/** A fresh model seeded with the known generation rules. */
	public static Model seeded() {
		Model m = new Model();
		Priors.apply(m);
		return m;
	}

	public double[] logits(Features f) {
		double[] z = new double[Label.COUNT];
		for (int k = 0; k < Label.COUNT; k++) z[k] = f.dot(weights[k]);
		return z;
	}

	/** Softmax restricted to {@code mask}; disallowed labels get exactly 0. */
	public static double[] softmax(double[] z, boolean[] mask) {
		double max = Double.NEGATIVE_INFINITY;
		for (int k = 0; k < z.length; k++) if (mask[k]) max = Math.max(max, z[k]);
		double[] p = new double[z.length];
		if (max == Double.NEGATIVE_INFINITY) return p;
		double sum = 0;
		for (int k = 0; k < z.length; k++) {
			if (!mask[k]) continue;
			p[k] = Math.exp(z[k] - max);
			sum += p[k];
		}
		for (int k = 0; k < z.length; k++) p[k] /= sum;
		return p;
	}

	/** One online step of cross-entropy descent toward {@code label}. Returns the loss before it. */
	public double update(Features f, boolean[] mask, Label label) {
		return update(f, mask, label, 1.0);
	}

	/**
	 * Like {@link #update(Features, boolean[], Label)} with the step scaled by {@code weight}
	 * (e.g. the fraction of the map that was opened in the run the example came from).
	 */
	public double update(Features f, boolean[] mask, Label label, double weight) {
		boolean[] m = mask.clone();
		m[label.ordinal()] = true;
		double[] p = softmax(logits(f), m);
		double loss = -Math.log(Math.max(1e-12, p[label.ordinal()]));
		for (int k = 0; k < Label.COUNT; k++) {
			if (!m[k]) continue;
			double err = (p[k] - (k == label.ordinal() ? 1 : 0)) * weight;
			double[] w = weights[k];
			double[] g2 = gradSq[k];
			for (int j = 0; j < f.size; j++) {
				int i = f.index[j];
				double g = err * f.value[j] + l2 * w[i];
				g2[i] += g * g;
				w[i] -= learningRate * g / Math.sqrt(g2[i] + 1e-8);
			}
		}
		updates++;
		return loss;
	}

	/** Expected number of rooms of each shape in a dungeon of this bracket. */
	public double[] expectedShapes(int bracket) {
		double[] out = new double[Shape.COUNT];
		double[] prior = Priors.shapePrior(bracket);
		double n = shapeRuns[bracket];
		// Blend the rule prior (worth 3 runs) with what we've actually seen.
		for (int s = 0; s < Shape.COUNT; s++) out[s] = (prior[s] * 3 + shapeTotals[bracket][s]) / (3 + n);
		return out;
	}

	public void recordShapes(int bracket, int[] counts, double weight) {
		if (weight <= 0) return;
		for (int s = 0; s < Shape.COUNT; s++) shapeTotals[bracket][s] += counts[s] * weight;
		shapeRuns[bracket] += weight;
	}

	/** Independent deep copy (live per-run learning works on a copy of the saved model). */
	public Model copy() {
		Model m = new Model();
		m.weights = deep(weights);
		m.gradSq = deep(gradSq);
		m.learningRate = learningRate;
		m.l2 = l2;
		m.updates = updates;
		m.shapeTotals = deep(shapeTotals);
		m.shapeRuns = shapeRuns.clone();
		m.puzzleTotals = puzzleTotals.clone();
		m.puzzleRuns = puzzleRuns.clone();
		m.version = version;
		m.bigRoomTotals = bigRoomTotals.clone();
		m.bigTileTotals = bigTileTotals.clone();
		m.bigRuns = bigRuns.clone();
		m.bigRoomMax = bigRoomMax.clone();
		m.bigTileMax = bigTileMax.clone();
		m.fairyBloodSteps = deep(fairyBloodSteps);
		m.entranceBloodSum = entranceBloodSum.clone();
		m.entranceBloodSq = entranceBloodSq.clone();
		m.entranceBloodN = entranceBloodN.clone();
		return m;
	}

	private static double[][] deep(double[][] a) {
		double[][] out = new double[a.length][];
		for (int i = 0; i < a.length; i++) out[i] = a[i].clone();
		return out;
	}

	public double expectedPuzzles(Floor floor) {
		int b = floor.bracket();
		return (floor.defaultPuzzles() * 2 + puzzleTotals[b]) / (2.0 + puzzleRuns[b]);
	}

	public void recordPuzzles(Floor floor, int count) {
		puzzleTotals[floor.bracket()] += count;
		puzzleRuns[floor.bracket()]++;
	}

	/** Shapes and sizes may have changed between versions; pad or truncate rather than fail. */
	public void sanitize() {
		weights = fit(weights, Label.COUNT, Features.DIM);
		gradSq = fit(gradSq, Label.COUNT, Features.DIM);
		shapeTotals = fit(shapeTotals, Floor.BRACKETS, Shape.COUNT);
		if (shapeRuns == null || shapeRuns.length != Floor.BRACKETS) shapeRuns = Arrays.copyOf(shapeRuns == null ? new double[0] : shapeRuns, Floor.BRACKETS);
		if (puzzleTotals == null || puzzleTotals.length != Floor.BRACKETS) puzzleTotals = Arrays.copyOf(puzzleTotals == null ? new double[0] : puzzleTotals, Floor.BRACKETS);
		if (puzzleRuns == null || puzzleRuns.length != Floor.BRACKETS) puzzleRuns = Arrays.copyOf(puzzleRuns == null ? new int[0] : puzzleRuns, Floor.BRACKETS);
		if (bigRoomTotals == null || bigRoomTotals.length != Floor.BRACKETS) bigRoomTotals = new double[Floor.BRACKETS];
		if (bigTileTotals == null || bigTileTotals.length != Floor.BRACKETS) bigTileTotals = new double[Floor.BRACKETS];
		if (bigRuns == null || bigRuns.length != Floor.BRACKETS) bigRuns = new int[Floor.BRACKETS];
		if (bigRoomMax == null || bigRoomMax.length != Floor.BRACKETS) bigRoomMax = new int[Floor.BRACKETS];
		if (bigTileMax == null || bigTileMax.length != Floor.BRACKETS) bigTileMax = new int[Floor.BRACKETS];
		fairyBloodSteps = fit(fairyBloodSteps, Floor.BRACKETS, 13);
		if (entranceBloodSum == null || entranceBloodSum.length != Floor.BRACKETS) entranceBloodSum = new double[Floor.BRACKETS];
		if (entranceBloodSq == null || entranceBloodSq.length != Floor.BRACKETS) entranceBloodSq = new double[Floor.BRACKETS];
		if (entranceBloodN == null || entranceBloodN.length != Floor.BRACKETS) entranceBloodN = new int[Floor.BRACKETS];
		if (!(learningRate > 0) || learningRate > 5) learningRate = 0.15;
		if (!(l2 >= 0)) l2 = 1e-4;
	}

	private static double[][] fit(double[][] a, int rows, int cols) {
		double[][] out = new double[rows][cols];
		if (a == null) return out;
		for (int r = 0; r < Math.min(rows, a.length); r++) {
			if (a[r] == null) continue;
			System.arraycopy(a[r], 0, out[r], 0, Math.min(cols, a[r].length));
		}
		return out;
	}
}

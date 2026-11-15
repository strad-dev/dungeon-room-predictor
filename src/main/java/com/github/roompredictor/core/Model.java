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
	public static final int VERSION = 3;

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
	public int version = VERSION;

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

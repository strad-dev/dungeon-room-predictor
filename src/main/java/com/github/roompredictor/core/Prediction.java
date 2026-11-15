package com.github.roompredictor.core;

/** Immutable result of one inference pass, published to the render thread. */
public final class Prediction {
	public final MapGrid grid;
	/** Per cell: label probabilities, or null if the cell is not a candidate (already explored). */
	public final double[][] labels;
	/** Per cell: shape probabilities given the room is a normal room, or null. */
	public final double[][] shapes;
	/** Per "?" cell guessed to be a normal room: the cells of its most likely footprint, or null. */
	public final int[][] footprints;
	/**
	 * The most likely completion of the map: every unopened cell assigned to a predicted room.
	 * {@code roomOf[cell]} is an index into the room arrays, or -1 (opened, or predicted empty).
	 */
	public final int[] roomOf;
	public final Label[] roomLabel;
	public final Shape[] roomShape;
	public final double[] roomTypeP;
	public final double[] roomShapeP;
	/** Predicted doors joining the predicted rooms into a tree: {cell, dir} with dir 1 = east, 2 = south. */
	public final java.util.List<int[]> roomDoors;
	/** Predicted wither doors: {east, south} probability per cell for doors the map doesn't show yet. */
	public final double[][] witherDoors;
	/** Features used per candidate cell in the final round (kept for learning). Do not mutate. */
	public final Features[] features;
	public final boolean[][] masks;
	public final int bloodCell;
	public final double bloodProbability;
	public final int puzzleTarget;
	public final boolean puzzlesFromTab;
	public final long modelUpdates;
	public final long computeNanos;

	Prediction(MapGrid grid, double[][] labels, double[][] shapes, int[][] footprints, Predictor.Completion completion, double[][] witherDoors, Features[] features, boolean[][] masks,
			   int bloodCell, double bloodProbability, int puzzleTarget, boolean puzzlesFromTab,
			   long modelUpdates, long computeNanos) {
		this.grid = grid;
		this.labels = labels;
		this.shapes = shapes;
		this.footprints = footprints;
		this.witherDoors = witherDoors;
		this.roomOf = completion.roomOf();
		this.roomLabel = completion.label();
		this.roomShape = completion.shape();
		this.roomTypeP = completion.typeP();
		this.roomShapeP = completion.shapeP();
		this.roomDoors = completion.doors();
		this.features = features;
		this.masks = masks;
		this.bloodCell = bloodCell;
		this.bloodProbability = bloodProbability;
		this.puzzleTarget = puzzleTarget;
		this.puzzlesFromTab = puzzlesFromTab;
		this.modelUpdates = modelUpdates;
		this.computeNanos = computeNanos;
	}

	public Label best(int cell) {
		double[] p = labels[cell];
		if (p == null) return null;
		int b = 0;
		for (int k = 1; k < p.length; k++) if (p[k] > p[b]) b = k;
		return Label.ALL[b];
	}

	public Shape bestShape(int cell) {
		double[] p = shapes[cell];
		if (p == null) return null;
		int b = 0;
		for (int k = 1; k < p.length; k++) if (p[k] > p[b]) b = k;
		return Shape.ALL[b];
	}
}

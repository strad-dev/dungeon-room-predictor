package com.github.roompredictor.core;

import static com.github.roompredictor.core.Features.*;

/**
 * Starting weights that encode the known generation rules, so the model is useful before it has
 * learned anything. Online learning then moves these toward what is actually observed.
 *
 * <ul>
 *   <li>End rooms (Blood, Yellow, Trap, Puzzles, Rare) are 1x1 with exactly one door.</li>
 *   <li>Distances below are in steps (tiles moved). "N tiles between" in the community rules
 *       excludes both ends, so it is N+1 steps.</li>
 *   <li>Blood has 3 tiles between it and the Fairy room: 4 steps. On a grid, step count and
 *       manhattan distance have the same parity and manhattan &lt;= steps, so Blood is at
 *       manhattan 2 or 4 from Fairy.</li>
 *   <li>Blood usually has 9-10 tiles between it and the Entrance (10-11 steps); 6 between
 *       (7 steps) has been seen on M7.</li>
 *   <li>Fairy sits on the Entrance-to-Blood path, 4 steps before Blood: usually 6-7 steps from
 *       the Entrance, as few as 3.</li>
 * </ul>
 */
final class Priors {
	private Priors() {
	}

	static void apply(Model m) {
		double[] normal = m.weights[Label.NORMAL.ordinal()];
		double[] puzzle = m.weights[Label.PUZZLE.ordinal()];
		double[] trap = m.weights[Label.TRAP.ordinal()];
		double[] yellow = m.weights[Label.YELLOW.ordinal()];
		double[] blood = m.weights[Label.BLOOD.ordinal()];
		double[] fairy = m.weights[Label.FAIRY.ordinal()];
		double[] rare = m.weights[Label.RARE.ordinal()];
		double[] empty = m.weights[Label.EMPTY.ordinal()];

		// F7/M7 distance rules, copied into each floor size's block at the end.
		double[] bloodD = new double[DIST_BLOCK];
		double[] fairyD = new double[DIST_BLOCK];

		normal[BIAS] = 2.0;
		normal[WALLED + 3] = -0.8;
		normal[OBS_DOORS + 2] = 0.5;
		normal[OBS_DOORS + 3] = 0.8;

		for (double[] end : new double[][]{puzzle, trap, yellow, blood, rare}) {
			end[WALLED + 2] = 0.3;
			end[WALLED + 3] = 1.5;
			end[SOFT_END_ADJ] = -0.3;
		}
		puzzle[BIAS] = 0.2;
		trap[BIAS] = -0.4;
		yellow[BIAS] = -0.4;
		rare[BIAS] = -2.0;

		blood[BIAS] = -0.8;
		blood[BLOOD_DOOR] = 8.0;
		double[] bloodEntrMan = {-4, -3, -2.5, -1.5, -0.8, -0.2, 0.3, 0.6, 0.8, 0.8, 0.7, 0.5, 0.3};
		for (int i = 0; i < bloodEntrMan.length; i++) bloodD[ENTR_MAN + i] = bloodEntrMan[i];
		double[] bloodEntrWalk = {-4, -4, -4, -3.5, -3, -2.5, -2, -0.3, 0.3, 0.8, 1.5, 1.5, 0.8};
		for (int i = 0; i < bloodEntrWalk.length; i++) bloodD[ENTR_WALK + i] = bloodEntrWalk[i];
		// Softened: if Hypixel counts whole rooms (see ENTR_ROOM), going through a 2x2 or L can
		// break the 2-or-4 parity of the shortest walk.
		double[] bloodFairyMan = {-6, -2, 1.0, -1.5, 1.5, -2, -3};
		for (int i = 0; i < bloodFairyMan.length; i++) bloodD[FAIRY_MAN + i] = bloodFairyMan[i];
		double[] bloodSoftFairy = {-1.5, 1.0, -1.0, 1.5, -0.5};
		for (int i = 0; i < bloodSoftFairy.length; i++) bloodD[SOFT_FAIRY + i] = bloodSoftFairy[i];

		// Path rules: the next unopened path room (behind a wither door) has two doors and isn't
		// Fairy, whose door in is a normal door. Before Fairy is found, the path continues into it
		// through a normal door of the path's last room.
		normal[PATH_FRONTIER] = 1.0;
		for (double[] end : new double[][]{puzzle, trap, yellow, rare}) end[PATH_FRONTIER] = -3.0;
		double[] bloodFairyWalk = {-4, -3, -2, -1, 1.5, -0.5, -1};
		for (int i = 0; i < bloodFairyWalk.length; i++) bloodD[FAIRY_WALK + i] = bloodFairyWalk[i];
		double[] bloodPathEst = {-4, -4, -4, -4, -4, -3, -2, -0.5, 0.3, 0.8, 1.5, 1.5, 0.8};
		for (int i = 0; i < bloodPathEst.length; i++) bloodD[PATH_EST + i] = bloodPathEst[i];

		// Whole-room "tiles between" (each room passed counts its full size): Entrance->Fairy 4-5,
		// Fairy->Blood 3, Entrance->Blood 9-10.
		double[] fairyEntrRoom = {-4, -3, -2, -0.5, 1.5, 1.5, 0, -1, -2, -3, -3, -3, -3};
		for (int i = 0; i < fairyEntrRoom.length; i++) fairyD[ENTR_ROOM + i] = fairyEntrRoom[i];
		double[] bloodEntrRoom = {-4, -4, -4, -4, -3.5, -3, -2, -1, 0.5, 1.5, 1.5, 0.3, -0.5};
		for (int i = 0; i < bloodEntrRoom.length; i++) bloodD[ENTR_ROOM + i] = bloodEntrRoom[i];
		double[] bloodFairyRoom = {-5, -4, -2, 3.0, -1.5, -2.5, -3};
		for (int i = 0; i < bloodFairyRoom.length; i++) bloodD[FAIRY_ROOM + i] = bloodFairyRoom[i];

		fairy[BIAS] = -0.6;
		fairy[WITHER_DOOR] = -2.0;
		fairy[PATH_FRONTIER] = -2.0;
		fairy[FAIRY_CANDIDATE] = 3.5;
		double[] fairyPathEst = {-4, -3, -2, -0.3, 0.2, 0.6, 1.0, 1.0, 0.5, -0.2, -0.8, -1.5, -2};
		for (int i = 0; i < fairyPathEst.length; i++) fairyD[PATH_EST + i] = fairyPathEst[i];
		fairy[WALLED + 3] = -1.0;
		double[] fairyEntrMan = {-4, -1.5, -0.8, -0.2, 0.4, 0.7, 0.7, 0.5, 0.1, -0.4, -0.9, -1.4, -2};
		for (int i = 0; i < fairyEntrMan.length; i++) fairyD[ENTR_MAN + i] = fairyEntrMan[i];
		double[] fairyEntrWalk = {-4, -2.5, -1.5, -0.3, 0.2, 0.6, 1.0, 1.0, 0.5, -0.2, -0.8, -1.5, -2};
		for (int i = 0; i < fairyEntrWalk.length; i++) fairyD[ENTR_WALK + i] = fairyEntrWalk[i];
		double[] fairySoftBlood = {-1.5, 1.0, -1.0, 1.5, -0.5};
		for (int i = 0; i < fairySoftBlood.length; i++) fairyD[SOFT_BLOOD + i] = fairySoftBlood[i];

		// Shortest-walk distances count a 2x2 shortcut as 2 tiles where Hypixel seems to count 4, so
		// trust them half as much as the whole-room ones; learning sorts out which is right.
		for (int i = 0; i < 13; i++) {
			bloodD[ENTR_WALK + i] *= 0.5;
			fairyD[ENTR_WALK + i] *= 0.5;
			bloodD[PATH_EST + i] *= 0.5;
			fairyD[PATH_EST + i] *= 0.5;
		}
		// The distance rules above are for F7/M7. Smaller dungeons have their own distances, which
		// they learn from their own runs; they start from a weak (0.3x) copy that keeps the
		// near-universal parts (e.g. Blood is never next to the Entrance).
		for (int b = 0; b < Floor.BRACKETS; b++) {
			double scale = b == 5 ? 1.0 : 0.3;
			for (int k = 0; k < DIST_BLOCK; k++) {
				blood[d(b, k)] = bloodD[k] * scale;
				fairy[d(b, k)] = fairyD[k] * scale;
			}
		}

		// Extra puzzle column: mostly empty, sometimes a puzzle.
		empty[BIAS] = 0;
		empty[EXTRA_COLUMN] = 1.5;
		puzzle[EXTRA_COLUMN] = -0.5;
	}

	/** Expected rooms per shape in one dungeon, before any runs are observed. */
	static double[] shapePrior(int bracket) {
		// 1x1, 1x2, 1x3, 1x4, L, 2x2 (normal rooms only; specials are counted separately)
		return switch (bracket) {
			case 0 -> new double[]{2.5, 1.2, 0.6, 0.3, 0.5, 0.3};
			case 1 -> new double[]{3.5, 1.4, 0.8, 0.4, 0.7, 0.4};
			case 2 -> new double[]{5.0, 1.6, 1.0, 0.6, 1.0, 0.5};
			case 3 -> new double[]{5.0, 1.6, 1.0, 0.6, 1.0, 0.5};
			case 4 -> new double[]{7.0, 1.7, 1.2, 0.8, 1.2, 0.7};
			default -> new double[]{8.0, 1.8, 1.4, 1.0, 1.4, 0.9};
		};
	}
}

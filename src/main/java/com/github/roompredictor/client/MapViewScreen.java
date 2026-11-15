package com.github.roompredictor.client;

import com.github.roompredictor.core.MapGrid;
import com.github.roompredictor.core.Prediction;
import com.github.roompredictor.core.Replay;
import com.github.roompredictor.engine.PredictorService;
import com.github.roompredictor.engine.RunRecord;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * One stored map. The step buttons replay how the map opened up, and the predictor can be run on
 * any step (on the worker thread) to see what it would have guessed at that moment.
 */
public final class MapViewScreen extends Screen {
	private final PredictorService service;
	private final RunRecord run;
	private final Screen parent;
	private final MapGrid finalGrid;
	private final int[] steps;
	/** Index into {@link #steps}; steps.length means the final map. */
	private int step;
	private boolean predict = true;
	private MapGrid shown;
	private volatile Prediction prediction;
	private int request;

	public MapViewScreen(PredictorService service, RunRecord run, Screen parent) {
		super(Component.literal("Map " + MapListScreen.describe(run)));
		this.service = service;
		this.run = run;
		this.parent = parent;
		this.finalGrid = run.grid();
		this.steps = Replay.steps(run.reveal);
		this.step = steps.length;
		refresh();
	}

	private void refresh() {
		shown = step >= steps.length ? finalGrid : Replay.at(finalGrid, run.reveal, steps[step]);
		prediction = null;
		if (!predict) return;
		int id = ++request;
		MapGrid g = shown;
		service.predictAsync(g, run.puzzles).thenAccept(p -> {
			if (id == request) prediction = p;
		});
	}

	@Override
	protected void init() {
		int by = height - 28;
		int bw = 60;
		int x = width / 2 - (bw * 5 + 16) / 2;
		addRenderableWidget(Button.builder(Component.literal("|<"), b -> go(0)).bounds(x, by, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("<"), b -> go(step - 1)).bounds(x + bw + 4, by, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal(">"), b -> go(step + 1)).bounds(x + 2 * (bw + 4), by, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal(">|"), b -> go(steps.length)).bounds(x + 3 * (bw + 4), by, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(x + 4 * (bw + 4), by, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal(predict ? "Predictions: on" : "Predictions: off"), b -> {
			predict = !predict;
			refresh();
			rebuildWidgets();
		}).bounds(width / 2 - 60, by - 24, 120, 20).build());
	}

	private void go(int s) {
		step = Math.max(0, Math.min(steps.length, s));
		refresh();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
		super.extractRenderState(ctx, mouseX, mouseY, delta);
		ctx.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
		String when = step >= steps.length ? "Final map" : "At " + steps[step] / 60 + "m" + String.format("%02d", steps[step] % 60) + "s (step " + (step + 1) + "/" + steps.length + ")";
		ctx.centeredText(font, when, width / 2, 20, 0xFFAAAAAA);
		if (shown == null) return;
		float size = Math.min(width - 40, height - 90);
		Prediction p = predict ? prediction : null;
		MapPainter.draw(ctx, font, shown, p, (width - size) / 2, 34, size,
			new MapPainter.Options(true, true, 0.3f, com.github.roompredictor.RoomPredictorClient.config().style,
				run.names, com.github.roompredictor.RoomPredictorClient.config().hideCommonNames,
				com.github.roompredictor.RoomPredictorClient.config().colorRoomNames,
				com.github.roompredictor.RoomPredictorClient.config().witherMinConfidence));
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}

package com.github.roompredictor.client;

import com.github.roompredictor.engine.PredictorService;
import com.github.roompredictor.engine.RunRecord;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Every stored map, newest first, a page at a time. Clicking one opens {@link MapViewScreen}. */
public final class MapListScreen extends Screen {
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
	private static final int ROW = 22;

	private final PredictorService service;
	private final Screen parent;
	private final List<RunRecord> runs;
	private int page;

	public MapListScreen(PredictorService service, Screen parent) {
		this(service, parent, 0);
	}

	MapListScreen(PredictorService service, Screen parent, int page) {
		super(Component.literal("Stored dungeon maps"));
		this.service = service;
		this.parent = parent;
		this.runs = new ArrayList<>(service.runs()).reversed();
		this.page = page;
	}

	private int perPage() {
		return Math.max(1, (height - 80) / ROW);
	}

	private int pages() {
		return Math.max(1, (runs.size() + perPage() - 1) / perPage());
	}

	@Override
	protected void init() {
		page = Math.max(0, Math.min(page, pages() - 1));
		int w = Math.min(340, width - 20);
		int x = (width - w) / 2;
		int y = 36;
		int from = page * perPage();
		for (int i = from; i < Math.min(runs.size(), from + perPage()); i++) {
			RunRecord r = runs.get(i);
			addRenderableWidget(Button.builder(Component.literal(describe(r)),
				b -> minecraft.gui.setScreen(new MapViewScreen(service, r, this))).bounds(x, y, w, ROW - 2).build());
			y += ROW;
		}
		int by = height - 28;
		addRenderableWidget(Button.builder(Component.literal("< Newer"), b -> {
			page--;
			rebuildWidgets();
		}).bounds(width / 2 - 155, by, 100, 20).build()).active = page > 0;
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
			.bounds(width / 2 - 50, by, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Older >"), b -> {
			page++;
			rebuildWidgets();
		}).bounds(width / 2 + 55, by, 100, 20).build()).active = page < pages() - 1;
	}

	static String describe(RunRecord r) {
		StringBuilder sb = new StringBuilder();
		sb.append(WHEN.format(Instant.ofEpochMilli(r.time))).append("  ").append(r.floor);
		if (r.puzzles > 0) sb.append("  ").append(r.puzzles).append(" puzzles");
		if (r.seconds > 0) sb.append("  ").append(r.seconds / 60).append('m').append(String.format("%02d", r.seconds % 60)).append('s');
		if (r.bloodGuess >= 0 && r.bloodActual >= 0) sb.append(r.bloodGuess == r.bloodActual ? "  blood guess: right" : "  blood guess: wrong");
		return sb.toString();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
		super.extractRenderState(ctx, mouseX, mouseY, delta);
		ctx.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
		String sub = runs.isEmpty()
			? "No maps yet - they are saved when a run ends."
			: runs.size() + " maps  -  page " + (page + 1) + "/" + pages();
		ctx.centeredText(font, sub, width / 2, 24, 0xFFAAAAAA);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}

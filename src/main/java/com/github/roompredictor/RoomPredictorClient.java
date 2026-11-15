package com.github.roompredictor;

import com.github.roompredictor.client.Config;
import com.github.roompredictor.client.DungeonWatcher;
import com.github.roompredictor.client.DvImport;
import com.github.roompredictor.client.RoomNamer;
import com.github.roompredictor.core.RoomDatabase;
import com.github.roompredictor.client.MapListScreen;
import com.github.roompredictor.client.MapPainter;
import com.github.roompredictor.core.Label;
import com.github.roompredictor.core.Prediction;
import com.github.roompredictor.engine.PredictorService;
import com.github.roompredictor.engine.Storage;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import com.github.roompredictor.core.MapDecoder;
import com.github.roompredictor.core.MapGrid;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Strad Rooms Predictor.
 *
 * <p>Purely client-side: it reads the dungeon map item, sidebar, tab list and chat that the client
 * already received, and draws an overlay. It registers no packet handlers, mixins or networking.
 */
public final class RoomPredictorClient implements ClientModInitializer {
	private static final String PREFIX = "§6[SRP]§r ";

	private static Config config;
	private static PredictorService service;
	private static DungeonWatcher watcher;
	private static Screen screenToOpen;

	@Override
	public void onInitializeClient() {
		Path dir = FabricLoader.getInstance().getConfigDir().resolve("roompredictor");
		config = Config.load(dir.resolve("config.json"));
		service = new PredictorService(new Storage(dir), msg -> tell(msg));
		watcher = new DungeonWatcher(service, new RoomNamer(RoomDatabase.load()));
		// Quiet import of Devonian's map style on a fresh install only; never again automatically,
		// so later changes (e.g. /srp text) are kept. /srp importdv re-imports on request.
		if (config.dvImportVersion == 0) importDv(false, true);

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (screenToOpen != null) {
				mc.gui.setScreen(screenToOpen);
				screenToOpen = null;
			}
			if (config.enabled) watcher.tick(mc);
		});
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay && config.enabled) watcher.onChat(message);
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> service.shutdown());
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("roompredictor", "map"), (ctx, delta) -> drawHud(ctx));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
			literal("srp")
				.executes(c -> help(c))
				.then(literal("toggle").executes(c -> {
					config.enabled = !config.enabled;
					config.save();
					return ok(c, "Predictor " + (config.enabled ? "enabled" : "disabled"));
				}))
				.then(literal("debug")
					.executes(c -> {
						for (String l : watcher.debug()) c.getSource().sendFeedback(Component.literal("§7" + l));
						c.getSource().sendFeedback(Component.literal("§7worker: " + service.status()));
						if (service.lastError() != null) c.getSource().sendFeedback(Component.literal("§cworker error: " + service.lastError()));
						return 1;
					})
					.then(literal("map").executes(c -> {
						service.dumpMap(RoomPredictorClient::tell);
						return 1;
					}))
					.then(literal("overlay").executes(c -> {
						config.debug = !config.debug;
						config.save();
						return ok(c, "Debug status line " + (config.debug ? "on" : "off"));
					})))
				.then(literal("importdv")
					.executes(c -> {
						importDv(false, false);
						return 1;
					})
					.then(literal("position").executes(c -> {
						importDv(true, false);
						return 1;
					})))
				.then(literal("maps").executes(c -> {
					screenToOpen = new MapListScreen(service, null);
					return 1;
				}))
				.then(literal("pos")
					.then(argument("x", IntegerArgumentType.integer(0))
						.then(argument("y", IntegerArgumentType.integer(0)).executes(c -> {
							config.hudX = IntegerArgumentType.getInteger(c, "x");
							config.hudY = IntegerArgumentType.getInteger(c, "y");
							config.save();
							return ok(c, "Map moved to " + config.hudX + ", " + config.hudY);
						}))))
				.then(literal("size")
					.then(argument("pixels", FloatArgumentType.floatArg(40, 400)).executes(c -> {
						config.hudSize = FloatArgumentType.getFloat(c, "pixels");
						config.save();
						return ok(c, "Map size " + config.hudSize);
					})))
				.then(literal("text")
					.then(argument("scale", FloatArgumentType.floatArg(0.2f, 2f)).executes(c -> {
						config.style.textScale = FloatArgumentType.getFloat(c, "scale");
						config.save();
						return ok(c, "Label text scale " + config.style.textScale);
					})))
				.then(literal("head")
					.then(argument("size", FloatArgumentType.floatArg(0.15f, 1.5f)).executes(c -> {
						config.style.headSize = FloatArgumentType.getFloat(c, "size");
						config.save();
						return ok(c, "Player head size " + config.style.headSize + " of a room");
					})))
				.then(literal("names")
					.executes(c -> {
						config.showNames = !config.showNames;
						config.save();
						return ok(c, "Teammate names " + (config.showNames ? "on" : "off"));
					})
					.then(literal("leap").executes(c -> {
						config.namesOnlyWithLeap = !config.namesOnlyWithLeap;
						config.save();
						return ok(c, "Names only while holding a Spirit Leap: " + (config.namesOnlyWithLeap ? "on" : "off"));
					})))
				.then(literal("wither").executes(c -> {
					config.showWitherDoors = !config.showWitherDoors;
					config.save();
					return ok(c, "Predicted wither doors " + (config.showWitherDoors ? "shown" : "hidden"));
				}))
				.then(literal("checks").executes(c -> {
					config.showChecks = !config.showChecks;
					config.save();
					return ok(c, "Checkmarks " + (config.showChecks ? "on" : "off"));
				}))
				.then(literal("nav").executes(c -> {
					config.showNav = !config.showNav;
					config.save();
					return ok(c, "Nav to Blood " + (config.showNav ? "on" : "off"));
				}))
				.then(literal("boss").executes(c -> {
					config.showInBoss = !config.showInBoss;
					config.save();
					return ok(c, "Map in the boss room " + (config.showInBoss ? "shown" : "hidden"));
				}))
				.then(literal("rooms").executes(c -> {
					config.showRoomNames = !config.showRoomNames;
					config.save();
					return ok(c, "Room names " + (config.showRoomNames ? "on" : "off"));
				}))
				.then(literal("blank").executes(c -> {
					config.showBlank = !config.showBlank;
					config.save();
					return ok(c, "Guesses on unseen cells " + (config.showBlank ? "shown" : "hidden"));
				}))
				.then(literal("heat").executes(c -> {
					config.bloodHeat = !config.bloodHeat;
					config.save();
					return ok(c, "Blood heat overlay " + (config.bloodHeat ? "on" : "off"));
				}))
				.then(literal("lr")
					.then(argument("rate", DoubleArgumentType.doubleArg(0.0001, 2)).executes(c -> {
						service.setLearningRate(DoubleArgumentType.getDouble(c, "rate"));
						return 1;
					})))
				.then(literal("stats").executes(c -> {
					service.stats(RoomPredictorClient::tell);
					return 1;
				}))
				.then(literal("save").executes(c -> {
					service.save();
					return 1;
				}))
				.then(literal("rebuild").executes(c -> {
					service.rebuild();
					return 1;
				}))
				.then(literal("reload").executes(c -> {
					service.reloadModel();
					return 1;
				}))
				.then(literal("reset").executes(c -> ok(c, "This erases everything the model learned. Run §e/srp reset confirm§r to do it."))
					.then(literal("confirm").executes(c -> {
						service.resetModel();
						return 1;
					})))
		));
	}

	public static Config config() {
		return config;
	}

	/**
	 * Copies Devonian's map colours and sizes (and with {@code position}, its HUD position and
	 * size) from devonianConfig.json. The file is read off the client thread and never written.
	 */
	private static void importDv(boolean position, boolean firstLaunch) {
		Path file = DvImport.file(FabricLoader.getInstance().getGameDir());
		Config.Style base = config.style;
		CompletableFuture.supplyAsync(() -> {
			try {
				return DvImport.read(file, base);
			} catch (Exception e) {
				return null;
			}
		}).thenAccept(r -> Minecraft.getInstance().execute(() -> {
			config.dvImportVersion = DvImport.VERSION;
			if (r == null || r.imported().isEmpty()) {
				config.save();
				if (!firstLaunch) tell("Couldn't read Devonian map settings from " + file);
				return;
			}
			config.style = r.style();
			if (r.renderNames() != null) config.showNames = r.renderNames();
			if (r.namesRequireLeap() != null) config.namesOnlyWithLeap = r.namesRequireLeap();
			if (r.renderRoomNames() != null) config.showRoomNames = r.renderRoomNames();
			if (r.hideCommonRoomNames() != null) config.hideCommonNames = r.hideCommonRoomNames();
			if (r.colorRoomNames() != null) config.colorRoomNames = r.colorRoomNames();
			if (r.renderCheckmark() != null) config.showChecks = r.renderCheckmark();
			String extra = "";
			if (position && r.hudX() != null) {
				config.hudX = r.hudX();
				config.hudY = r.hudY();
				config.hudSize = Math.max(40, Math.min(400, r.hudSize()));
				extra = ", position " + config.hudX + "," + config.hudY + " and size " + Math.round(config.hudSize);
			}
			config.save();
			tell("Imported Devonian map style (" + r.imported().size() + " settings" + extra + ")."
				+ (firstLaunch ? " Use /srp importdv again to refresh it." : ""));
		}));
	}

	private static void drawHud(GuiGraphicsExtractor ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.hud.isHidden()) return; // F1
		if (config.debug) {
			String line = "SRP " + (config.enabled ? "" : "(off) ") + "floor=" + watcher.floor() + " | " + service.status();
			ctx.text(mc.font, line, 2, mc.getWindow().getGuiScaledHeight() - 10, 0xFFFFFF55, true);
		}
		if (!config.enabled || watcher.floor() == null) return;
		if (!watcher.active() && !config.showInBoss) return;
		Prediction p = service.latestOrFinished();
		if (p == null) return;
		float size = config.hudSize;
		// Draw the newest decoded map even if the prediction for it is still being computed.
		MapGrid shown = service.liveGrid();
		if (shown == null || shown.floor != p.grid.floor) shown = p.grid;
		MapPainter.draw(ctx, mc.font, shown, p, config.hudX, config.hudY, size,
			new MapPainter.Options(config.showBlank, config.bloodHeat, config.blankMinConfidence, config.style,
				config.showRoomNames ? watcher.names() : null, config.hideCommonNames, config.colorRoomNames,
				config.showWitherDoors ? config.witherMinConfidence : 2f, config.showChecks));
		boolean names = config.showNames && (!config.namesOnlyWithLeap || holdingLeap(mc));
		// In the boss room positions are off the dungeon grid and teammate markers are stale.
		if (watcher.active()) MapPainter.drawPlayers(ctx, mc.font, shown, config.hudX, config.hudY, size, config.style, markers(mc), names);

		if (config.showNav && mc.player != null) {
			int cx = (int) Math.floor((mc.player.getX() + 200) / 32), cz = (int) Math.floor((mc.player.getZ() + 200) / 32);
			int from = cx >= 0 && cz >= 0 && cx < shown.w && cz < shown.h ? shown.idx(cx, cz) : -1;
			MapPainter.drawNav(ctx, shown, config.hudX, config.hudY, size, config.style, from);
		}
	}

	/**
	 * Teammates from the map item's markers (positioned with the decoded map geometry), then you,
	 * drawn last with your head at your exact position. Dungeon rooms are 32 blocks apart with the
	 * grid starting at x/z = -200.
	 */
	private static List<MapPainter.Marker> markers(Minecraft mc) {
		List<MapPainter.Marker> out = new ArrayList<>();
		if (mc.player == null || mc.level == null) return out;
		String self = mc.player.getName().getString();

		// Map-item markers in grid-cell units.
		List<float[]> marks = new ArrayList<>();
		MapDecoder.Geometry g = service.geometry();
		if (g != null) {
			for (float[] t : watcher.teammates()) {
				float cx = (t[0] - g.offsetX() - g.roomSize() / 2f) / g.pitch() + 0.5f;
				float cz = (t[1] - g.offsetZ() - g.roomSize() / 2f) / g.pitch() + 0.5f;
				marks.add(new float[]{cx, cz, t[2]});
			}
		}

		// 1) Teammates whose player entity is loaded: exact position; drop the map marker nearest them.
		List<String> unplaced = new ArrayList<>();
		for (String name : watcher.party()) {
			if (name.equals(self)) continue;
			net.minecraft.world.entity.player.Player p = null;
			for (net.minecraft.world.entity.player.Player e : mc.level.players()) {
				if (e != mc.player && e.getName().getString().equals(name)) p = e;
			}
			if (p == null) {
				unplaced.add(name);
				continue;
			}
			float cx = cell(p.getX()), cz = cell(p.getZ());
			int nearest = -1;
			double best = 1.0;
			for (int i = 0; i < marks.size(); i++) {
				double d = Math.hypot(marks.get(i)[0] - cx, marks.get(i)[1] - cz);
				if (d < best) {
					best = d;
					nearest = i;
				}
			}
			if (nearest >= 0) marks.remove(nearest);
			out.add(new MapPainter.Marker(cx, cz, p.getYRot(), skin(mc, name), name));
		}

		// 2) The rest: remaining map markers matched to remaining names in party order.
		for (int i = 0; i < marks.size(); i++) {
			float[] m = marks.get(i);
			String name = i < unplaced.size() ? unplaced.get(i) : null;
			out.add(new MapPainter.Marker(m[0], m[1], m[2], name == null ? null : skin(mc, name), name));
		}

		// 3) You, last so you're on top.
		out.add(new MapPainter.Marker(cell(mc.player.getX()), cell(mc.player.getZ()), mc.player.getYRot(), mc.player.getSkin(), null));
		return out;
	}

	/** Spirit Leap or Infinileap in the main hand (by item name). */
	private static boolean holdingLeap(Minecraft mc) {
		if (mc.player == null) return false;
		String n = mc.player.getMainHandItem().getHoverName().getString();
		return n.contains("Spirit Leap") || n.contains("Infinileap");
	}

	/** World x/z to grid-cell units: rooms are 32 blocks apart, the grid starts at -200. */
	private static float cell(double world) {
		return (float) ((world + 184.5) / 32.0 + 0.5);
	}

	private static net.minecraft.world.entity.player.PlayerSkin skin(Minecraft mc, String name) {
		var conn = mc.getConnection();
		if (conn == null) return null;
		var info = conn.getPlayerInfo(name);
		return info == null ? null : info.getSkin();
	}

	private static int help(CommandContext<FabricClientCommandSource> c) {
		String[] lines = {
			"§6Strad Rooms Predictor§r commands:",
			"§e/srp maps§r - browse every stored map",
			"§e/srp toggle§r - enable/disable",
			"§e/srp importdv [position]§r - copy Devonian's map colours/sizes (and HUD position)",
			"§e/srp pos <x> <y>§r, §e/srp size <px>§r - move/resize the map",
			"§e/srp text <scale>§r, §e/srp head <size>§r - label text size, your head size",
			"§e/srp names§r - toggle teammate names; §e/srp names leap§r - only while holding a Spirit Leap",
			"§e/srp rooms§r - toggle room names (opened rooms only); §e/srp checks§r - checkmarks",
			"§e/srp wither§r - toggle predicted wither doors (the rest of the Blood rush path)",
			"§e/srp nav§r - toggle nav to Blood (highlights the doors between you and Blood)",
			"§e/srp boss§r - toggle keeping the map up in the boss room",
			"§e/srp blank§r - toggle guesses on unseen cells",
			"§e/srp heat§r - toggle the Blood heat overlay",
			"§e/srp stats§r - model and accuracy info",
			"§e/srp debug§r - what the mod currently sees; §e/srp debug overlay§r - status line on screen",
			"§e/srp lr <rate>§r - learning rate",
			"§e/srp rebuild§r - retrain from every stored map (weighted by how much was opened)",
			"§e/srp save§r / §e/srp reload§r / §e/srp reset§r - model file"
		};
		for (String l : lines) c.getSource().sendFeedback(Component.literal(l));
		return 1;
	}

	private static int ok(CommandContext<FabricClientCommandSource> c, String msg) {
		c.getSource().sendFeedback(Component.literal(PREFIX + msg));
		return 1;
	}

	/** Shows a message in the local chat window only (nothing is sent to the server). Thread-safe. */
	private static void tell(String msg) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.gui == null || mc.gui.hud == null || mc.gui.hud.getChat() == null) return;
			mc.gui.hud.getChat().addClientSystemMessage(Component.literal(PREFIX + msg));
		});
	}
}

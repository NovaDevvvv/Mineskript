package dev.novab.minescript;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public final class PythonBridgeServer {
	private static final int PROTOCOL_VERSION = 1;
	private static final List<BridgeMethod> BRIDGE_METHODS = List.of(
		method("send_chat", List.of("sendchat"), parameter("message")),
		method("get_chat", List.of("getchat"), optionalParameter("limit")),
		method("get_player_position", List.of("getplayerpos", "getplayerposition")),
		method("look_at", List.of("lookat"), parameter("x"), parameter("y"), parameter("z")),
		method("right_click", List.of("rightclick")),
		method("left_click", List.of("leftclick")),
		method("jump", List.of()),
		method("show_action_bar", List.of("showactionbar"), parameter("message")),
		method("show_title", List.of("showtitle"), parameter("title"), optionalParameter("subtitle"), optionalParameter("fade_in"), optionalParameter("stay"), optionalParameter("fade_out")),
		method("show_toast", List.of("showtoast"), parameter("title"), optionalParameter("description"), optionalParameter("icon")),
		method("show_inventory", List.of("showinventory"), parameter("title"), optionalParameter("rows"), optionalParameter("items")),
		method("move_forward", List.of("moveforward"), optionalParameter("state")),
		method("move_back", List.of("moveback"), optionalParameter("state")),
		method("move_left", List.of("moveleft"), optionalParameter("state")),
		method("move_right", List.of("moveright"), optionalParameter("state")),
		method("stop_moving", List.of("stopmoving")),
		method("set_sneaking", List.of("setsneaking"), optionalParameter("state")),
		method("set_sprinting", List.of("setsprinting"), optionalParameter("state")),
		method("run_java", List.of("runjava"), parameter("source")),
		method("get_inventory_slot", List.of("getinventoryslot"), parameter("slot")),
		method("get_inventory", List.of("getinventory")),
		method("quick_move_slot", List.of("quickmoveslot"), parameter("slot")),
		method("drop_slot", List.of("dropslot"), parameter("slot")),
		method("swap_slots", List.of("swapslots"), parameter("slot_a"), parameter("slot_b")),
		method("get_selected_hotbar_slot", List.of("getselectedhotbarslot")),
		method("select_hotbar_slot", List.of("selecthotbarslot"), parameter("slot")),
		method("get_leaderboard", List.of("getleaderboard")),
		method("click_slot", List.of("clickslot"), parameter("slot"), optionalParameter("button"), optionalParameter("action_type")),
		method("get_target_block", List.of("gettargetblock")),
		method("get_target_entity", List.of("gettargetentity")),
		method("get_entity_nbt", List.of("getnbt"), parameter("entity_id")),
		method("get_health", List.of("gethealth")),
		method("get_hunger", List.of("gethunger")),
		method("get_armor", List.of("getarmor")),
		method("get_dimension", List.of("getdimension")),
		method("get_biome", List.of("getbiome")),
		method("get_nearby_entities", List.of("getnearbyentities"), optionalParameter("radius")),
		method("get_nearby_players", List.of("getnearbyplayers"), optionalParameter("radius")),
		method("poll_events", List.of("pollevents"), optionalParameter("types"), optionalParameter("limit"))
	);

	public interface Api {
		JsonElement sendChat(String message);

		JsonElement getChat(int limit);

		JsonElement getPlayerPos();

		JsonElement lookAt(double x, double y, double z);

		JsonElement rightClick();

		JsonElement leftClick();

		JsonElement jump();

		JsonElement showActionBar(String message);

		JsonElement showTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut);

		JsonElement showToast(String title, String description, String iconId);

		JsonElement showInventory(String title, int rows, JsonArray items);

		JsonElement setForwardPressed(boolean pressed);

		JsonElement setBackPressed(boolean pressed);

		JsonElement setLeftPressed(boolean pressed);

		JsonElement setRightPressed(boolean pressed);

		JsonElement stopMoving();

		JsonElement setSneaking(boolean sneaking);

		JsonElement setSprinting(boolean sprinting);

		JsonElement runJava(String source);

		JsonElement getObjectAtInventorySlot(int slotIndex);

		JsonElement getInventory();

		JsonElement quickMoveSlot(int slotIndex);

		JsonElement dropSlot(int slotIndex);

		JsonElement swapSlots(int firstSlot, int secondSlot);

		JsonElement getSelectedHotbarSlot();

		JsonElement selectHotbarSlot(int slotIndex);

		JsonElement getLeaderboard();

		JsonElement clickSlot(int slotIndex, int button, String actionType);

		JsonElement getTargetBlock();

		JsonElement getTargetEntity();

		JsonElement getEntityNbt(int entityId);

		JsonElement getHealth();

		JsonElement getHunger();

		JsonElement getArmor();

		JsonElement getDimension();

		JsonElement getBiome();

		JsonElement getNearbyEntities(double radius);

		JsonElement getNearbyPlayers(double radius);

		JsonElement pollEvents(List<String> eventTypes, int limit);
	}

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private final MinescriptConfig config;
	private final Api api;
	private HttpServer server;
	private ExecutorService executor;

	public PythonBridgeServer(MinescriptConfig config, Api api) {
		this.config = config;
		this.api = api;
	}

	public void start() {
		if (this.server != null) {
			return;
		}

		try {
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", this.config.port()), 0);
			this.server.createContext("/invoke", new InvokeHandler());
			this.executor = Executors.newCachedThreadPool();
			this.server.setExecutor(this.executor);
			this.server.start();
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to start Minescript bridge server", exception);
		}
	}

	private final class InvokeHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			JsonObject response = new JsonObject();

			try {
				if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
					throw new IllegalArgumentException("Only POST is supported");
				}

				JsonObject request = readRequest(exchange.getRequestBody());
				String method = request.get("method").getAsString();
				JsonObject args = request.has("args") && request.get("args").isJsonObject()
					? request.getAsJsonObject("args")
					: new JsonObject();

				response.addProperty("ok", true);
				response.add("result", dispatch(method, args));
				writeResponse(exchange, 200, response);
			} catch (Exception exception) {
				response.addProperty("ok", false);
				response.addProperty("error", exception.getMessage());
				writeResponse(exchange, 500, response);
			}
		}

		private JsonObject readRequest(InputStream stream) throws IOException {
			String body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			JsonObject json = GSON.fromJson(body, JsonObject.class);
			if (json == null || !json.has("method")) {
				throw new IllegalArgumentException("Missing method");
			}

			return json;
		}

		private JsonElement dispatch(String methodName, JsonObject args) {
			String normalized = canonicalMethodName(methodName);
			return switch (normalized) {
				case "listmethods" -> listMethods();
				case "bridgeinfo" -> bridgeInfo();
				case "send_chat" -> api.sendChat(getString(args, "message", ""));
				case "get_chat" -> api.getChat(getInt(args, "limit", 20));
				case "get_player_position" -> api.getPlayerPos();
				case "look_at" -> api.lookAt(
					getDouble(args, "x", 0.0),
					getDouble(args, "y", 0.0),
					getDouble(args, "z", 0.0)
				);
				case "right_click" -> api.rightClick();
				case "left_click" -> api.leftClick();
				case "jump" -> api.jump();
				case "show_action_bar" -> api.showActionBar(getString(args, "message", ""));
				case "show_title" -> api.showTitle(
					getString(args, "title", ""),
					getString(args, "subtitle", ""),
					getInt(args, "fade_in", 10),
					getInt(args, "stay", 70),
					getInt(args, "fade_out", 20)
				);
				case "show_toast" -> api.showToast(
					getString(args, "title", ""),
					getString(args, "description", ""),
					getString(args, "icon", "minecraft:paper")
				);
				case "show_inventory" -> api.showInventory(
					getString(args, "title", "Inventory"),
					getInt(args, "rows", 3),
					getJsonArray(args, "items")
				);
				case "move_forward" -> api.setForwardPressed(getBoolean(args, "state", true));
				case "move_back" -> api.setBackPressed(getBoolean(args, "state", true));
				case "move_left" -> api.setLeftPressed(getBoolean(args, "state", true));
				case "move_right" -> api.setRightPressed(getBoolean(args, "state", true));
				case "stop_moving" -> api.stopMoving();
				case "set_sneaking" -> api.setSneaking(getBoolean(args, "state", true));
				case "set_sprinting" -> api.setSprinting(getBoolean(args, "state", true));
				case "run_java" -> api.runJava(getString(args, "source", ""));
				case "get_inventory_slot" -> api.getObjectAtInventorySlot(getInt(args, "slot", -1));
				case "get_inventory" -> api.getInventory();
				case "quick_move_slot" -> api.quickMoveSlot(getInt(args, "slot", -1));
				case "drop_slot" -> api.dropSlot(getInt(args, "slot", -1));
				case "swap_slots" -> api.swapSlots(getInt(args, "slot_a", -1), getInt(args, "slot_b", -1));
				case "get_selected_hotbar_slot" -> api.getSelectedHotbarSlot();
				case "select_hotbar_slot" -> api.selectHotbarSlot(getInt(args, "slot", -1));
				case "get_leaderboard" -> api.getLeaderboard();
				case "click_slot" -> api.clickSlot(
					getInt(args, "slot", -1),
					getInt(args, "button", 0),
					getString(args, "action_type", "PICKUP")
				);
				case "get_target_block" -> api.getTargetBlock();
				case "get_target_entity" -> api.getTargetEntity();
				case "get_entity_nbt" -> api.getEntityNbt(getInt(args, "entity_id", -1));
				case "get_health" -> api.getHealth();
				case "get_hunger" -> api.getHunger();
				case "get_armor" -> api.getArmor();
				case "get_dimension" -> api.getDimension();
				case "get_biome" -> api.getBiome();
				case "get_nearby_entities" -> api.getNearbyEntities(getDouble(args, "radius", 16.0));
				case "get_nearby_players" -> api.getNearbyPlayers(getDouble(args, "radius", 16.0));
				case "poll_events" -> api.pollEvents(getStringList(args, "types"), getInt(args, "limit", 100));
				default -> throw new IllegalArgumentException("Unknown method: " + methodName);
			};
		}

		private int getInt(JsonObject json, String key, int defaultValue) {
			return json.has(key) ? json.get(key).getAsInt() : defaultValue;
		}

		private double getDouble(JsonObject json, String key, double defaultValue) {
			return json.has(key) ? json.get(key).getAsDouble() : defaultValue;
		}

		private boolean getBoolean(JsonObject json, String key, boolean defaultValue) {
			return json.has(key) ? json.get(key).getAsBoolean() : defaultValue;
		}

		private String getString(JsonObject json, String key, String defaultValue) {
			return json.has(key) ? json.get(key).getAsString() : defaultValue;
		}

		private JsonArray getJsonArray(JsonObject json, String key) {
			return json.has(key) && json.get(key).isJsonArray() ? json.getAsJsonArray(key) : new JsonArray();
		}

		private List<String> getStringList(JsonObject json, String key) {
			if (!json.has(key) || !json.get(key).isJsonArray()) {
				return List.of();
			}

			JsonArray array = json.getAsJsonArray(key);
			List<String> values = new ArrayList<>(array.size());
			for (JsonElement element : array) {
				values.add(element.getAsString());
			}

			return values;
		}

		private void writeResponse(HttpExchange exchange, int statusCode, JsonObject payload) throws IOException {
			byte[] body = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
			exchange.sendResponseHeaders(statusCode, body.length);

			try (OutputStream outputStream = exchange.getResponseBody()) {
				outputStream.write(body);
			}
		}

		private String canonicalMethodName(String methodName) {
			String normalized = normalizeMethodName(methodName);
			for (BridgeMethod method : BRIDGE_METHODS) {
				if (normalizeMethodName(method.name()).equals(normalized)
					|| method.aliases().stream().map(PythonBridgeServer::normalizeMethodName).anyMatch(normalized::equals)) {
					return method.name();
				}
			}

			return normalized;
		}

		private JsonArray listMethods() {
			JsonArray methods = new JsonArray();
			for (BridgeMethod method : BRIDGE_METHODS) {
				JsonObject json = new JsonObject();
				json.addProperty("name", method.name());
				JsonArray aliases = new JsonArray();
				method.aliases().forEach(aliases::add);
				json.add("aliases", aliases);
				JsonArray parameters = new JsonArray();
				for (BridgeParameter parameter : method.parameters()) {
					JsonObject parameterJson = new JsonObject();
					parameterJson.addProperty("name", parameter.name());
					parameterJson.addProperty("optional", parameter.optional());
					parameters.add(parameterJson);
				}
				json.add("params", parameters);
				methods.add(json);
			}
			return methods;
		}

		private JsonObject bridgeInfo() {
			JsonObject info = new JsonObject();
			info.addProperty("protocol_version", PROTOCOL_VERSION);
			info.addProperty("method_count", BRIDGE_METHODS.size());
			return info;
		}
	}

	private static BridgeMethod method(String name, List<String> aliases, BridgeParameter... parameters) {
		return new BridgeMethod(name, aliases, List.of(parameters));
	}

	private static BridgeParameter parameter(String name) {
		return new BridgeParameter(name, false);
	}

	private static BridgeParameter optionalParameter(String name) {
		return new BridgeParameter(name, true);
	}

	private static String normalizeMethodName(String value) {
		return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	private record BridgeMethod(String name, List<String> aliases, List<BridgeParameter> parameters) {
	}

	private record BridgeParameter(String name, boolean optional) {
	}
}

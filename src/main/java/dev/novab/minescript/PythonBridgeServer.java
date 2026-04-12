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
	public interface Api {
		JsonElement sendChat(String message);

		JsonElement getChat(int limit);

		JsonElement getPlayerPos();

		JsonElement lookAt(double x, double y, double z);

		JsonElement rightClick();

		JsonElement leftClick();

		JsonElement jump();

		JsonElement setForwardPressed(boolean pressed);

		JsonElement setBackPressed(boolean pressed);

		JsonElement setLeftPressed(boolean pressed);

		JsonElement setRightPressed(boolean pressed);

		JsonElement stopMoving();

		JsonElement setSneaking(boolean sneaking);

		JsonElement setSprinting(boolean sprinting);

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
			String normalized = methodName.toLowerCase(Locale.ROOT);
			return switch (normalized) {
				case "sendchat" -> api.sendChat(getString(args, "message", ""));
				case "getchat" -> api.getChat(getInt(args, "limit", 20));
				case "getplayerpos" -> api.getPlayerPos();
				case "lookat" -> api.lookAt(
					getDouble(args, "x", 0.0),
					getDouble(args, "y", 0.0),
					getDouble(args, "z", 0.0)
				);
				case "rightclick" -> api.rightClick();
				case "leftclick" -> api.leftClick();
				case "jump" -> api.jump();
				case "moveforward", "forward" -> api.setForwardPressed(getBoolean(args, "state", true));
				case "moveback", "back" -> api.setBackPressed(getBoolean(args, "state", true));
				case "moveleft", "left" -> api.setLeftPressed(getBoolean(args, "state", true));
				case "moveright", "right" -> api.setRightPressed(getBoolean(args, "state", true));
				case "stopmoving" -> api.stopMoving();
				case "sneak" -> api.setSneaking(getBoolean(args, "state", true));
				case "sprint" -> api.setSprinting(getBoolean(args, "state", true));
				case "getobjectatinventorryslot", "getobjectatinventoryslot" -> api.getObjectAtInventorySlot(getInt(args, "slot", -1));
				case "getinventory" -> api.getInventory();
				case "quickmoveslot" -> api.quickMoveSlot(getInt(args, "slot", -1));
				case "dropslot" -> api.dropSlot(getInt(args, "slot", -1));
				case "swapslots" -> api.swapSlots(getInt(args, "slot_a", -1), getInt(args, "slot_b", -1));
				case "getselectedhotbarslot" -> api.getSelectedHotbarSlot();
				case "selecthotbarslot" -> api.selectHotbarSlot(getInt(args, "slot", -1));
				case "getleaderboard", "getleaaderboard" -> api.getLeaderboard();
				case "clickslot" -> api.clickSlot(
					getInt(args, "slot", -1),
					getInt(args, "button", 0),
					getString(args, "action_type", "PICKUP")
				);
				case "gettargetblock" -> api.getTargetBlock();
				case "gettargetentity" -> api.getTargetEntity();
				case "gethealth" -> api.getHealth();
				case "gethunger" -> api.getHunger();
				case "getarmor" -> api.getArmor();
				case "getdimension" -> api.getDimension();
				case "getbiome" -> api.getBiome();
				case "getnearbyentities" -> api.getNearbyEntities(getDouble(args, "radius", 16.0));
				case "getnearbyplayers" -> api.getNearbyPlayers(getDouble(args, "radius", 16.0));
				case "pollevents" -> api.pollEvents(getStringList(args, "types"), getInt(args, "limit", 100));
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
	}
}

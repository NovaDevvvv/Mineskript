package dev.novab.minescript;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import net.fabricmc.loader.api.FabricLoader;

public record MinescriptConfig(
	Path configFile,
	Path scriptsDir,
	Path pythonDir,
	Path logsDir,
	String pythonCommand,
	int port
) {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final int DEFAULT_PORT = 47641;

	public static MinescriptConfig loadOrCreate() {
		Path configDir = FabricLoader.getInstance().getConfigDir();
		Path configFile = configDir.resolve("minescript.json");
		Path minecraftConfigDir = configDir.resolve("minecraft");
		Path scriptsDir = minecraftConfigDir.resolve("scripts");
		Path pythonDir = minecraftConfigDir.resolve("python");
		Path logsDir = minecraftConfigDir.resolve("logs");

		String pythonCommand = defaultPythonCommand();
		int port = DEFAULT_PORT;

		if (Files.isRegularFile(configFile)) {
			try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
				JsonObject json = GSON.fromJson(reader, JsonObject.class);
				if (json != null) {
					if (json.has("python_command")) {
						pythonCommand = json.get("python_command").getAsString();
					}
					if (json.has("port")) {
						port = json.get("port").getAsInt();
					}
				}
			} catch (IOException exception) {
				throw new IllegalStateException("Unable to read Minescript config", exception);
			}
		}

		MinescriptConfig config = new MinescriptConfig(configFile, scriptsDir, pythonDir, logsDir, pythonCommand, port);
		config.writeConfig();
		return config;
	}

	public void ensureSupportFiles() {
		try {
			Files.createDirectories(this.scriptsDir);
			Files.createDirectories(this.pythonDir);
			Files.createDirectories(this.logsDir);

			writeFile(this.pythonDir.resolve("minescript.py"), pythonModuleSource());
			writeFile(this.scriptsDir.resolve("example.py"), exampleScriptSource());
			writeFile(this.scriptsDir.resolve("test.py"), testScriptSource());
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to create Minescript support files", exception);
		}
	}

	private void writeConfig() {
		try {
			Files.createDirectories(this.configFile.getParent());
			JsonObject json = new JsonObject();
			json.addProperty("python_command", this.pythonCommand);
			json.addProperty("port", this.port);
			try (Writer writer = Files.newBufferedWriter(
				this.configFile,
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE
			)) {
				GSON.toJson(json, writer);
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to write Minescript config", exception);
		}
	}

	private void writeFile(Path path, String content) throws IOException {
		Files.writeString(
			path,
			content,
			StandardCharsets.UTF_8,
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING,
			StandardOpenOption.WRITE
		);
	}

	private static String defaultPythonCommand() {
		String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		return osName.contains("win") ? "py" : "python3";
	}

	private static String lines(String... lines) {
		return String.join("\n", lines) + "\n";
	}

	private String pythonModuleSource() {
		return lines(
			"\"\"\"Python helper module for talking to the local Minescript Fabric bridge.\"\"\"",
			"",
			"import atexit",
			"import json",
			"import os",
			"import threading",
			"import time",
			"import traceback",
			"import urllib.error",
			"import urllib.request",
			"",
			"_PORT = int(os.environ.get(\"MINESCRIPT_PORT\", \"" + this.port + "\"))",
			"_BASE_URL = f\"http://127.0.0.1:{_PORT}/invoke\"",
			"_CONTROL_PREFIX = os.environ.get(\"MINESCRIPT_CONTROL_PREFIX\", \"__MINESCRIPT_CONTROL__:\")",
			"_EVENT_HANDLERS = {\"chat\": [], \"tick\": [], \"join_world\": []}",
			"_EVENT_THREAD = None",
			"_STOP_EVENTS = threading.Event()",
			"",
			"",
			"def _invoke(method, **kwargs):",
			"\t\"\"\"Send a request to the local Minescript bridge and return the decoded result.\"\"\"",
			"\tpayload = json.dumps({\"method\": method, \"args\": kwargs}).encode(\"utf-8\")",
			"\trequest = urllib.request.Request(",
			"\t\t_BASE_URL,",
			"\t\tdata=payload,",
			"\t\theaders={\"Content-Type\": \"application/json\"},",
			"\t\tmethod=\"POST\",",
			"\t)",
			"",
			"\ttry:",
			"\t\twith urllib.request.urlopen(request, timeout=5) as response:",
			"\t\t\tdata = json.loads(response.read().decode(\"utf-8\"))",
			"\texcept urllib.error.URLError as exc:",
			"\t\traise RuntimeError(f\"Minescript bridge is unavailable: {exc}\") from exc",
			"",
			"\tif not data.get(\"ok\", False):",
			"\t\traise RuntimeError(data.get(\"error\", \"Unknown Minescript error\"))",
			"",
			"\treturn data.get(\"result\")",
			"",
			"",
			"def _emit_control(payload):",
			"\t\"\"\"Send a control message back to the Java runner through stdout.\"\"\"",
			"\tprint(f\"{_CONTROL_PREFIX}{json.dumps(payload, separators=(\\\",\\\", \\\"\\:\\\"))}\", flush=True)",
			"",
			"",
			"def _set_chat_output(enabled):",
			"\t\"\"\"Enable or disable chat mirroring for future script output lines.\"\"\"",
			"\t_emit_control({\"command\": \"set_chat_output\", \"enabled\": bool(enabled)})",
			"",
			"",
			"def getchat(limit=20):",
			"\t\"\"\"Return the most recent captured chat lines up to the requested limit.\"\"\"",
			"\treturn _invoke(\"getchat\", limit=limit)",
			"",
			"",
			"def sendchat(message):",
			"\t\"\"\"Send a chat message or command from the client.\"\"\"",
			"\treturn _invoke(\"sendchat\", message=str(message))",
			"",
			"",
			"def getplayerpos():",
			"\t\"\"\"Return the local player's position, rotation, and block coordinates.\"\"\"",
			"\treturn _invoke(\"getplayerpos\")",
			"",
			"",
			"def lookat(x, y, z):",
			"\t\"\"\"Rotate the player to face a world-space position.\"\"\"",
			"\treturn _invoke(\"lookat\", x=float(x), y=float(y), z=float(z))",
			"",
			"",
			"def rightclick():",
			"\t\"\"\"Perform a normal right-click interaction with the current target or held item.\"\"\"",
			"\treturn _invoke(\"rightclick\")",
			"",
			"",
			"def leftclick():",
			"\t\"\"\"Perform a normal left-click attack or block hit.\"\"\"",
			"\treturn _invoke(\"leftclick\")",
			"",
			"",
			"def jump():",
			"\t\"\"\"Make the local player jump once.\"\"\"",
			"\treturn _invoke(\"jump\")",
			"",
			"",
			"def moveforward(state=True):",
			"\t\"\"\"Press or release the forward movement key.\"\"\"",
			"\treturn _invoke(\"moveforward\", state=bool(state))",
			"",
			"",
			"def moveback(state=True):",
			"\t\"\"\"Press or release the back movement key.\"\"\"",
			"\treturn _invoke(\"moveback\", state=bool(state))",
			"",
			"",
			"def moveleft(state=True):",
			"\t\"\"\"Press or release the left strafe key.\"\"\"",
			"\treturn _invoke(\"moveleft\", state=bool(state))",
			"",
			"",
			"def moveright(state=True):",
			"\t\"\"\"Press or release the right strafe key.\"\"\"",
			"\treturn _invoke(\"moveright\", state=bool(state))",
			"",
			"",
			"def stopmoving():",
			"\t\"\"\"Release all scripted movement keys.\"\"\"",
			"\treturn _invoke(\"stopmoving\")",
			"",
			"",
			"def forward(state=True):",
			"\t\"\"\"Alias for moveforward.\"\"\"",
			"\treturn moveforward(state)",
			"",
			"",
			"def back(state=True):",
			"\t\"\"\"Alias for moveback.\"\"\"",
			"\treturn moveback(state)",
			"",
			"",
			"def left(state=True):",
			"\t\"\"\"Alias for moveleft.\"\"\"",
			"\treturn moveleft(state)",
			"",
			"",
			"def right(state=True):",
			"\t\"\"\"Alias for moveright.\"\"\"",
			"\treturn moveright(state)",
			"",
			"",
			"def sneak(state=True):",
			"\t\"\"\"Enable or disable sneaking.\"\"\"",
			"\treturn _invoke(\"sneak\", state=bool(state))",
			"",
			"",
			"def sprint(state=True):",
			"\t\"\"\"Enable or disable sprinting.\"\"\"",
			"\treturn _invoke(\"sprint\", state=bool(state))",
			"",
			"",
			"def getobjectatinventorryslot(slot):",
			"\t\"\"\"Return item data for a visible inventory slot.\"\"\"",
			"\treturn _invoke(\"getobjectatinventorryslot\", slot=slot)",
			"",
			"",
			"def getinventory():",
			"\t\"\"\"Return every visible slot from the current screen handler.\"\"\"",
			"\treturn _invoke(\"getinventory\")",
			"",
			"",
			"def quickmoveslot(slot):",
			"\t\"\"\"Shift-click a slot in the current screen handler.\"\"\"",
			"\treturn _invoke(\"quickmoveslot\", slot=slot)",
			"",
			"",
			"def dropslot(slot):",
			"\t\"\"\"Throw the item stack from a slot.\"\"\"",
			"\treturn _invoke(\"dropslot\", slot=slot)",
			"",
			"",
			"def swapslots(slot_a, slot_b):",
			"\t\"\"\"Swap two slots using a simple pickup sequence.\"\"\"",
			"\treturn _invoke(\"swapslots\", slot_a=slot_a, slot_b=slot_b)",
			"",
			"",
			"def getselectedhotbarslot():",
			"\t\"\"\"Return the currently selected hotbar slot index.\"\"\"",
			"\treturn _invoke(\"getselectedhotbarslot\")",
			"",
			"",
			"def selecthotbarslot(slot):",
			"\t\"\"\"Select a hotbar slot by index from 0 to 8.\"\"\"",
			"\treturn _invoke(\"selecthotbarslot\", slot=slot)",
			"",
			"",
			"def getleaderboard():",
			"\t\"\"\"Return the sidebar scoreboard title and entries.\"\"\"",
			"\treturn _invoke(\"getleaderboard\")",
			"",
			"",
			"def getleaaderboard():",
			"\t\"\"\"Compatibility alias for the original misspelled leaderboard helper.\"\"\"",
			"\treturn getleaderboard()",
			"",
			"",
			"def clickslot(slot, button=0, action_type=\"PICKUP\"):",
			"\t\"\"\"Click a slot in the current screen handler using a Minecraft slot action.\"\"\"",
			"\treturn _invoke(\"clickslot\", slot=slot, button=button, action_type=action_type)",
			"",
			"",
			"def gettargetblock():",
			"\t\"\"\"Return block information for the current crosshair target, or None.\"\"\"",
			"\treturn _invoke(\"gettargetblock\")",
			"",
			"",
			"def gettargetentity():",
			"\t\"\"\"Return entity information for the current crosshair target, or None.\"\"\"",
			"\treturn _invoke(\"gettargetentity\")",
			"",
			"",
			"def gethealth():",
			"\t\"\"\"Return the player's current and maximum health.\"\"\"",
			"\treturn _invoke(\"gethealth\")",
			"",
			"",
			"def gethunger():",
			"\t\"\"\"Return the player's hunger and saturation values.\"\"\"",
			"\treturn _invoke(\"gethunger\")",
			"",
			"",
			"def getarmor():",
			"\t\"\"\"Return the player's armor value.\"\"\"",
			"\treturn _invoke(\"getarmor\")",
			"",
			"",
			"def getdimension():",
			"\t\"\"\"Return the current dimension identifier.\"\"\"",
			"\treturn _invoke(\"getdimension\")",
			"",
			"",
			"def getbiome():",
			"\t\"\"\"Return the biome identifier at the player's current position.\"\"\"",
			"\treturn _invoke(\"getbiome\")",
			"",
			"",
			"def getnearbyentities(radius=16.0):",
			"\t\"\"\"Return nearby entity data within the given radius.\"\"\"",
			"\treturn _invoke(\"getnearbyentities\", radius=float(radius))",
			"",
			"",
			"def getnearbyplayers(radius=16.0):",
			"\t\"\"\"Return nearby player data including held items, facing, and velocity.\"\"\"",
			"\treturn _invoke(\"getnearbyplayers\", radius=float(radius))",
			"",
			"",
			"def log(message):",
			"\t\"\"\"Write an info-level Minescript log line.\"\"\"",
			"\t_emit_control({\"command\": \"log\", \"level\": \"INFO\", \"message\": str(message)})",
			"",
			"",
			"def error(message):",
			"\t\"\"\"Write an error-level Minescript log line.\"\"\"",
			"\t_emit_control({\"command\": \"log\", \"level\": \"ERROR\", \"message\": str(message)})",
			"",
			"",
			"def disablelog():",
			"\t\"\"\"Stop mirroring future script output into Minecraft chat.\"\"\"",
			"\t_set_chat_output(False)",
			"",
			"",
			"def enablelog():",
			"\t\"\"\"Resume mirroring future script output into Minecraft chat.\"\"\"",
			"\t_set_chat_output(True)",
			"",
			"",
			"def _ensure_event_thread():",
			"\t\"\"\"Start the background event polling thread if needed.\"\"\"",
			"\tglobal _EVENT_THREAD",
			"\tif _EVENT_THREAD is not None and _EVENT_THREAD.is_alive():",
			"\t\treturn",
			"",
			"\t_STOP_EVENTS.clear()",
			"\t_EVENT_THREAD = threading.Thread(target=_event_loop, name=\"minescript-events\", daemon=True)",
			"\t_EVENT_THREAD.start()",
			"",
			"",
			"def _register_event(event_type, handler):",
			"\t\"\"\"Register a Python callback for a named Minescript event.\"\"\"",
			"\t_EVENT_HANDLERS.setdefault(event_type, []).append(handler)",
			"\t_ensure_event_thread()",
			"\treturn handler",
			"",
			"",
			"def on_chat(handler):",
			"\t\"\"\"Register a handler for captured chat events.\"\"\"",
			"\treturn _register_event(\"chat\", handler)",
			"",
			"",
			"def on_tick(handler):",
			"\t\"\"\"Register a handler for per-tick events while the script is alive.\"\"\"",
			"\treturn _register_event(\"tick\", handler)",
			"",
			"",
			"def on_join_world(handler):",
			"\t\"\"\"Register a handler for world-join events.\"\"\"",
			"\treturn _register_event(\"join_world\", handler)",
			"",
			"",
			"def _event_loop():",
			"\t\"\"\"Poll the Java bridge for queued events and dispatch them to handlers.\"\"\"",
			"\twhile not _STOP_EVENTS.is_set():",
			"\t\ttry:",
			"\t\t\tevents = _invoke(\"pollevents\", types=list(_EVENT_HANDLERS.keys()), limit=100)",
			"\t\texcept Exception as exc:",
			"\t\t\terror(f\"event polling failed: {exc}\")",
			"\t\t\ttraceback.print_exc()",
			"\t\t\ttime.sleep(1.0)",
			"\t\t\tcontinue",
			"",
			"\t\tfor event in events:",
			"\t\t\tevent_type = event.get(\"type\")",
			"\t\t\tfor handler in list(_EVENT_HANDLERS.get(event_type, [])):",
			"\t\t\t\ttry:",
			"\t\t\t\t\thandler(event)",
			"\t\t\t\texcept Exception as exc:",
			"\t\t\t\t\terror(f\"{event_type} handler failed: {exc}\")",
			"\t\t\t\t\ttraceback.print_exc()",
			"",
			"\t\ttime.sleep(0.05)",
			"",
			"",
			"def stop_events():",
			"\t\"\"\"Stop the background event polling thread.\"\"\"",
			"\t_STOP_EVENTS.set()",
			"",
			"",
			"def wait_forever(interval=0.1):",
			"\t\"\"\"Keep the script alive so background event handlers can continue running.\"\"\"",
			"\twhile True:",
			"\t\ttime.sleep(interval)",
			"",
			"",
			"atexit.register(stop_events)"
		);
	}

	private static String exampleScriptSource() {
		return lines(
			"import minescript",
			"",
			"minescript.log(\"player position:\")",
			"print(minescript.getplayerpos())",
			"",
			"minescript.log(\"target block:\")",
			"print(minescript.gettargetblock())",
			"",
			"@minescript.on_chat",
			"def handle_chat(event):",
			"\tif \"hello\" in event[\"message\"].lower():",
			"\t\tminescript.sendchat(\"Hello from Minescript\")",
			"",
			"# minescript.wait_forever()"
		);
	}

	private static String testScriptSource() {
		return lines(
			"import time",
			"",
			"import minescript",
			"",
			"DELAY_SECONDS = 2",
			"",
			"def wait_step():",
			"\ttime.sleep(DELAY_SECONDS)",
			"",
			"def run_step(name, action):",
			"\tminescript.log(f\"running {name}\")",
			"\ttry:",
			"\t\tresult = action()",
			"\t\tprint(f\"{name}: {result}\")",
			"\texcept Exception as exc:",
			"\t\tminescript.error(f\"{name} failed: {exc}\")",
			"\t\tprint(f\"{name} failed: {exc}\")",
			"\tfinally:",
			"\t\twait_step()",
			"",
			"@minescript.on_chat",
			"def handle_chat(event):",
			"\tif not getattr(handle_chat, \"done\", False):",
			"\t\tprint(f\"chat event: {event}\")",
			"\t\thandle_chat.done = True",
			"",
			"@minescript.on_tick",
			"def handle_tick(event):",
			"\tif not getattr(handle_tick, \"done\", False):",
			"\t\tprint(f\"tick event: {event}\")",
			"\t\thandle_tick.done = True",
			"",
			"@minescript.on_join_world",
			"def handle_join(event):",
			"\tif not getattr(handle_join, \"done\", False):",
			"\t\tprint(f\"join_world event: {event}\")",
			"\t\thandle_join.done = True",
			"",
			"run_step(\"sendchat\", lambda: minescript.sendchat(\"Minescript test.py starting\"))",
			"run_step(\"getchat\", lambda: minescript.getchat())",
			"run_step(\"getplayerpos\", lambda: minescript.getplayerpos())",
			"",
			"player_pos = minescript.getplayerpos()",
			"run_step(\"lookat\", lambda: minescript.lookat(player_pos[\"x\"] + 1.0, player_pos[\"y\"], player_pos[\"z\"] + 1.0))",
			"run_step(\"rightclick\", lambda: minescript.rightclick())",
			"run_step(\"leftclick\", lambda: minescript.leftclick())",
			"run_step(\"jump\", lambda: minescript.jump())",
			"run_step(\"sneak_on\", lambda: minescript.sneak(True))",
			"run_step(\"sneak_off\", lambda: minescript.sneak(False))",
			"run_step(\"sprint_on\", lambda: minescript.sprint(True))",
			"run_step(\"sprint_off\", lambda: minescript.sprint(False))",
			"run_step(\"moveforward_on\", lambda: minescript.moveforward(True))",
			"run_step(\"stopmoving\", lambda: minescript.stopmoving())",
			"run_step(\"getobjectatinventorryslot\", lambda: minescript.getobjectatinventorryslot(0))",
			"run_step(\"getinventory\", lambda: minescript.getinventory())",
			"run_step(\"quickmoveslot\", lambda: minescript.quickmoveslot(0))",
			"run_step(\"dropslot\", lambda: minescript.dropslot(0))",
			"run_step(\"swapslots\", lambda: minescript.swapslots(0, 1))",
			"run_step(\"getselectedhotbarslot\", lambda: minescript.getselectedhotbarslot())",
			"run_step(\"selecthotbarslot\", lambda: minescript.selecthotbarslot(0))",
			"run_step(\"getleaderboard\", lambda: minescript.getleaderboard())",
			"run_step(\"gettargetblock\", lambda: minescript.gettargetblock())",
			"run_step(\"gettargetentity\", lambda: minescript.gettargetentity())",
			"run_step(\"gethealth\", lambda: minescript.gethealth())",
			"run_step(\"gethunger\", lambda: minescript.gethunger())",
			"run_step(\"getarmor\", lambda: minescript.getarmor())",
			"run_step(\"getdimension\", lambda: minescript.getdimension())",
			"run_step(\"getbiome\", lambda: minescript.getbiome())",
			"run_step(\"getnearbyentities\", lambda: minescript.getnearbyentities(16.0))",
			"run_step(\"getnearbyplayers\", lambda: minescript.getnearbyplayers(16.0))",
			"run_step(\"log\", lambda: minescript.log(\"test.py log message\"))",
			"run_step(\"error\", lambda: minescript.error(\"test.py error message\"))",
			"run_step(\"disablelog\", lambda: minescript.disablelog())",
			"run_step(\"enablelog\", lambda: minescript.enablelog())",
			"run_step(\"sendchat_hook_trigger\", lambda: minescript.sendchat(\"hook test message from test.py\"))",
			"",
			"print(\"waiting briefly for hook callbacks\")",
			"time.sleep(4)",
			"",
			"minescript.sendchat(\"Minescript test.py finished\")",
			"print(\"done\")"
		);
	}
}

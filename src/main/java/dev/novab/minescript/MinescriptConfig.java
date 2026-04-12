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

public final class MinescriptConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int DEFAULT_PORT = 47641;

	private final Path rootDir;
	private final Path scriptsDir;
	private final Path pythonDir;
	private final Path logsDir;
	private final Path configFile;
	private final String pythonCommand;
	private final int port;

	private MinescriptConfig(Path rootDir, Path scriptsDir, Path pythonDir, Path logsDir, Path configFile, String pythonCommand, int port) {
		this.rootDir = rootDir;
		this.scriptsDir = scriptsDir;
		this.pythonDir = pythonDir;
		this.logsDir = logsDir;
		this.configFile = configFile;
		this.pythonCommand = pythonCommand;
		this.port = port;
	}

	public static MinescriptConfig loadOrCreate() {
		Path configDir = FabricLoader.getInstance().getConfigDir();
		Path rootDir = configDir.resolve("minecraft");
		Path scriptsDir = rootDir.resolve("scripts");
		Path pythonDir = rootDir.resolve("python");
		Path logsDir = rootDir.resolve("logs");
		Path configFile = configDir.resolve("minescript.json");

		try {
			Files.createDirectories(scriptsDir);
			Files.createDirectories(pythonDir);
			Files.createDirectories(logsDir);
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to create Minescript directories", exception);
		}

		String defaultPython = defaultPythonCommand();
		int port = DEFAULT_PORT;
		String pythonCommand = defaultPython;

		if (Files.exists(configFile)) {
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
			} catch (Exception exception) {
				MinescriptClient.LOGGER.warn("Failed to read minescript config, rewriting defaults", exception);
			}
		}

		MinescriptConfig config = new MinescriptConfig(rootDir, scriptsDir, pythonDir, logsDir, configFile, pythonCommand, port);
		config.writeConfigFile();
		return config;
	}

	public void ensureSupportFiles() {
		writeTextIfChanged(this.pythonDir.resolve("minescript.py"), pythonModuleSource());
		writeTextIfChanged(this.scriptsDir.resolve("example.py"), exampleScriptSource());
		writeTextIfChanged(this.scriptsDir.resolve("test.py"), testScriptSource());
	}

	public Path rootDir() {
		return this.rootDir;
	}

	public Path scriptsDir() {
		return this.scriptsDir;
	}

	public Path pythonDir() {
		return this.pythonDir;
	}

	public Path logsDir() {
		return this.logsDir;
	}

	public String pythonCommand() {
		return this.pythonCommand;
	}

	public int port() {
		return this.port;
	}

	private void writeConfigFile() {
		JsonObject json = new JsonObject();
		json.addProperty("python_command", this.pythonCommand);
		json.addProperty("port", this.port);
		writeTextIfChanged(this.configFile, GSON.toJson(json) + System.lineSeparator());
	}

	private void writeTextIfChanged(Path path, String content) {
		try {
			String existing = Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
			if (content.equals(existing)) {
				return;
			}

			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(
				path,
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE
			)) {
				writer.write(content);
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to write support file: " + path, exception);
		}
	}

	private static String defaultPythonCommand() {
		String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		return osName.contains("win") ? "py" : "python3";
	}

	private String pythonModuleSource() {
		return """
import json
import os
import atexit
import threading
import time
import traceback
import urllib.error
import urllib.request

_PORT = int(os.environ.get(\"MINESCRIPT_PORT\", \"%d\"))
_BASE_URL = f\"http://127.0.0.1:{_PORT}/invoke\"
_CONTROL_PREFIX = os.environ.get(\"MINESCRIPT_CONTROL_PREFIX\", \"__MINESCRIPT_CONTROL__:\")
_EVENT_HANDLERS = {\"chat\": [], \"tick\": [], \"join_world\": []}
_EVENT_THREAD = None
_STOP_EVENTS = threading.Event()


def _invoke(method, **kwargs):
	payload = json.dumps({\"method\": method, \"args\": kwargs}).encode(\"utf-8\")
	request = urllib.request.Request(
		_BASE_URL,
		data=payload,
		headers={\"Content-Type\": \"application/json\"},
		method=\"POST\",
	)

	try:
		with urllib.request.urlopen(request, timeout=5) as response:
			data = json.loads(response.read().decode(\"utf-8\"))
	except urllib.error.URLError as exc:
		raise RuntimeError(f\"Minescript bridge is unavailable: {exc}\") from exc

	if not data.get(\"ok\", False):
		raise RuntimeError(data.get(\"error\", \"Unknown Minescript error\"))

	return data.get(\"result\")


def _emit_control(payload):
	print(f\"{_CONTROL_PREFIX}{json.dumps(payload, separators=(\",\", \":\"))}\", flush=True)


def _set_chat_output(enabled):
	_emit_control({\"command\": \"set_chat_output\", \"enabled\": bool(enabled)})


def getchat(limit=20):
	return _invoke(\"getchat\", limit=limit)


def sendchat(message):
	return _invoke(\"sendchat\", message=str(message))


def getplayerpos():
	return _invoke(\"getplayerpos\")


def lookat(x, y, z):
	return _invoke(\"lookat\", x=float(x), y=float(y), z=float(z))


def rightclick():
	return _invoke(\"rightclick\")


def leftclick():
	return _invoke(\"leftclick\")


def jump():
	return _invoke(\"jump\")


def sneak(state=True):
	return _invoke(\"sneak\", state=bool(state))


def sprint(state=True):
	return _invoke(\"sprint\", state=bool(state))


def getobjectatinventorryslot(slot):
	return _invoke(\"getobjectatinventorryslot\", slot=slot)


def getinventory():
	return _invoke(\"getinventory\")


def quickmoveslot(slot):
	return _invoke(\"quickmoveslot\", slot=slot)


def dropslot(slot):
	return _invoke(\"dropslot\", slot=slot)


def swapslots(slot_a, slot_b):
	return _invoke(\"swapslots\", slot_a=slot_a, slot_b=slot_b)


def getselectedhotbarslot():
	return _invoke(\"getselectedhotbarslot\")


def selecthotbarslot(slot):
	return _invoke(\"selecthotbarslot\", slot=slot)


def getleaderboard():
	return _invoke(\"getleaderboard\")


def getleaaderboard():
	return getleaderboard()


def clickslot(slot, button=0, action_type=\"PICKUP\"):
	return _invoke(\"clickslot\", slot=slot, button=button, action_type=action_type)


def gettargetblock():
	return _invoke(\"gettargetblock\")


def gettargetentity():
	return _invoke(\"gettargetentity\")


def gethealth():
	return _invoke(\"gethealth\")


def gethunger():
	return _invoke(\"gethunger\")


def getarmor():
	return _invoke(\"getarmor\")


def getdimension():
	return _invoke(\"getdimension\")


def getbiome():
	return _invoke(\"getbiome\")


def getnearbyentities(radius=16.0):
	return _invoke(\"getnearbyentities\", radius=float(radius))


def log(message):
	_emit_control({\"command\": \"log\", \"level\": \"INFO\", \"message\": str(message)})


def error(message):
	_emit_control({\"command\": \"log\", \"level\": \"ERROR\", \"message\": str(message)})


def disablelog():
	_set_chat_output(False)


def enablelog():
	_set_chat_output(True)


def _ensure_event_thread():
	global _EVENT_THREAD
	if _EVENT_THREAD is not None and _EVENT_THREAD.is_alive():
		return

	_STOP_EVENTS.clear()
	_EVENT_THREAD = threading.Thread(target=_event_loop, name=\"minescript-events\", daemon=True)
	_EVENT_THREAD.start()


def _register_event(event_type, handler):
	_EVENT_HANDLERS.setdefault(event_type, []).append(handler)
	_ensure_event_thread()
	return handler


def on_chat(handler):
	return _register_event(\"chat\", handler)


def on_tick(handler):
	return _register_event(\"tick\", handler)


def on_join_world(handler):
	return _register_event(\"join_world\", handler)


def _event_loop():
	while not _STOP_EVENTS.is_set():
		try:
			events = _invoke(\"pollevents\", types=list(_EVENT_HANDLERS.keys()), limit=100)
		except Exception as exc:
			error(f\"event polling failed: {exc}\")
			traceback.print_exc()
			time.sleep(1.0)
			continue

		for event in events:
			event_type = event.get(\"type\")
			for handler in list(_EVENT_HANDLERS.get(event_type, [])):
				try:
					handler(event)
				except Exception as exc:
					error(f\"{event_type} handler failed: {exc}\")
					traceback.print_exc()

		time.sleep(0.05)


def stop_events():
	_STOP_EVENTS.set()


def wait_forever(interval=0.1):
	while True:
		time.sleep(interval)


atexit.register(stop_events)

""".formatted(this.port);
	}

	private static String exampleScriptSource() {
		return """
import minescript

minescript.log("player position:")
print(minescript.getplayerpos())

minescript.log("target block:")
print(minescript.gettargetblock())

@minescript.on_chat
def handle_chat(event):
	if "hello" in event["message"].lower():
		minescript.sendchat("Hello from Minescript")

# minescript.wait_forever()
""";
	}

	private static String testScriptSource() {
		return """
import time

import minescript


DELAY_SECONDS = 2


def wait_step():
	time.sleep(DELAY_SECONDS)


def run_step(name, action):
	minescript.log(f"running {name}")
	try:
		result = action()
		print(f"{name}: {result}")
	except Exception as exc:
		minescript.error(f"{name} failed: {exc}")
		print(f"{name} failed: {exc}")
	finally:
		wait_step()


@minescript.on_chat
def handle_chat(event):
	if not getattr(handle_chat, "done", False):
		print(f"chat event: {event}")
		handle_chat.done = True


@minescript.on_tick
def handle_tick(event):
	if not getattr(handle_tick, "done", False):
		print(f"tick event: {event}")
		handle_tick.done = True


@minescript.on_join_world
def handle_join(event):
	if not getattr(handle_join, "done", False):
		print(f"join_world event: {event}")
		handle_join.done = True


run_step("sendchat", lambda: minescript.sendchat("Minescript test.py starting"))
run_step("getchat", lambda: minescript.getchat())
run_step("getplayerpos", lambda: minescript.getplayerpos())

player_pos = minescript.getplayerpos()
run_step("lookat", lambda: minescript.lookat(player_pos["x"] + 1.0, player_pos["y"], player_pos["z"] + 1.0))
run_step("rightclick", lambda: minescript.rightclick())
run_step("leftclick", lambda: minescript.leftclick())
run_step("jump", lambda: minescript.jump())
run_step("sneak_on", lambda: minescript.sneak(True))
run_step("sneak_off", lambda: minescript.sneak(False))
run_step("sprint_on", lambda: minescript.sprint(True))
run_step("sprint_off", lambda: minescript.sprint(False))
run_step("getobjectatinventorryslot", lambda: minescript.getobjectatinventorryslot(0))
run_step("getinventory", lambda: minescript.getinventory())
run_step("quickmoveslot", lambda: minescript.quickmoveslot(0))
run_step("dropslot", lambda: minescript.dropslot(0))
run_step("swapslots", lambda: minescript.swapslots(0, 1))
run_step("getselectedhotbarslot", lambda: minescript.getselectedhotbarslot())
run_step("selecthotbarslot", lambda: minescript.selecthotbarslot(0))
run_step("getleaderboard", lambda: minescript.getleaderboard())
run_step("gettargetblock", lambda: minescript.gettargetblock())
run_step("gettargetentity", lambda: minescript.gettargetentity())
run_step("gethealth", lambda: minescript.gethealth())
run_step("gethunger", lambda: minescript.gethunger())
run_step("getarmor", lambda: minescript.getarmor())
run_step("getdimension", lambda: minescript.getdimension())
run_step("getbiome", lambda: minescript.getbiome())
run_step("getnearbyentities", lambda: minescript.getnearbyentities(16.0))
run_step("log", lambda: minescript.log("test.py log message"))
run_step("error", lambda: minescript.error("test.py error message"))
run_step("disablelog", lambda: minescript.disablelog())
run_step("enablelog", lambda: minescript.enablelog())
run_step("sendchat_hook_trigger", lambda: minescript.sendchat("hook test message from test.py"))

print("waiting briefly for hook callbacks")
time.sleep(4)

minescript.sendchat("Minescript test.py finished")
print("done")
""";
	}
}


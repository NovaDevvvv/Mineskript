# Minescript

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-3C8527?style=for-the-badge)
![Fabric](https://img.shields.io/badge/Loader-Fabric-DBD0B4?style=for-the-badge)
![Python](https://img.shields.io/badge/Python-System%20Install-3776AB?style=for-the-badge)
![Side](https://img.shields.io/badge/Side-Client-1F6FEB?style=for-the-badge)

A client-side Fabric mod that lets Python scripts control and inspect the Minecraft client through `import minescript`.

It creates a script workspace inside `.minecraft/config/minecraft/scripts`, generates a Python helper module at `.minecraft/config/minecraft/python/minescript.py`, runs scripts from in-game commands, mirrors output into chat and log files, and exposes a growing set of client-side automation hooks.

## Highlights

- Run Python files directly from Minecraft with `/ms run <file>`.
- Put scripts in subfolders under `config/minecraft/scripts`.
- Use filename autocorrect when script names are close but not exact.
- Read chat, inventory, scoreboard, player state, world state, and nearby entities.
- Perform client-side actions such as clicking, looking, jumping, sprinting, sneaking, hotbar selection, and sending chat.
- Register Python event hooks for chat, tick, and join-world events.
- Mirror script output into chat with color-coded levels and also write it to rotating log files.

## Quick Start

1. Start the client once with the mod installed.
2. Open `.minecraft/config/minecraft/scripts`.
3. Create a Python file or use the generated `example.py` and `test.py`.
4. In-game, run:

```text
/ms run example.py
```

You can also run a script without the `.py` extension. If the file name is close enough, Minescript will autocorrect it.

Examples:

```text
/ms run test
/ms run exampel
/ms run tools/invento
```

## Runtime Layout

After first launch, the mod creates:

```text
.minecraft/
  config/
    minescript.json
    minecraft/
      logs/
        minescript.log
        minescript.log.1
        minescript.log.2
      python/
        minescript.py
      scripts/
        example.py
        test.py
        your_scripts_here.py
        subfolders_supported/
```

## Commands

### Run one script

```text
/minescript run your_script.py
/ms run your_script.py
```

Behavior:

- Runs a single script through your configured Python executable.
- Supports subfolder paths.
- Supports fuzzy filename autocorrect.
- Shows start, finish, and failure toasts.

### Show scripts folder

```text
/minescript folder
/ms folder
```

### Reload support files and run all scripts

```text
/minescript reload
/ms reload
```

Behavior:

- Regenerates `minescript.py`, `example.py`, and `test.py`.
- Recursively finds every `.py` file under `config/minecraft/scripts`.
- Runs them in filename order.

## Python Setup

Minescript launches your system Python.

Defaults:

- Windows: `py`
- Non-Windows: `python3`

You can change this in `config/minescript.json`:

```json
{
  "python_command": "py",
  "port": 47641
}
```

## Minimal Example

```python
import minescript

minescript.sendchat("Hello from Python")
print(minescript.getplayerpos())
print(minescript.getinventory())
```

## Logging and Output

By default:

- `print(...)` output is shown in Minecraft chat.
- Script messages are written to `config/minecraft/logs/minescript.log`.
- Log lines are timestamped.
- Chat messages use color-coded levels.

### Chat log control

```python
minescript.disablelog()
minescript.enablelog()
```

`disablelog()` stops future script output from being mirrored into chat, but logging still continues to the log file.

### Explicit levels

```python
minescript.log("This is an info line")
minescript.error("This is an error line")
```

### Log rotation

Minescript rotates log files automatically when the main log grows too large.

## Event Hooks

The generated `minescript.py` includes simple Python decorators for client events.

```python
import minescript

@minescript.on_chat
def handle_chat(event):
    print(event["message"])

@minescript.on_tick
def handle_tick(event):
    pass

@minescript.on_join_world
def handle_join(event):
    minescript.sendchat("joined world")

minescript.wait_forever()
```

Available event types:

- `chat`
- `tick`
- `join_world`

Important:

- Event hooks only run while the script is still alive.
- Use `minescript.wait_forever()` or your own loop if the script should keep listening.
- Internal `[ms:...]` messages are filtered out so the mod does not recursively trigger its own chat hooks.

## API Reference

### Chat and messaging

| Function | Description |
| --- | --- |
| `sendchat(message)` | Sends a chat message. If the message starts with `/`, it is sent as a command. |
| `getchat(limit=20)` | Returns recent chat lines captured by the mod. |
| `log(message)` | Emits an info-level Minescript log line. |
| `error(message)` | Emits an error-level Minescript log line. |
| `disablelog()` | Stops chat mirroring for later script output. |
| `enablelog()` | Re-enables chat mirroring. |

### Player state and movement

| Function | Description |
| --- | --- |
| `getplayerpos()` | Returns `x`, `y`, `z`, `yaw`, `pitch`, and block coordinates. |
| `lookat(x, y, z)` | Rotates the player view toward a world position. |
| `jump()` | Makes the player jump. |
| `sneak(state=True)` | Toggles sneaking. |
| `sprint(state=True)` | Toggles sprinting. |

### Click and interaction helpers

| Function | Description |
| --- | --- |
| `rightclick()` | Attempts a normal right-click interaction. |
| `leftclick()` | Attempts a normal left-click attack or block hit. |
| `clickslot(slot, button=0, action_type="PICKUP")` | Clicks a slot in the current screen handler. |
| `quickmoveslot(slot)` | Shift-click style slot move. |
| `dropslot(slot)` | Throws the item from a slot. |
| `swapslots(slot_a, slot_b)` | Performs a simple three-click swap between two slots. |

### Inventory and hotbar

| Function | Description |
| --- | --- |
| `getobjectatinventorryslot(slot)` | Returns the item data for one slot. |
| `getinventory()` | Returns every visible slot in the current screen handler. |
| `getselectedhotbarslot()` | Returns the selected hotbar slot index. |
| `selecthotbarslot(slot)` | Sets the selected hotbar slot. |

### Scoreboard and world info

| Function | Description |
| --- | --- |
| `getleaderboard()` | Returns the sidebar scoreboard title and entries. |
| `getleaaderboard()` | Alias for the misspelled name you originally requested. |
| `gettargetblock()` | Returns block information under the crosshair, or `None`. |
| `gettargetentity()` | Returns entity information under the crosshair, or `None`. |
| `gethealth()` | Returns current and max health. |
| `gethunger()` | Returns food and saturation. |
| `getarmor()` | Returns armor points. |
| `getdimension()` | Returns the current dimension id. |
| `getbiome()` | Returns the biome id at the player position. |
| `getnearbyentities(radius=16.0)` | Returns nearby entity data within a radius. |

### Event helpers

| Function | Description |
| --- | --- |
| `on_chat(handler)` | Registers a chat event handler. |
| `on_tick(handler)` | Registers a tick event handler. |
| `on_join_world(handler)` | Registers a world-join event handler. |
| `wait_forever(interval=0.1)` | Keeps the script alive for hooks. |
| `stop_events()` | Stops the event polling loop. |

## Return Shapes

The API returns plain Python dictionaries and lists converted from JSON. Common examples:

### `getplayerpos()`

```python
{
    "x": 120.5,
    "y": 64.0,
    "z": -31.5,
    "yaw": 180.0,
    "pitch": 12.0,
    "block_x": 120,
    "block_y": 64,
    "block_z": -31,
}
```

### `getobjectatinventorryslot(0)`

```python
{
    "slot": 0,
    "empty": False,
    "count": 64,
    "name": "Stone",
    "item_id": "minecraft:stone",
    "sync_id": 0,
    "slot_count": 46,
}
```

### `getleaderboard()`

```python
{
    "title": "Bedwars",
    "entries": [
        {"name": "PlayerA", "score": 10},
        {"name": "PlayerB", "score": 8},
    ],
}
```

## File Autocorrect

Minescript now autocorrects script file names when you run them from command.

Supported corrections include:

- missing `.py` extension
- case differences
- minor misspellings
- close filename matches in subfolders

Examples:

```text
/ms run test
/ms run tset.py
/ms run util/inventroy
```

If Minescript finds a strong enough match, it runs that script and tells you what it corrected.

## Generated Example Scripts

### `example.py`

A small example that reads state and shows the basic module style.

### `test.py`

A broader smoke-test script that walks through many available actions with a delay between steps.

Important:

- some actions mutate inventory state
- some actions can move the player or click the world
- use it in a safe environment before relying on it on a real server

## Safety Notes

- This is a client-side tool, not a server plugin.
- Methods only work while the relevant Minecraft client state exists.
- Inventory and click helpers operate on the current open screen handler.
- `gettargetblock()` and `gettargetentity()` depend on the current crosshair target.
- Automation behavior may still be restricted by the server you are connected to.

## Troubleshooting

### Python script exits immediately

Possible causes:

- Python is not installed or not available through the configured command.
- You ran the script directly outside Minecraft, so the local bridge server was not available.

Check:

- `config/minescript.json`
- `config/minecraft/logs/minescript.log`

### `import minescript` fails

Start Minecraft with the mod once so it generates the helper module into `config/minecraft/python/minescript.py`.

### Script not found

Try:

- `/ms folder`
- using the relative path from the scripts folder
- relying on autocorrect with the closest reasonable name

### Chat spam or loops

Minescript filters its own internal `[ms:...]` output from event capture, but your script can still generate noisy loops if it reacts to every chat message and then emits more chat. Use guards in handlers and prefer one-shot output for testing.

## Build and Development

Project target:

- Minecraft `1.21.1`
- Fabric Loader `0.19.1`
- Fabric API `0.116.10+1.21.1`

Build:

```text
./gradlew build
```

Run the client:

```text
./gradlew runclient
```

## Notes

- This project uses Minecraft `1.21.1` because Fabric does not publish a `1.21.11` target.
- The Python bridge runs locally over `127.0.0.1`.
- The helper module is generated at runtime, so edits to the Java generator become visible after support files are regenerated.

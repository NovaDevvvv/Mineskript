# Minescript API

```py
from minescript import game, data
```

<hr>

1) `game.sendchat(message : string)`

Sends text into Minecraft chat. A message beginning with `/` runs as a command.

> Input
```py
game.sendchat(input("message > "))
```

> Output
```
{"sent": true, "message": "Hello, world!"}
```

<hr>

2) `game.getchat(limit : int = 20)`

Returns recently captured chat messages.

> Input
```py
messages = game.getchat(10)
```

> Output
```py
["Hello, world!", "Another chat message"]
```

<hr>

3) `game.getplayerposition()`

Returns a `data.Position` with `x`, `y`, `z`, `yaw`, and `pitch`.

> Input
```py
position = game.getplayerposition()
print(position.x, position.y, position.z)
```

> Output
```
120.5 64.0 -31.5
```

<hr>

4) `game.lookat(x : float, y : float, z : float)`

Turns the local player toward a world position.

> Input
```py
game.lookat(0, 64, 0)
```

> Output
```
{"yaw": 135.0, "pitch": 12.0}
```

<hr>

5) `game.rightclick()` / `game.leftclick()` / `game.jump()`

Uses, attacks, or jumps once.

> Input
```py
game.rightclick()
game.leftclick()
game.jump()
```

> Output
```
{"used": true}
{"attacked": true}
{"jumped": true}
```

<hr>

6) `game.moveforward(pressed : bool = True)`, `game.moveback(pressed : bool = True)`, `game.moveleft(pressed : bool = True)`, `game.moveright(pressed : bool = True)`

Presses or releases one scripted movement key.

> Input
```py
game.moveforward()
game.moveforward(False)
```

> Output
```
{"direction": "forward", "pressed": true}
{"direction": "forward", "pressed": false}
```

<hr>

7) `game.stopmoving()`, `game.setsneaking(enabled : bool = True)`, `game.setsprinting(enabled : bool = True)`

Stops movement or changes sneaking and sprinting state.

> Input
```py
game.stopmoving()
game.setsneaking(True)
game.setsprinting(False)
```

> Output
```
{"moving": false}
{"sneaking": true}
{"sprinting": false}
```

<hr>

8) `game.getinventoryslot(slot_index : int)` / `game.getinventory()`

Returns a `data.InventorySlot`, or every visible `data.InventorySlot`.

> Input
```py
slot = game.getinventoryslot(0)
print(slot.name, slot.count)
```

> Output
```
Stone 64
```

<hr>

9) `game.quickmoveslot(slot_index : int)`, `game.dropslot(slot_index : int)`, `game.swapslots(first_slot : int, second_slot : int)`, `game.clickslot(slot_index : int, button : int = 0, action_type : string = "PICKUP")`

Moves, drops, swaps, or clicks inventory slots.

> Input
```py
game.quickmoveslot(0)
game.swapslots(0, 1)
game.clickslot(2, action_type="QUICK_MOVE")
```

> Output
```
{"clicked": true, "slot": 2, "action_type": "QUICK_MOVE"}
```

<hr>

10) `game.getselectedhotbarslot()` / `game.selecthotbarslot(slot_index : int)`

Gets or changes the selected hotbar slot.

> Input
```py
game.selecthotbarslot(4)
print(game.getselectedhotbarslot())
```

> Output
```
4
```

<hr>

11) `game.gettargetblock()`

Returns a `data.Block` under the crosshair, or `None`.

> Input
```py
block = game.gettargetblock()
if block:
    print(block.block_id, block.x, block.y, block.z)
```

> Output
```
minecraft:stone 120 64 -32
```

<hr>

12) `game.gettargetentity()` / `game.getnearbyentities(radius : float = 16.0)` / `game.getnearbyplayers(radius : float = 16.0)`

Returns `data.Entity` and `data.Player` objects.

> Input
```py
for entity in game.getnearbyentities(8):
    print(entity.name, entity.entity_type)
```

> Output
```
Zombie minecraft:zombie
NovaB minecraft:player
```

<hr>

13) `game.getnbt(entity : data.Entity | data.Player | int)`

Returns the SNBT string for an entity object or entity id.

> Input
```py
entity = game.gettargetentity()
if entity:
    print(game.getnbt(entity))
```

> Output
```
{id: "minecraft:zombie", Health: 20.0f, ...}
```

<hr>

14) `game.gethealth()` / `game.gethunger()` / `game.getarmor()`

Return `data.Health`, `data.Hunger`, and `data.Armor`.

> Input
```py
print(game.gethealth().health)
print(game.gethunger().food)
print(game.getarmor().armor)
```

> Output
```
20.0
20
0
```

<hr>

15) `game.getdimension()` / `game.getbiome()`

Returns the current dimension or biome identifier.

> Input
```py
print(game.getdimension(), game.getbiome())
```

> Output
```
minecraft:overworld minecraft:plains
```

<hr>

16) `game.getleaderboard()`

Returns a `data.Leaderboard` with `title` and `data.LeaderboardEntry` values.

> Input
```py
board = game.getleaderboard()
for entry in board.entries:
    print(entry.name, entry.score)
```

> Output
```
PlayerA 10
```

<hr>

17) `game.runjava(source : string)`

Compiles and runs a Java method body on the client thread. `client`, `player`, and Gson JSON classes are available.

> Input
```py
health = game.runjava("return new JsonPrimitive(player.getHealth());")
```

> Output
```
20.0
```

<hr>

18) `game.loginfo(message : string)` / `game.logerror(message : string)` / `game.disablechatoutput()` / `game.enablechatoutput()`

Writes script log messages and controls their chat output.

> Input
```py
game.loginfo("Started")
game.logerror("Failed")
game.disablechatoutput()
```

> Output
```
[ms:script] Started
[ms:script] Failed
```

<hr>

19) `game.onchat(handler)`, `game.ontick(handler)`, `game.onjoinworld(handler)`

Registers an event handler.

> Input
```py
@game.onchat
def on_chat(event):
    print(event["message"])

game.waitforever()
```

> Output
```
Hello from chat
```

<hr>

20) `game.stopevents()` / `game.waitforever(interval : float = 0.1)`

Stops event polling or keeps a script alive for event handlers.

> Input
```py
game.stopevents()
```

> Output
```
Event polling stopped
```

<hr>

21) `game.showactionbar(message : string)` / `game.showtitle(title : string, subtitle : string | None = None)`

Shows a transient action bar message or a title. Title timing is configurable with `fade_in`, `stay`, and `fade_out` in ticks.

> Input
```py
game.showactionbar("Mining started")
game.showtitle("New objective", "Find the ancient city", stay=100)
```

> Output
```
{"shown": true, "type": "action_bar"}
{"shown": true, "type": "title"}
```

<hr>

22) `game.createtoast(...)` / `game.showtoast(toast)`

Creates and shows a toast notification. Its `icon` is any Minecraft item identifier.

> Input
```py
toast = game.createtoast("Rare find", "Diamond added to inventory", icon="minecraft:diamond")
game.showtoast(toast)
```

> Output
```
{"shown": true, "type": "toast"}
```

<hr>

23) `game.createinventory(...)` / `game.showinventory(inventory)`

Creates a local chest-style inventory screen. Use `data.InventoryType.CHEST_1` through `data.InventoryType.CHEST_6` to choose its row count, then place items by zero-based slot.

> Input
```py
inventory = game.createinventory("Quest rewards", data.InventoryType.CHEST_3)
inventory.set_item(0, "minecraft:diamond", 3)
inventory.set_item(4, "minecraft:nether_star")
game.showinventory(inventory)
```

> Output
```
{"shown": true, "rows": 3, "size": 27}
```

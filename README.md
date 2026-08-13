# Minescript API

```python
from minescript import game, data
```

`data.Position`, `data.Block`, `data.Entity`, `data.Player`, `data.InventorySlot`, `data.Health`, `data.Hunger`, `data.Armor`, `data.Leaderboard`, and `data.LeaderboardEntry` are the objects returned by `game` query functions. Their properties are available directly, for example `game.getplayerposition().x` and `game.gettargetblock().block_id`.

## Chat

### `game.sendchat(message)`
Sends chat text or a command.
```python
game.sendchat(input("message > "))
```

### `game.getchat(limit=20)`
Returns recent captured chat messages.
```python
messages = game.getchat(10)
```

### `game.loginfo(message)`
Writes an info log line.
```python
game.loginfo("Script started")
```

### `game.logerror(message)`
Writes an error log line.
```python
game.logerror("Something failed")
```

### `game.disablechatoutput()` / `game.enablechatoutput()`
Controls whether script output is mirrored to chat.
```python
game.disablechatoutput()
game.enablechatoutput()
```

## Player And Input

### `game.getplayerposition()`
Returns a `data.Position`.
```python
position = game.getplayerposition()
print(position.x, position.y, position.z)
```

### `game.lookat(x, y, z)`
Turns toward a world position.
```python
game.lookat(0, 64, 0)
```

### `game.jump()`
Makes the player jump.
```python
game.jump()
```

### `game.moveforward(pressed=True)`, `game.moveback(pressed=True)`, `game.moveleft(pressed=True)`, `game.moveright(pressed=True)`
Presses or releases a movement key.
```python
game.moveforward()
game.moveforward(False)
```

### `game.stopmoving()`
Releases scripted movement keys.
```python
game.stopmoving()
```

### `game.setsneaking(enabled=True)` / `game.setsprinting(enabled=True)`
Changes sneaking or sprinting state.
```python
game.setsneaking(True)
game.setsprinting(False)
```

### `game.gethealth()`, `game.gethunger()`, `game.getarmor()`
Return `data.Health`, `data.Hunger`, and `data.Armor`.
```python
print(game.gethealth().health)
print(game.gethunger().food)
print(game.getarmor().armor)
```

### `game.getdimension()` / `game.getbiome()`
Return the current dimension or biome id.
```python
print(game.getdimension(), game.getbiome())
```

## World Data

### `game.gettargetblock()`
Returns a `data.Block`, or `None`.
```python
block = game.gettargetblock()
if block:
    print(block.block_id, block.x, block.y, block.z)
```

### `game.gettargetentity()`
Returns a `data.Entity` or `data.Player`, or `None`.
```python
entity = game.gettargetentity()
if entity:
    print(entity.name, entity.entity_type)
```

### `game.getnbt(entity)`
Returns the SNBT string for a `data.Entity` or `data.Player`.
```python
entity = game.gettargetentity()
if entity:
    print(game.getnbt(entity))
```

### `game.getnearbyentities(radius=16.0)`
Returns `data.Entity` and `data.Player` objects.
```python
for entity in game.getnearbyentities(8):
    print(entity.name)
```

### `game.getnearbyplayers(radius=16.0)`
Returns nearby `data.Player` objects.
```python
for player in game.getnearbyplayers():
    print(player.player_name, player.facing)
```

## Inventory

### `game.getinventoryslot(slot_index)`
Returns one `data.InventorySlot`.
```python
slot = game.getinventoryslot(0)
print(slot.item_id, slot.count)
```

### `game.getinventory()`
Returns visible `data.InventorySlot` objects.
```python
for slot in game.getinventory():
    print(slot.slot, slot.name)
```

### `game.quickmoveslot(slot_index)`, `game.dropslot(slot_index)`, `game.swapslots(first_slot, second_slot)`
Moves, drops, or swaps inventory items.
```python
game.quickmoveslot(0)
game.swapslots(0, 1)
```

### `game.getselectedhotbarslot()` / `game.selecthotbarslot(slot_index)`
Gets or selects a hotbar index.
```python
game.selecthotbarslot(4)
print(game.getselectedhotbarslot())
```

### `game.clickslot(slot_index, button=0, action_type="PICKUP")`
Performs a Minecraft slot action.
```python
game.clickslot(0, action_type="QUICK_MOVE")
```

## Scoreboard And Java

### `game.getleaderboard()`
Returns a `data.Leaderboard`.
```python
board = game.getleaderboard()
for entry in board.entries:
    print(entry.name, entry.score)
```

### `game.runjava(source)`
Runs a Java method body on the client thread. `client`, `player`, and Gson JSON classes are available.
```python
health = game.runjava("return new JsonPrimitive(player.getHealth());")
```

## Events

### `game.onchat(handler)`, `game.ontick(handler)`, `game.onjoinworld(handler)`
Register a chat, tick, or join-world handler.
```python
@game.onchat
def on_chat(event):
    print(event["message"])
```

### `game.stopevents()` / `game.waitforever(interval=0.1)`
Stops event polling or keeps the script alive for handlers.
```python
game.waitforever()
```

## Interaction

### `game.rightclick()` / `game.leftclick()`
Uses or attacks at the crosshair.
```python
game.rightclick()
game.leftclick()
```

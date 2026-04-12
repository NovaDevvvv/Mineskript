# Suggestions

## High-value features

- Add `minescript.sendchat(message)` so Python scripts can send chat messages directly.
- Add `minescript.getplayerpos()` to return the player's exact position, yaw, and pitch.
- Add `minescript.lookat(x, y, z)` to rotate the player toward a target point.
- Add `minescript.rightclick()` and `minescript.leftclick()` for simple scripted interaction.
- Add `minescript.jump()`, `minescript.sneak(state)`, and `minescript.sprint(state)` for movement control.

## Inventory and container improvements

- Add `minescript.getinventory()` to return every visible slot at once instead of one slot at a time.
- Add `minescript.quickmoveslot(slot)` to shift-click items in containers.
- Add `minescript.dropslot(slot)` to throw an item from a slot.
- Add `minescript.swapslots(slot_a, slot_b)` for simple inventory rearranging.
- Add `minescript.getselectedhotbarslot()` and `minescript.selecthotbarslot(slot)`.

## World information

- Add `minescript.gettargetblock()` to return the block the crosshair is pointing at.
- Add `minescript.gettargetentity()` to return the entity under the crosshair.
- Add `minescript.gethealth()`, `minescript.gethunger()`, and `minescript.getarmor()`.
- Add `minescript.getdimension()` and `minescript.getbiome()`.
- Add `minescript.getnearbyentities(radius)` for simple automation and alerts.

## Chat and logging

- Add colored chat output levels such as info, warning, and error.
- Add `minescript.log(message)` and `minescript.error(message)` helpers instead of relying on plain `print`.
- Add a rolling log file under `config/minecraft/logs` for script output.
- Add a toggle to show script output only in the log and not in chat.
- Add timestamps to script output lines.

## Nice-to-have ideas

- Add toast notifications when scripts start, finish, or fail.
- Add simple event hooks like `on_chat`, `on_tick`, or `on_join_world`.
- Add support for subfolders inside `config/minecraft/scripts`.
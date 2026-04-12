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

## Nearby player tracking

- Add `minescript.getnearbyplayers(radius)` with held-item, facing, and movement-state data.
- Add `minescript.getplayerbyname(name)` for a direct lookup without scanning a whole radius.
- Add armor inspection for nearby players, including helmet, chestplate, leggings, and boots item ids.
- Add active-effect data for nearby players such as speed, invisibility, or regeneration.
- Add ping and gamemode when the client has that information.
- Add team, scoreboard prefix, and nametag color data for nearby players.

## Movement and pathing

- Add timed helpers like `walkforward(seconds)` and `straferight(seconds)` so scripts do not need manual sleep-plus-release logic.
- Add `minescript.stopallinputs()` to release movement, sneak, sprint, and mouse buttons together.
- Add `minescript.faceentity(entity_uuid)` and `minescript.faceplayer(name)` helpers.
- Add a simple `moveto(x, y, z)` helper for straight-line walking in open areas.
- Add `minescript.ispathclear(x, y, z)` for lightweight movement checks.

## Screen and UI automation

- Add `minescript.getopenscreen()` to identify the current GUI or container type.
- Add `minescript.closescreen()` to dismiss containers and menus.
- Add helpers to read container titles, chest row counts, and villager trade screens.
- Add click helpers for hotbar swap, drag-split, and double-click collect.
- Add `minescript.gettooltipat(slot)` for richer item inspection.

## World scanning and automation

- Add `minescript.getblocksaround(radius)` for a small nearby block snapshot.
- Add `minescript.findblock(block_id, radius)` to locate common blocks near the player.
- Add `minescript.raycast(distance)` for generic hit detection instead of separate block and entity methods.
- Add light-level and sky-light queries for farming or mob-spawn scripts.
- Add simple chest or sign text reading when targeted.

## Script runtime improvements

- Add per-script config files stored next to each Python script.
- Add delayed scheduling helpers like `run_later(seconds, fn)` and repeating timers.
- Add a script list command that shows discovered files and the autocorrect target it would choose.
- Add a safe mode to block dangerous actions such as inventory clicks unless explicitly enabled.
- Add optional JSON log output for scripts that want machine-readable records.
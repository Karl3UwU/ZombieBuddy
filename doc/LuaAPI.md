# KRL ZomBud Lua API Reference

This document covers the Lua APIs exposed by KRL ZomBud for inspecting game events, watching method calls, and querying Java mod status.

For dev/debug utilities (`zbinspect`, `zbmethods`, `zbgrep`, `zbmap`, etc.), see [DevDebugFunctions.md](DevDebugFunctions.md).

---

## KRLZomBud.Events

Inspect game event hooks registered by Lua code.

| Method | Description |
|--------|-------------|
| `getAll()` | Returns a table mapping event names to their callback lists. |
| `getByName(eventName)` | Returns the list of callbacks for the given event (e.g. `"OnCreatePlayer"`). |
| `getByFile(filename)` | Returns a table of events that have callbacks from the given Lua file. Each event name maps to a 1-based array of those callbacks. |
| `EventName` (index) | Access event callbacks by name: `KRLZomBud.Events.OnCreatePlayer` returns the same as `getByName("OnCreatePlayer")`. |

### Related helpers on KRLZomBud

| Method | Description |
|--------|-------------|
| `getClosureFilename(closure)` | Returns the source filename for a callback (LuaClosure). |
| `getClosureInfo(closure)` | Returns a table with `file`, `filename`, `name`, and `line` for a callback. |

### Examples

```lua
-- Get all events
local all = KRLZomBud.Events.getAll()

-- Get callbacks for a specific event
local callbacks = KRLZomBud.Events.getByName("OnCreatePlayer")
-- Or equivalently (via __index):
local callbacks = KRLZomBud.Events.OnCreatePlayer

-- Get events/callbacks from a specific file
local byFile = KRLZomBud.Events.getByFile("/path/to/SomeMod.lua")
-- byFile.OnFillWorldObjectContextMenu[1] is the first callback from that file

-- Inspect a callback's source
local info = KRLZomBud.getClosureInfo(callbacks[1])
print(info.filename, info.line)  -- e.g. "media/lua/client/SomeMod.lua", 42
```

---

## KRLZomBud.Watches (Experimental)

Hook any Java method and log its calls and arguments.

| Method | Description |
|--------|-------------|
| `Add(className, methodName)` | Add a watch. Every call to the method is logged with its arguments. |
| `Remove(className, methodName)` | Remove a watch. |
| `Clear()` | Remove all watches. |

### Examples

```lua
-- Watch a game method
KRLZomBud.Watches.Add("zombie.iso.IsoPlayer", "addBlood")
KRLZomBud.Watches.Add("zombie.Lua.LuaManager", "RunLua")

-- Logs appear in the game console: [ZB Watch] zombie.iso.IsoPlayer.addBlood(...)

-- Remove when done
KRLZomBud.Watches.Remove("zombie.iso.IsoPlayer", "addBlood")
KRLZomBud.Watches.Clear()
```

> **Note**: The class must be loaded for the watch to apply. If the class is not yet loaded, the watch is registered and will apply on first load.

---

## Java Mod Status

Query the current loader policy and per-mod load status. Useful for mods that display Java mod information (e.g. [ZBetterModList](https://github.com/zed-0xff/ZBetterModList)).

| Method | Description |
|--------|-------------|
| `KRLZomBud.getPolicy()` | Returns the active policy as a string: `"prompt"`, `"deny-new"`, or `"allow-all"`. |
| `KRLZomBud.getJavaModStatus(modId)` | Returns a table for the given mod id, or `nil` if KRL ZomBud didn't consider a JAR for it. |

### Status table fields

| Field       | Type    | Meaning |
|-------------|---------|---------|
| `loaded`    | boolean | `true` if the JAR was actually loaded this run. |
| `reason`    | string  | `"loaded"` or a short skip reason (e.g. `"blocked by policy=deny-new"`). |
| `sha256`    | string  | Hex SHA-256 of the JAR, or `nil` if there was no JAR. |
| `decision`  | string  | `"yes"`, `"no"`, or `nil` if no decision was recorded. |
| `persisted` | boolean | `true` if the decision came from the on-disk approvals file (vs session-only). |

### Example

```lua
local status = KRLZomBud.getJavaModStatus("SomeJavaMod")
if status then
    if status.loaded then
        print("loaded, decision=" .. tostring(status.decision) .. ", persisted=" .. tostring(status.persisted))
    else
        print("blocked: " .. status.reason)
    end
end
```

---

## KRLZomBud Core Methods

General-purpose methods available on the `KRLZomBud` global.

| Method | Description |
|--------|-------------|
| `getVersion()` | Returns the mod version string. |
| `getFullVersionString()` | Returns e.g. `"KRL ZomBud v1.0"`. |
| `getClosureFilename(obj)` | Filename of a Lua closure, or nil. |
| `getClosureInfo(obj)` | Table with `file`, `filename`, `name`, `numParams`, `isVararg`, `line`, `fileLastModified` for a Lua closure; nil otherwise. |
| `getCallableInfo(obj)` | Same as `zbmethod(obj)`: metadata for Lua closures and Java callables. |
| `getPatchReport()` | Results of the load-time patch checks: `disabledMods` (list of mod ids whose Java side was turned off) and `entries` (tables with `modId`, `patchClass`, `targetClass`, `method`, `descriptor`, `kind`, `message`, `optional`, `actionTaken`). Same data as `patch-report.json`; see the [Modding Guide](ModdingGuide.md#load-time-patch-checks). |

# Migrating a Mod from ZombieBuddy to KRL ZomBud

KRL ZomBud is a fork of [ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy) (see [RENAME.md](RENAME.md)). Its Java
package, `mod.info` keys, manifest attribute and Lua names differ from ZombieBuddy's.

**KRL ZomBud doesn't load mods built for ZombieBuddy until they're rebuilt.** A jar compiled against ZombieBuddy
references `me.zed_0xff.zombie_buddy` classes, which KRL ZomBud doesn't contain and doesn't remap. Change the names
below, compile against `KRLZomBud.jar`, and ship the new jar.

This guide assumes your mod already targets ZombieBuddy 3.x (the `annotations.Patch` API). A ZombieBuddy 2.x mod also
has to replace removed APIs such as `Accessor` with `Reflect` or `@Patch.Field`; the 2.x → 3.x changes are described
in upstream ZombieBuddy's own migration guide.

## Renames

### Java packages and classes

| ZombieBuddy | KRL ZomBud |
|---|---|
| `me.zed_0xff.zombie_buddy.annotations.Patch` | `io.github.karl3uwu.zombud.api.Patch` |
| `me.zed_0xff.zombie_buddy.annotations.Shadow` | `io.github.karl3uwu.zombud.api.Shadow` |
| `me.zed_0xff.zombie_buddy.Exposer` | `io.github.karl3uwu.zombud.api.Exposer` |
| `me.zed_0xff.zombie_buddy.Reflect` | `io.github.karl3uwu.zombud.api.Reflect` |
| `me.zed_0xff.zombie_buddy.EventsAPI` | `io.github.karl3uwu.zombud.api.EventsAPI` |
| `me.zed_0xff.zombie_buddy.Callbacks` | `io.github.karl3uwu.zombud.api.Callbacks` |
| `me.zed_0xff.zombie_buddy.LuaUtils` | `io.github.karl3uwu.zombud.api.LuaUtils` |
| `me.zed_0xff.zombie_buddy.LuaJSON` | `io.github.karl3uwu.zombud.api.LuaJSON` |
| `me.zed_0xff.zombie_buddy.ZombieBuddy` (version info) | `io.github.karl3uwu.zombud.api.ZomBud` |
| `me.zed_0xff.zombie_buddy.annotations.Internal` | `io.github.karl3uwu.zombud.annotations.Internal` (internal, don't use) |
| everything else under `me.zed_0xff.zombie_buddy` | same name under `io.github.karl3uwu.zombud` (internal, may change) |

Only `io.github.karl3uwu.zombud.api` is meant for mods. Classes outside it, and the `api` members marked
"Internal" in their javadoc (`Exposer.exposeAnnotatedClasses`, `Exposer.exposeClassesFromPackage`, `Reflect.init`),
are used by the loader and can change without notice.

In most mods the change is the imports:

```java
// ZombieBuddy
import me.zed_0xff.zombie_buddy.annotations.Patch;
import me.zed_0xff.zombie_buddy.Exposer;

// KRL ZomBud
import io.github.karl3uwu.zombud.api.Patch;
import io.github.karl3uwu.zombud.api.Exposer;
```

### `mod.info`

| ZombieBuddy | KRL ZomBud |
|---|---|
| `require=\ZombieBuddy` | `require=\KRLZomBud` |
| `ZBVersionMin`, `ZBVersionMinN` | `KRLVersionMin`, `KRLVersionMinN` |
| `ZBVersionMax`, `ZBVersionMaxN` | `KRLVersionMax`, `KRLVersionMaxN` |

The old keys are not read; a mod that still uses them loads with no version range. The per-jar numeric suffix works
as before (see [Multiple JARs per mod](ModdingGuide.md#multiple-jars-per-mod-supporting-several-krl-zombud-api-versions)).
Version ranges now order prereleases correctly: `3.0.0-beta3` is below `3.0.0`, so `KRLVersionMin=3.0.0` doesn't match
a beta build. KRL ZomBud continues ZombieBuddy's numbering for now (`java/VERSION`, currently `3.0.0-beta3`).

`javaJarFile`, `javaPkgName` and `javaPreload` are unchanged.

### Jar manifest

| ZombieBuddy | KRL ZomBud |
|---|---|
| `ZB-Preload: true` | `KRL-Preload: true` |

Preloaded mods need `javaPreload=true` in `mod.info` and `KRL-Preload: true` in the manifest. `ZB-Preload` is not read.

### Lua

| ZombieBuddy | KRL ZomBud |
|---|---|
| `ZombieBuddy` (global) | `KRLZomBud` |
| `ZombieBuddy.Events` | `KRLZomBud.Events` |
| `ZombieBuddy.Watermark` | `KRLZomBud.Watermark` |
| `ZombieBuddy.Patches.GameLoadingState` | `KRLZomBud.Patches.GameLoadingState` |
| `ZombieBuddy.LogOverlay` (experimental) | `KRLZomBud.LogOverlay` |
| `ZombieBuddy.Watches` (experimental) | `KRLZomBud.Watches` |
| `ZombieBuddy.EventLog` (experimental) | `KRLZomBud.EventLog` |
| `ZombieBuddy.PacketLog` (experimental) | `KRLZomBud.PacketLog` |
| translation keys `UI_ZB_*` | `UI_KRL_*` |

A Lua check such as `if ZombieBuddy then` becomes `if KRLZomBud then`. The methods on these tables are unchanged.
The experimental debug globals (`zbinspect`, `zbmethods`, `zbgrep`, `zbmap` and the others in
[DevDebugFunctions.md](DevDebugFunctions.md)) keep their names. The Mod Options id stays `ZombieBuddy` so saved option
values carry over.

### Config directory, environment and launch options

These affect players and tooling, not mod code.

| ZombieBuddy | KRL ZomBud |
|---|---|
| `~/.zombie_buddy` (config, approvals, caches) | `~/.krl_zombud` |
| `ZB_VERBOSITY` environment variable | `KRL_VERBOSITY` |
| `-Dzb.<arg>=...` with `prop_prefix=zb` (the documented example) | `-Dkrl.<arg>=...` with `prop_prefix=krl` (or a bare `prop_prefix`) |
| `zbLuaAPI.txt` port file (experimental HTTP API) | `krlLuaAPI.txt` |

On the first start without `config_dir`, if `~/.krl_zombud` doesn't exist and `~/.zombie_buddy` does, KRL ZomBud
copies the old directory over, so approval decisions, trusted authors and preload settings carry over. The old
directory is never changed or deleted. `ZB_VERBOSITY` is not read; a warning is logged if it is set without
`KRL_VERBOSITY`. `prop_prefix` still reads whatever prefix you pass, so `prop_prefix=zb` keeps working if you set it
explicitly.

### Your own `@Exposer.LuaClass` names

If you named your Lua table under ZombieBuddy's (e.g. `@Exposer.LuaClass(name = "ZombieBuddy.MyMod")`), move it to
your own namespace (`"MyMod"`). KRL ZomBud doesn't create a `ZombieBuddy` global any more.

## Removed

- **2.x annotation compatibility.** ZombieBuddy 3.x rewrote references to the 2.x `me.zed_0xff.zombie_buddy.Patch`
  when loading a jar. KRL ZomBud has no such step.

## Build

Compile against `KRLZomBud.jar` with JDK 25:

```gradle
dependencies {
    compileOnly files("path/to/KRLZomBud.jar")
}
```

## Checklist

1. Replace `me.zed_0xff.zombie_buddy` imports with `io.github.karl3uwu.zombud.api` (and `ZombieBuddy` with `ZomBud`).
2. In `mod.info`: `require=\KRLZomBud`, and rename `ZBVersionMin`/`ZBVersionMax` to `KRLVersionMin`/`KRLVersionMax`.
3. If you preload: rename the manifest attribute to `KRL-Preload`.
4. In Lua: rename `ZombieBuddy.*` to `KRLZomBud.*`.
5. Rebuild against `KRLZomBud.jar`, run the game with `verbosity=1`, and look for patch warnings.

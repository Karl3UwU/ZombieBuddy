# Session files

A **session file** describes one game launch prepared by a launcher: which mods, which Java jars (pinned by
sha256), where to write progress and reports. When the game is started with a session file, KRL ZomBud loads
exactly what the file says, before any game class is loaded, and never asks questions.

The format is plain JSON so a launcher in any language can write it. The full definition is the JSON Schema
[`schema/session-v1.schema.json`](../schema/session-v1.schema.json) (draft 2020-12); examples are in
[`schema/examples/`](../schema/examples/). The loader's reader (`io.github.karl3uwu.zombud.session.SessionFile`)
is tested against the same examples, and the examples are tested against the schema.

## Format (version 1)

UTF-8 JSON object (a byte-order mark is tolerated). Unknown fields are ignored. Paths are absolute, in POSIX
(`/home/...`) or Windows form (`C:\...`, `C:/...`, `\\server\share\...`).

| Field | Type | Meaning |
|---|---|---|
| `schema` | `1` | Format version. Any other value: the loader refuses the file (see [Fail open](#fail-open)). |
| `id` | string | Launcher-chosen id, e.g. a UUID. |
| `created` | string | RFC 3339 date-time, e.g. `2026-11-28T01:00:00Z`. |
| `gameBuild` | string | Game build the session was prepared for, as `Core.getGameAndBuildVersion()` reports it, e.g. `42.22.2`. |
| `loaderVersion` | string | KRL ZomBud version the launcher expects. Informational. |
| `intent` | object | `kind`: `continue` (needs `save`), `host` (needs `server`), `new`, `join` (needs `address`). Recorded only, see [Intent](#intent). |
| `mods` | array | Mods in load order. Each: `id` (unique; a leading `\` as in mod.info's `require=` is allowed and dropped), `workshopId` (optional integer), `dir` (mod folder for the chosen build: the version folder such as `.../42.13`, else `common`, else the mod folder), `java` (optional array). |
| `mods[].java[]` | object | `jar` (absolute path), `sha256` (64 hex digits), `pkg` (`javaPkgName` from mod.info). |
| `safeMode` | boolean | `true`: Lua mods load, Java mods don't, core fixes are off. |
| `coreFixes` | object | `enabled` (boolean), `overrides` (optional object of fix id → boolean). Read, not used yet (no core fixes exist yet). |
| `stageFile` | string | File the loader appends stage markers to. |
| `reportDir` | string | Directory for `patch-report.json`. |

The schema can't express two rules the loader also checks: mod ids are unique, and an `intent` field required by its
`kind` must not be empty.

A Java jar whose `pkg` is KRL ZomBud's own package (`io.github.karl3uwu.zombud`) or upstream ZombieBuddy's
(`me.zed_0xff.zombie_buddy`) is never loaded. The CLI leaves such jars out; a launcher should too.

## Discovery

- `-Dkrl.session=<path>` on the game's Java command line, or
- environment variable `KRL_SESSION=<path>`.

The property wins when both are set. With neither, nothing changes: mods load the usual in-game way.

## What the agent does

All of this happens in agent `premain`, before the game loads its classes.

1. **Read and validate** the file. Any problem → [fail open](#fail-open).
2. **Stage file**: append `premain`.
3. **Game build**: read from the bytes of `zombie/core/Core.class` on the game class path, without loading it
   (`GameBuild`: the `new GameVersion(major, minor, suffix)` in its static initializer plus the constant
   `getGameAndBuildVersion()` appends). If it differs from `gameBuild`: Java mods stay off and the problem is reported;
   the session's mod list is still used for Lua mods. If the build can't be read that way (the game's code changed
   shape), Java mods load and the build is checked again once the game is up (`onGameInitComplete`, via
   `Core.getGameAndBuildVersion()`). A mismatch found that late can only be reported (log, report, on-screen line,
   stage file): the Java mods are already in. Restart from the launcher.
4. **Safe mode**: no Java mods; done.
5. **Each Java jar**, in session order:
   - the file must exist and its sha256 must equal the session's, else it's skipped (`JAR_MISSING`, `SHA256_MISMATCH`);
   - its `.zbs` signature must not be invalid (same rule as in game; `SIGNATURE_BLOCKED`);
   - it must be approved: allowed in `mod_approvals.json` for that hash, or policy `allow-all`, or a valid signature by
     a trusted author. There is no prompt in session mode; an unapproved jar is skipped with "needs approval in the
     launcher" (`NOT_APPROVED`);
   - then it is transformed and checked like any mod ([load-time patch checks](ModdingGuide.md#load-time-patch-checks)),
     its patches are installed and its `PreMain` runs. Game classes aren't loaded yet, so the patches are woven when each
     class is first defined: no retransformation. Patches are installed *before* `PreMain` runs, so `PreMain` touching a
     game class doesn't force a retransform.
6. **Mod list**: when the game calls `ZomboidFileSystem.loadMods(List)` (from `loadMods("default")` at startup and on
   later reloads), the bundled patch replaces the list argument with the session's mod ids, in memory only.
   `default.txt` and other game files are never written. Exception: as a multiplayer client or on a server the
   server's mod list is used (`GameClient.client` / `GameServer.server`).
7. **In-game Java path off**: at that same call the usual Java mod loading (approval dialog, loading jars) doesn't run;
   instead each jar loaded at premain gets its `Main` called (same phase as without a session), once.
8. **Reports**: `patch-report.json` goes to `reportDir`, with a `session` block (file, id, safe mode, error, number of
   Java jars loaded) and one entry per skipped jar or failed patch.

## Fail open

Any problem with the session (file missing or unreadable, not JSON, invalid, unknown `schema`, wrong game build) is
logged, added to the report and, when the stage file is known, written to it as `<time> error <message>`. The game
then starts **without Java mods**. A file that couldn't be read at all has no usable stage file or report dir, so the
report goes to the KRL ZomBud config dir (`~/.krl_zombud/patch-report.json`) and the game uses its own mod list.
The in-game Java loading path stays off whenever a session was requested, so a broken session never falls back to
prompting.

## Stage markers

Each line is `<ISO-8601 UTC time> <stage>`, appended (the file and its folder are created if needed):

| Stage | Written by | When |
|---|---|---|
| `premain` | agent | session file read, before any mod loads (once) |
| `mods-loaded` | `ZomboidFileSystem.loadMods(List)` patch, on exit | the game finished loading the mod list (once) |
| `main-menu` | Lua `Events.OnMainMenuEnter` → `KRLZomBud.markSessionStage` | each time the main menu opens |
| `in-game` | Lua `Events.OnGameStart` → `KRLZomBud.markSessionStage` | a game (save) started |
| `quit` | JVM shutdown hook | normal exit, including `System.exit` and Ctrl+C; **not** after a crash or a kill |
| `error <message>` | agent | see [Fail open](#fail-open) |

A launcher can treat "no `quit` after the process ended" as a crash.

## Intent

`intent` is recorded and shown to Lua (`KRLZomBud.getSession().intent`), nothing acts on it yet. Loading a save or
hosting automatically waits for the launcher experiment E5.

## Lua

- `KRLZomBud.isSessionMode()`: true when a session was requested (even if it failed).
- `KRLZomBud.getSession()`: nil, or `{ id, gameBuild, safeMode, error, intent = { kind, save, server, address }, mods = { ids } }`.

The in-game mods menu is not hidden in session mode yet; that is left for the launcher work.

## Command line

The agent jar runs as a tool without the game:

```
java -jar KRLZomBud.jar session create --mods A,B[,..] [--game-dir D] [--user-dir U] [--out F] [--safe-mode]
     [--game-build B] [--intent new|continue|host|join] [--save S] [--server S] [--address A]
     [--stage-file F] [--report-dir D] [--id ID]
java -jar KRLZomBud.jar session validate F
```

`create` finds each mod id in `<user dir>/mods`, `<user dir>/Workshop/<item>/Contents/mods` and the Steam Workshop
folder next to the game (`steamapps/workshop/content/108600/<id>/mods`), local first. For B42-style mods it picks the
highest version folder not newer than the game build, plus `common`, like the game. Java jars, sha256 and package come
from mod.info through the loader's own rules (`KRLVersionMin`/`KRLVersionMax`, client/server folders). The game build
is read from `projectzomboid.jar` in `--game-dir`, or given with `--game-build`. Defaults: `--user-dir ~/Zomboid`,
`--out ~/.krl_zombud/sessions/<id>.json`, `--intent new`, stage file `<out dir>/<id>.stage`, report dir `<out dir>`.
The file is validated before it's written. Exit codes: 0 ok, 1 invalid input, 2 usage.

`validate` prints every problem in the file. The mod discovery lives in `io.github.karl3uwu.zombud.ModFolders`, so a
launcher written in Java can share it.

## Not verified yet

- In the real game: the mod-list override at startup, `mods-loaded` / `main-menu` / `in-game` / `quit` markers, and
  that no game class is retransformed for session jars. The tests cover these with test classes standing in for game
  classes, calling the same hooks the bundled patch calls.
- With a real launcher: anything about `intent`, hiding the in-game mods menu, and the launcher's own handling of the
  stage file and report.

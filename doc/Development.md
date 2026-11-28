# Development setup

How to build, test and explore this project on Windows. (Linux and macOS work too, but the native loader is Windows-only.)

## Prerequisites

| Tool | Why | Notes |
|---|---|---|
| Project Zomboid (Build 42) | Compile against the game, run tests | The build needs `projectzomboid.jar` from your install |
| A JDK 17+ to run Gradle | Starts the Gradle wrapper | Any installed JDK works. The **build itself uses JDK 25**, which Gradle downloads automatically if you don't have it (Foojay toolchain resolver) |
| MinGW-w64 (`x86_64-w64-mingw32-gcc`, `mingw32-make`) | Builds `zbNative.dll` | Only needed when changing `c/windows` |
| Go 1.25+ | Builds the installer | Only needed when changing `installer/` |
| IntelliJ IDEA 2025.2+ (recommended) | Java 25 and Gradle 9 support | Older versions can't import the project correctly |

## Point the build at your game

Pick one:

- pass `-PpzDir=<game folder>` on each Gradle command,
- set the `PZ_DIR` environment variable,
- or add `pzDir=<game folder>` to `%USERPROFILE%\.gradle\gradle.properties` (applies to every build).

Use the game's install folder, e.g. `C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid`. On macOS, use the
folder that contains `Project Zomboid.app`; the build finds the jar inside the bundle.

## Build and test

```powershell
cd java
.\gradlew shadowJar                          # KRLZomBud.jar -> java\build\jdk25\libs\
.\gradlew unitTest test_vanilla test_patched # the three main test suites
.\gradlew test_sessions                      # session-mode agent tests
```

- `unitTest`: plain unit tests.
- `test_vanilla`: runs without the agent.
- `test_patched`: runs with the built jar attached as a Java agent and applies the patches in `testpatches/`, plus
  the deliberately broken mods in `testbroken/`.
- `test_sessions`: four short JVM runs with a launcher session file (normal, safe mode, corrupt file, missing file).
  The session files are made by the real `session create` CLI from mod folders built around the `testbroken` jars.

The launcher session file format, the agent's session mode and the `session create/validate` CLI are described in
[SessionFile.md](SessionFile.md).

### What CI checks, and what it doesn't

GitHub Actions (`.github/workflows/ci.yml`) runs on every pull request. It builds `zbNative.dll`, runs the installer's
Go tests on Windows and Linux, and checks that the Gradle setup loads and provisions JDK 25.

CI can't compile the Java code or run its tests: that needs `projectzomboid.jar`, which can't be used there. Run the
build and all three test suites locally before you open or merge a pull request.

## Native loader (`zbNative.dll`)

```powershell
cd c\windows
mingw32-make
```

The Makefile targets Windows 10 (`-D_WIN32_WINNT=0x0A00`), which the runtime guard needs. On Linux or macOS, install
the `mingw-w64` package and run `make` instead.

## Installer

```powershell
cd installer
go test ./...
$env:GOOS="windows"; $env:GOARCH="amd64"; go build -o KRLZomBudInstaller.exe
```

The `testdata/` launcher files keep their original line endings on purpose (see `.gitattributes`).

## Read the game's code

Patches target game methods by name, so you need the game's source to write them. Generate a local decompiled
reference with:

```powershell
.\tools\decompile-game.ps1 -GameDir "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid"
```

It runs in Windows PowerShell 5.1 or PowerShell 7 (`pwsh tools/decompile-game.ps1` on Linux/macOS). Without `-GameDir`
it uses `PZ_DIR`, then the default Steam folder for your OS.

- Output goes to `..\pz-src\<game version>\src` (next to the repo, not inside it), one folder per game build.
  Diffing two folders shows what a game update changed.
- Only the game's own packages are decompiled: `zombie`, `se.krka` (the Kahlua Lua VM), `fmod`, `tis`, `astar`, `N3D`.
- The script downloads Vineflower from Maven Central (checksum-verified) and uses Gradle's JDK 25.
- **Never commit or share this output.** It is The Indie Stone's proprietary code. `pz-src/` is gitignored as a safety net.

To browse it in IntelliJ, open the `src` folder as its own project, or attach it as the source for `projectzomboid.jar`
when IntelliJ shows a decompiled game class ("Choose Sources...").

## Run your build in the game

Copy `java\build\jdk25\libs\KRLZomBud.jar` and `c\windows\zbNative.dll` into the game folder (back up the originals
first). Don't leave a `KRLZomBud.jar.zbs` signature file next to a self-built jar: a signature that doesn't match its
jar blocks loading. Watch `%USERPROFILE%\Zomboid\console.txt` for `[ZB]` lines.

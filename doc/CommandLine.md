# ZombieBuddy Command-Line Parameters

ZombieBuddy accepts configuration parameters through the Java agent argument string. Parameters are passed as `key=value` pairs separated by commas.

---

## Usage

**macOS / Linux:**

```
-javaagent:KRLZomBud.jar=param1=value1,param2=value2 --
```

**Windows:**

```
-agentlib:zbNative=param1=value1,param2=value2 --
```

> **Note:** The `--` at the end is mandatory.

---

## Parameters

### verbosity

Controls the amount of logging output.

| Value | Description |
|-------|-------------|
| `-2` | ERROR - log errors only |
| `-1` | WARN  - log warnings + ERROR |
| `0`  | (default) INFO - log informational messages + WARN |
| `1`  | DEBUG - log more debugging information, including the patches transformation progress info |
| `2`  | TRACE - log a vast amount of details |

**Example:**

```
-javaagent:KRLZomBud.jar=verbosity=2 --
```

Can also be set via the `KRL_VERBOSITY` environment variable (overrides the command-line value). ZombieBuddy's `ZB_VERBOSITY` is no longer read; a warning is logged if only it is set.

---

### policy

Controls how ZombieBuddy handles unknown or changed Java mod JARs.

| Value | Description |
|-------|-------------|
| `prompt` | (default) Ask via a native dialog for each unknown/changed JAR |
| `deny-new` | Silently skip any JAR that isn't already approved. No dialogs shown |
| `allow-all` | Load every JAR without prompting. **Not recommended** - use only in controlled setups |

**Example:**

```
-javaagent:KRLZomBud.jar=policy=deny-new --
```

The policy is locked during agent startup, before any Java mod is loaded. A later-loading Java mod cannot change it.

---

### allow_unsigned_mods

Controls whether mods without ZBS signatures are allowed.

| Value | Description |
|-------|-------------|
| `true` | (default) Allow mods without `.zbs` signature files |
| `false` | Treat missing `.zbs` sidecar as an invalid signature (blocked unless `policy=allow-all`) |

**Example:**

```
-javaagent:KRLZomBud.jar=allow_unsigned_mods=false --
```

> **Note:** Invalid signatures (present `.zbs` but verification fails) are always blocked when ZBS verification is enabled.

---

### frontend

Selects the UI for Java mod approval dialogs.

| Value | Description |
|-------|-------------|
| `auto` | (default) Automatically select the best available frontend |
| `swing` | Use Swing batch dialog + TinyFileDialogs for per-mod prompts |
| `tinyfd` | Use TinyFileDialogs for all prompts |
| `console` | Use stdin/stdout (headless mode) |

**Example:**

```
-javaagent:KRLZomBud.jar=frontend=console --
```

---

### http_client_timeout

Timeout in seconds for HTTP connections and requests. Applies to all outbound HTTP calls: Steam Workshop API, Steam profile pages, and the GitHub authors list.

| Value | Description |
|-------|-------------|
| `5` | (default) |
| `30` | Use 30 seconds as both the connect and request timeout |

**Example:**

```
-javaagent:KRLZomBud.jar=http_client_timeout=30 --
```

---

### http_cache_ttl

Time-to-live in seconds for the in-memory HTTP response cache. Applies to all outbound HTTP calls: Steam Workshop API, Steam profile pages, and the GitHub authors list.

| Value | Description |
|-------|-------------|
| `3600` | (default) Cache responses for 1 hour |
| `0` | Disable caching entirely |

**Example:**

```
-javaagent:KRLZomBud.jar=http_cache_ttl=0 --
```

---

### config_dir

Directory used for KRL ZomBud configuration and cache files. Defaults to `~/.krl_zombud`.

On the first start without `config_dir`, if `~/.krl_zombud` doesn't exist and `~/.zombie_buddy` does, the old directory is copied to `~/.krl_zombud`. The old directory is never changed or deleted.

**Example:**

```
-javaagent:KRLZomBud.jar=config_dir=/path/to/krl_zombud_config --
```

---

### prop_prefix

Import JVM system properties into KRL ZomBud agent arguments. When set, any system property whose name starts with this prefix plus `.` is read, the prefix and dot are stripped, and the remaining name becomes an agent argument. A bare `prop_prefix` without a value uses the prefix `krl`.

If `prop_prefix` is not specified, KRL ZomBud does not read system properties into agent arguments.

**Example:**

```
-Dkrl.verbosity=2 -Dkrl.policy=deny-new -javaagent:KRLZomBud.jar=prop_prefix=krl --
```

This is equivalent to:

```
-javaagent:KRLZomBud.jar=verbosity=2,policy=deny-new --
```

---

### experimental

Enables experimental patches. This is a flag parameter (no value needed).

**Example:**

```
-javaagent:KRLZomBud.jar=experimental --
```

---

### patches_jar

Load additional patch JARs at startup. Useful for development or testing patches without packaging them as a mod.

**Format:** `path:package_name` - multiple entries separated by semicolons.

**Example:**

```
-javaagent:KRLZomBud.jar=patches_jar=/path/to/MyPatches.jar:com.example.patches --
```

Multiple JARs:

```
-javaagent:KRLZomBud.jar=patches_jar=/path/to/First.jar:com.first;/path/to/Second.jar:com.second --
```

---

### expose_classes

Expose Java classes to Lua at startup. Semicolon-separated list of fully-qualified class names.

**Example:**

```
-javaagent:KRLZomBud.jar=expose_classes=com.example.MyClass;com.example.OtherClass --
```

---

### exit_after_game_init

Exit the game immediately after initialization completes. Useful for testing or CI pipelines. This is a flag parameter (no value needed).

**Example:**

```
-javaagent:KRLZomBud.jar=exit_after_game_init --
```

---

## Combining Parameters

Multiple parameters can be combined with commas:

```
-javaagent:KRLZomBud.jar=verbosity=1,policy=deny-new,allow_unsigned_mods=false --
```

Windows example:

```
-agentlib:zbNative=verbosity=2,experimental --
```

---

## Environment Variables

| Variable | Description |
|----------|-------------|
| `KRL_VERBOSITY` | Sets verbosity level (overrides command-line `verbosity` parameter). Replaces ZombieBuddy's `ZB_VERBOSITY`, which is no longer read. |
| `KRL_SESSION` | Path of a launcher session file (same as `-Dkrl.session=<path>`, which wins). See [SessionFile.md](SessionFile.md). |

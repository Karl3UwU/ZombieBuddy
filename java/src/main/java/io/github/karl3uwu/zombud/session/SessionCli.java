package io.github.karl3uwu.zombud.session;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.karl3uwu.zombud.ModFolders;

/**
 * {@code java -jar KRLZomBud.jar session create|validate ...}. See {@code doc/SessionFile.md}.
 * Runs without the game: only reads mod folders and {@code projectzomboid.jar}.
 */
public final class SessionCli {
    private SessionCli() {}

    static final String USAGE = String.join("\n",
        "Usage:",
        "  java -jar KRLZomBud.jar session create --mods A,B[,..] [--game-dir D] [--user-dir U] [--out F]",
        "         [--safe-mode] [--game-build B] [--intent new|continue|host|join] [--save S] [--server S]",
        "         [--address A] [--stage-file F] [--report-dir D] [--id ID]",
        "  java -jar KRLZomBud.jar session validate F",
        "",
        "create finds the mods in <user dir>/mods, <user dir>/Workshop and the Steam Workshop folder next to the",
        "game, fills their folders, Java jars, sha256 and package from mod.info, and writes a session file.",
        "Defaults: --user-dir ~/Zomboid, --out ~/.krl_zombud/sessions/<id>.json, --intent new,",
        "--stage-file <out dir>/<id>.stage, --report-dir <out dir>. --game-build is read from the game folder.");

    /** Exit code: 0 ok, 1 invalid input or file, 2 usage. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            err.println(USAGE);
            return 2;
        }
        try {
            return switch (args[0]) {
                case "create" -> create(parseOptions(args, 1), out, err);
                case "validate" -> validate(args, out, err);
                default -> {
                    err.println("unknown session command: " + args[0]);
                    err.println(USAGE);
                    yield 2;
                }
            };
        } catch (UsageException e) {
            err.println(e.getMessage());
            err.println(USAGE);
            return 2;
        }
    }

    private static int validate(String[] args, PrintStream out, PrintStream err) throws UsageException {
        if (args.length != 2) throw new UsageException("session validate takes one file");
        Path file = Path.of(args[1]);
        try {
            Session s = SessionFile.read(file);
            out.println(file + ": valid (session " + s.id() + ", " + s.mods().size() + " mods, game " + s.gameBuild() + ")");
            return 0;
        } catch (SessionFile.InvalidSessionException e) {
            err.println(file + ": invalid");
            for (String line : e.errors()) err.println("  " + line);
            return 1;
        } catch (IOException e) {
            err.println(file + ": cannot read: " + e.getMessage());
            return 1;
        }
    }

    private static int create(Map<String, String> o, PrintStream out, PrintStream err) throws UsageException {
        String modsArg = o.get("mods");
        if (modsArg == null || modsArg.isBlank()) throw new UsageException("--mods is required");
        List<String> wanted = new ArrayList<>();
        for (String id : modsArg.split(",")) {
            String t = SessionFile.normalizeModId(id);
            if (!t.isEmpty() && !wanted.contains(t)) wanted.add(t);
        }

        Path gameDir = o.containsKey("game-dir") ? Path.of(o.get("game-dir")).toAbsolutePath() : null;
        Path userDir = Path.of(o.getOrDefault("user-dir", Path.of(System.getProperty("user.home"), "Zomboid").toString())).toAbsolutePath();

        String gameBuild = o.get("game-build");
        if (gameBuild == null && gameDir != null) gameBuild = GameBuild.readFromGameDir(gameDir);
        if (gameBuild == null) {
            err.println("cannot read the game build: pass --game-dir <game folder> or --game-build <e.g. 42.22.2>");
            return 1;
        }
        String loaderVersion = loaderVersion();

        Map<String, ModFolders.FoundMod> found = ModFolders.scan(ModFolders.modRoots(userDir, gameDir), gameBuild, loaderVersion);
        List<Session.Mod> mods = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String id : wanted) {
            ModFolders.FoundMod m = found.get(id);
            if (m == null) {
                missing.add(id);
                continue;
            }
            List<Session.JavaJar> jars = new ArrayList<>();
            for (ModFolders.FoundJar j : m.jars()) {
                jars.add(new Session.JavaJar(j.jar().toString(), j.sha256(), j.pkg()));
            }
            mods.add(new Session.Mod(m.id(), m.workshopId(), m.dir().toString(), List.copyOf(jars)));
        }
        if (!missing.isEmpty()) {
            err.println("mods not found: " + String.join(", ", missing));
            return 1;
        }

        Session.Intent intent;
        try {
            Session.IntentKind kind = Session.IntentKind.valueOf(o.getOrDefault("intent", "new").toUpperCase(java.util.Locale.ROOT));
            intent = new Session.Intent(kind, o.get("save"), o.get("server"), o.get("address"));
        } catch (IllegalArgumentException e) {
            throw new UsageException("--intent must be new, continue, host or join");
        }

        String id = o.getOrDefault("id", UUID.randomUUID().toString());
        Path outFile = Path.of(o.getOrDefault("out",
            Path.of(System.getProperty("user.home"), ".krl_zombud", "sessions", id + ".json").toString())).toAbsolutePath();
        Path outDir = outFile.getParent();
        String stageFile = Path.of(o.getOrDefault("stage-file", outDir.resolve(id + ".stage").toString())).toAbsolutePath().toString();
        String reportDir = Path.of(o.getOrDefault("report-dir", outDir.toString())).toAbsolutePath().toString();

        Session s = new Session(Session.SCHEMA_VERSION, id,
            OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toString(),
            gameBuild, loaderVersion, intent, List.copyOf(mods), o.containsKey("safe-mode"),
            new Session.CoreFixes(true, Map.of()), stageFile, reportDir);

        String json = SessionFile.toJson(s);
        try {
            SessionFile.parse(json); // never write a file the loader would reject
        } catch (SessionFile.InvalidSessionException e) {
            err.println("refusing to write an invalid session: " + e.getMessage());
            return 1;
        }
        try {
            Files.createDirectories(outDir);
            Files.writeString(outFile, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            err.println("cannot write " + outFile + ": " + e.getMessage());
            return 1;
        }
        int jarCount = mods.stream().mapToInt(m -> m.java().size()).sum();
        out.println("wrote " + outFile + " (" + mods.size() + " mods, " + jarCount + " Java jars, game " + gameBuild + ")");
        return 0;
    }

    static String loaderVersion() {
        String v = SessionCli.class.getPackage() != null ? SessionCli.class.getPackage().getImplementationVersion() : null;
        return v != null ? v : "unknown";
    }

    private static final java.util.Set<String> FLAGS = java.util.Set.of("safe-mode");

    private static Map<String, String> parseOptions(String[] args, int from) throws UsageException {
        Map<String, String> o = new LinkedHashMap<>();
        for (int i = from; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) throw new UsageException("unexpected argument: " + a);
            String key = a.substring(2);
            String value = null;
            int eq = key.indexOf('=');
            if (eq >= 0) {
                value = key.substring(eq + 1);
                key = key.substring(0, eq);
            } else if (!FLAGS.contains(key)) {
                if (i + 1 >= args.length) throw new UsageException("--" + key + " needs a value");
                value = args[++i];
            }
            o.put(key, value == null ? "" : value);
        }
        return o;
    }

    static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }
}

package io.github.karl3uwu.zombud.session;

import java.util.List;
import java.util.Map;

/**
 * One launch prepared by a launcher: which mods, which Java jars, where to report. Format v1, see
 * {@code doc/SessionFile.md} and {@code schema/session-v1.schema.json}. Read with {@link SessionFile}.
 */
public record Session(
    int schema,
    String id,
    String created,
    String gameBuild,
    String loaderVersion,
    Intent intent,
    List<Mod> mods,
    boolean safeMode,
    CoreFixes coreFixes,
    String stageFile,
    String reportDir
) {
    public static final int SCHEMA_VERSION = 1;

    public enum IntentKind { CONTINUE, HOST, NEW, JOIN }

    /** What the player asked for. Recorded only; acting on it waits for the launcher (see the doc). */
    public record Intent(IntentKind kind, String save, String server, String address) {}

    /** A mod in load order. {@code dir} is the mod folder for the chosen game build. */
    public record Mod(String id, Long workshopId, String dir, List<JavaJar> java) {}

    /** A Java jar of a mod, pinned by hash. */
    public record JavaJar(String jar, String sha256, String pkg) {}

    public record CoreFixes(boolean enabled, Map<String, Boolean> overrides) {}

    /** Mod ids in load order, as the game's active mod list holds them (no leading backslash). */
    public List<String> modIds() {
        return mods.stream().map(Mod::id).toList();
    }
}

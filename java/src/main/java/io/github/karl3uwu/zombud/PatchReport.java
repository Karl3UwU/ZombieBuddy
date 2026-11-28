package io.github.karl3uwu.zombud;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.GsonBuilder;

import io.github.karl3uwu.zombud.api.ZomBud;

/**
 * Results of the load-time patch checks ({@link PatchChecker}) and of patch errors seen while classes were
 * transformed. Kept in {@link LoaderContext}; written to {@code patch-report.json} in the config dir.
 */
public final class PatchReport {

    public static final String FILE_NAME = "patch-report.json";

    /** What the loader did about a problem. */
    public enum Action {
        /** The mod's whole Java side was turned off (no patches, no Main/PreMain). */
        MOD_DISABLED,
        /** Only this patch was skipped (optional patch, or one of our bundled patches). */
        PATCH_SKIPPED,
        /** Logged as a warning; the patch was installed. */
        WARNING,
        /** Reported after the fact: the class was already being loaded, so it stayed unpatched by this mod. */
        CLASS_UNPATCHED,
        /** Session mode: no Java mod was loaded (bad session file, wrong game build, safe mode). */
        ALL_JAVA_MODS_OFF,
    }

    public record Entry(
        String modId,
        String jar,
        String patchClass,
        String targetClass,
        String method,
        String descriptor,
        PatchChecker.Kind kind,
        String message,
        boolean optional,
        Action actionTaken
    ) {}

    private final List<Entry> entries = new ArrayList<>();
    /** Mod ids whose Java side was turned off, in order. */
    private final Set<String> disabledMods = new LinkedHashSet<>();

    synchronized void add(Entry e) {
        entries.add(e);
        if (e.actionTaken() == Action.MOD_DISABLED) {
            disabledMods.add(e.modId());
        }
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    public synchronized List<String> disabledMods() {
        return List.copyOf(disabledMods);
    }

    synchronized void clear() {
        entries.clear();
        disabledMods.clear();
    }

    /** Logs the entries of one mod, failures as errors and warnings as warnings. */
    static void log(String modId, List<Entry> modEntries) {
        if (modEntries.isEmpty()) return;
        Logger.warn("Patch check for " + modId + ":");
        for (Entry e : modEntries) {
            String line = "  [" + e.kind() + "] " + e.patchClass() + " -> " + e.targetClass()
                + (Utils.isBlank(e.method()) ? "" : "." + e.method())
                + (Utils.isBlank(e.descriptor()) ? "" : e.descriptor())
                + ": " + e.message() + " (" + describe(e.actionTaken()) + ")";
            if (e.actionTaken() == Action.WARNING) {
                Logger.warn(line);
            } else {
                Logger.error(line);
            }
        }
    }

    private static String describe(Action a) {
        return switch (a) {
            case MOD_DISABLED -> "mod's Java side turned off";
            case PATCH_SKIPPED -> "patch skipped";
            case WARNING -> "warning only";
            case CLASS_UNPATCHED -> "class left unpatched by this mod";
            case ALL_JAVA_MODS_OFF -> "no Java mods loaded";
        };
    }

    /** The in-game message for turned-off mods, or null when none were turned off. */
    String summaryLine() {
        List<String> mods = disabledMods();
        if (mods.isEmpty()) return null;
        String names = String.join(", ", mods);
        return mods.size() == 1
            ? "1 Java mod was turned off because the game changed: " + names + ". See " + FILE_NAME + "."
            : mods.size() + " Java mods were turned off because the game changed: " + names + ". See " + FILE_NAME + ".";
    }

    Map<String, Object> toJsonTree() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("loaderVersion", ZomBud.getVersion());
        root.put("gameBuild", gameBuild());
        root.put("timestamp", Instant.now().toString());
        root.put("disabledMods", disabledMods());
        LoaderContext lc = LoaderContext.current();
        if (lc.sessionRequested) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("file", lc.sessionPath != null ? lc.sessionPath.toString() : null);
            s.put("id", lc.session != null ? lc.session.id() : null);
            s.put("safeMode", lc.session != null && lc.session.safeMode());
            s.put("error", lc.sessionError);
            s.put("javaModsLoaded", lc.sessionJars.size());
            root.put("session", s);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Entry e : entries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("modId", e.modId());
            m.put("jar", e.jar());
            m.put("patchClass", e.patchClass());
            m.put("targetClass", e.targetClass());
            m.put("method", e.method());
            m.put("descriptor", e.descriptor());
            m.put("kind", e.kind().name());
            m.put("message", e.message());
            m.put("optional", e.optional());
            m.put("actionTaken", e.actionTaken().name());
            list.add(m);
        }
        root.put("entries", list);
        return root;
    }

    /** Overwrites {@code patch-report.json} in {@code dir}. Failures are logged, never thrown. */
    void writeTo(Path dir) {
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
            String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()
                .toJson(toJsonTree());
            Files.writeString(dir.resolve(FILE_NAME), json);
        } catch (IOException | RuntimeException e) {
            Logger.error("Could not write " + FILE_NAME + ": " + e);
        }
    }

    /** Writes the report and, when mods were turned off, shows the summary in game. Called after each load step. */
    public void publish() {
        Path dir = LoaderContext.current().reportDir;
        writeTo(dir != null ? dir : Agent.configDir());
        String summary = summaryLine();
        if (summary != null) {
            Logger.error(summary);
            Watermark.setMidLine(summary);
        }
    }

    /** Game version when the game's Core class is already loaded; never loads game classes itself. */
    private static String gameBuild() {
        try {
            String fromPremain = LoaderContext.current().premainGameBuild;
            if (fromPremain != null) return fromPremain;
            var inst = LoaderContext.current().instrumentation;
            if (inst == null) return "unknown";
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if ("zombie.core.Core".equals(c.getName())) {
                    Object core = c.getMethod("getInstance").invoke(null);
                    Object v = core != null ? c.getMethod("getGameAndBuildVersion").invoke(core) : null;
                    return v != null ? v.toString() : "unknown";
                }
            }
        } catch (Throwable t) {
            // not available yet
        }
        return "unknown";
    }
}

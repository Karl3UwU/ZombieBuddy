package io.github.karl3uwu.zombud.api;
import io.github.karl3uwu.zombud.Utils;
import io.github.karl3uwu.zombud.Logger;
import io.github.karl3uwu.zombud.Loader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;

@Exposer.LuaClass(name = "KRLZomBud")
public class ZomBud {
    private static String version = "unknown";
    
    static {
        loadVersionInfo();
    }
    
    private static void loadVersionInfo() {
        try {
            Package pkg = ZomBud.class.getPackage();
            if (pkg != null) {
                String implVersion = pkg.getImplementationVersion();
                if (!Utils.isBlank(implVersion)) {
                    version = implVersion;
                }
            }
        } catch (Exception e) {
            Logger.error("Could not load version info: " + e.getMessage());
        }
    }
    
    public static String getVersion() {
        return version;
    }
    
    public static String getFullVersionString() {
        return io.github.karl3uwu.zombud.ModIdentity.DISPLAY_NAME + " v" + version;
    }

    public static void setAutoFixModOrder(boolean value) {
        Loader.setAutoFixModOrder(value);
    }

    public static void setFixMacOSRetina(boolean value) {
        Loader.setFixMacOSRetina(value);
    }

    /**
     * Lua: {@code KRLZomBud.getPatchReport()}. A table with {@code disabledMods} (list of mod ids whose Java side was
     * turned off) and {@code entries} (one table per problem: modId, patchClass, targetClass, method, descriptor,
     * kind, message, optional, actionTaken). Same data as {@code patch-report.json}.
     */
    public static KahluaTable getPatchReport() {
        var report = io.github.karl3uwu.zombud.LoaderContext.current().patchReport();
        var tbl = LuaManager.platform.newTable();
        var disabled = LuaManager.platform.newTable();
        int i = 1;
        for (String id : report.disabledMods()) {
            disabled.rawset(Double.valueOf(i++), id);
        }
        tbl.rawset("disabledMods", disabled);
        var entries = LuaManager.platform.newTable();
        i = 1;
        for (var e : report.entries()) {
            var t = LuaManager.platform.newTable();
            t.rawset("modId", e.modId());
            t.rawset("patchClass", e.patchClass());
            t.rawset("targetClass", e.targetClass());
            t.rawset("method", e.method());
            t.rawset("descriptor", e.descriptor());
            t.rawset("kind", e.kind().name());
            t.rawset("message", e.message());
            t.rawset("optional", e.optional());
            t.rawset("actionTaken", e.actionTaken().name());
            entries.rawset(Double.valueOf(i++), t);
        }
        tbl.rawset("entries", entries);
        return tbl;
    }

    /** Lua: {@code KRLZomBud.isSessionMode()}: the game was started by a launcher with a session file. */
    public static boolean isSessionMode() {
        return io.github.karl3uwu.zombud.SessionLoader.isSessionMode();
    }

    /**
     * Lua: {@code KRLZomBud.getSession()}. nil outside session mode or when the file couldn't be used; otherwise
     * a table with id, gameBuild, safeMode, error (string or nil), intent {kind, save, server, address} and mods
     * (list of ids). The intent is recorded only; nothing acts on it yet.
     */
    public static KahluaTable getSession() {
        var s = io.github.karl3uwu.zombud.SessionLoader.session();
        if (s == null) return null;
        var t = LuaManager.platform.newTable();
        t.rawset("id", s.id());
        t.rawset("gameBuild", s.gameBuild());
        t.rawset("safeMode", s.safeMode());
        String error = io.github.karl3uwu.zombud.SessionLoader.sessionError();
        if (error != null) t.rawset("error", error);
        var intent = LuaManager.platform.newTable();
        intent.rawset("kind", s.intent().kind().name().toLowerCase(java.util.Locale.ROOT));
        if (s.intent().save() != null) intent.rawset("save", s.intent().save());
        if (s.intent().server() != null) intent.rawset("server", s.intent().server());
        if (s.intent().address() != null) intent.rawset("address", s.intent().address());
        t.rawset("intent", intent);
        var mods = LuaManager.platform.newTable();
        int i = 1;
        for (String id : s.modIds()) mods.rawset(Double.valueOf(i++), id);
        t.rawset("mods", mods);
        return t;
    }

    /** Lua: {@code KRLZomBud.markSessionStage("main-menu" | "in-game")}; other names are ignored. */
    public static void markSessionStage(String stage) {
        io.github.karl3uwu.zombud.SessionLoader.markStageFromLua(stage);
    }

    // lua-facing variant of Loader.getActiveJavaMods(). Returns a list of modIds for JARs that were loaded this run.
    public static KahluaTable getActiveJavaMods() {
        var tbl = LuaManager.platform.newTable();
        for (var modInfo : Loader.getActiveJavaMods()) {
            var modTbl = LuaManager.platform.newTable();
            modTbl.rawset("id", modInfo.id());
            modTbl.rawset("jarPath", modInfo.jarPath());
            modTbl.rawset("flags", LuaUtils.mapToLuaTable(modInfo.flags().toMap()));
            tbl.rawset(modInfo.id(), modTbl);
        }
        return tbl;
    }

    public static String getClosureFilename(Object obj) {
        if (obj instanceof LuaClosure closure) {
            if (closure == null || closure.prototype == null)
                return null;

            return closure.prototype.filename;
        }

        return null;
    }

    public static KahluaTable getClosureInfo(Object obj) {
        if (obj instanceof LuaClosure closure) {
            if (closure == null || closure.prototype == null)
                return null;

            var tbl = LuaManager.platform.newTable();
            tbl.rawset("file",      closure.prototype.file);
            tbl.rawset("filename",  closure.prototype.filename);
            tbl.rawset("name",      closure.prototype.name);
            tbl.rawset("numParams", Double.valueOf(closure.prototype.numParams));
            tbl.rawset("isVararg",  closure.prototype.isVararg);

            if (closure.prototype.lines != null && closure.prototype.lines.length > 0) {
                tbl.rawset("line", Double.valueOf(closure.prototype.lines[0]));
            }
            String path = closure.prototype.filename;
            if (!Utils.isBlank(path)) {
                long lastModified = 0L;
                try { lastModified = Files.getLastModifiedTime(Path.of(path)).toMillis(); } catch (IOException ignored) {}
                if (lastModified != 0L) {
                    tbl.rawset("fileLastModified", Double.valueOf(lastModified));
                }
            }
            return tbl;
        }

        return null;
    }

    /** Returns metadata for any callable: Lua closure or Java function. */
    public static KahluaTable getCallableInfo(Object obj) {
        if (obj instanceof LuaClosure closure) {
            if (closure == null || closure.prototype == null)
                return null;
            var tbl = getClosureInfo(obj);
            if (tbl != null) tbl.rawset("kind", "lua");
            return tbl;
        }
        if (obj instanceof JavaFunction) {
            var tbl = LuaManager.platform.newTable();
            tbl.rawset("kind", "java");
            Class<?> c = obj.getClass();
            tbl.rawset("className", c.getName());
            tbl.rawset("simpleName", c.getSimpleName());
            tbl.rawset("name", obj.toString());
            LuaUtils.addInvokersInfo(tbl, obj);
            return tbl;
        }
        return null;
    }
}

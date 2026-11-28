package io.github.karl3uwu.zombud;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.karl3uwu.zombud.SteamWorkshop.WorkshopItemID;
import io.github.karl3uwu.zombud.session.GameBuild;
import io.github.karl3uwu.zombud.session.Session;
import io.github.karl3uwu.zombud.session.SessionFile;

/**
 * Session mode: a launcher wrote a session file ({@code doc/SessionFile.md}) and started the game with
 * {@code -Dkrl.session=<file>} or {@code KRL_SESSION=<file>}. The session decides the mod list and the Java jars;
 * the in-game Java loading path is off.
 *
 * <p>Everything fails open: any problem is logged, written to the stage file and the report, and the game runs
 * without Java mods.
 */
public final class SessionLoader {
    private SessionLoader() {}

    public static final String PROPERTY = "krl.session";
    public static final String ENV = "KRL_SESSION";

    /** Stages the loader writes itself; Lua may write only these. */
    public static final String STAGE_PREMAIN = "premain";
    public static final String STAGE_MODS_LOADED = "mods-loaded";
    public static final String STAGE_MAIN_MENU = "main-menu";
    public static final String STAGE_IN_GAME = "in-game";
    public static final String STAGE_QUIT = "quit";
    private static final Set<String> LUA_STAGES = Set.of(STAGE_MAIN_MENU, STAGE_IN_GAME);

    record LoadedJar(String modId, Path jar, String pkg, String sha256) {}

    /** The session file path from {@code -Dkrl.session} (wins) or {@code KRL_SESSION}; null when neither is set. */
    static Path requestedPath() {
        String p = System.getProperty(PROPERTY);
        if (Utils.isBlank(p)) p = System.getenv(ENV);
        return Utils.isBlank(p) ? null : Path.of(p.trim());
    }

    public static boolean isSessionMode() {
        return LoaderContext.current().sessionRequested;
    }

    /** The session in use, or null (none requested, or it couldn't be used). */
    public static Session session() {
        return LoaderContext.current().session;
    }

    public static String sessionError() {
        return LoaderContext.current().sessionError;
    }

    // ---- premain ---------------------------------------------------------------------------------------------

    /** Called from {@link Agent#premain} after arguments, policy and config are set up. Returns true in session mode. */
    static boolean premain(LoaderContext lc) {
        Path path = requestedPath();
        if (path == null) {
            return false;
        }
        lc.sessionRequested = true;
        lc.sessionPath = path;
        Logger.info("Session mode: " + path);

        Session s;
        try {
            s = SessionFile.read(path);
        } catch (SessionFile.UnsupportedSchemaException e) {
            fail(lc, "session file " + path + " uses an unsupported format: " + e.getMessage());
            return true;
        } catch (SessionFile.InvalidSessionException e) {
            fail(lc, "session file " + path + " is invalid: " + e.getMessage());
            return true;
        } catch (IOException | RuntimeException e) {
            fail(lc, "session file " + path + " can't be read: " + e);
            return true;
        }
        lc.session = s;
        lc.stageFile = Path.of(s.stageFile());
        lc.reportDir = Path.of(s.reportDir());
        stage(STAGE_PREMAIN);
        registerQuitStage();

        // Game build: read from Core.class bytes without loading it; else check once Core is up.
        lc.premainGameBuild = GameBuild.read(ClassLoader.getSystemClassLoader());
        if (lc.premainGameBuild != null && !lc.premainGameBuild.equals(s.gameBuild())) {
            fail(lc, "game build is " + lc.premainGameBuild + " but the session was prepared for " + s.gameBuild(),
                io.github.karl3uwu.zombud.PatchChecker.Kind.BUILD_MISMATCH);
            lc.session = s; // keep the mod list and Lua info; only Java mods are off
            return true;
        }
        if (lc.premainGameBuild == null) {
            Logger.warn("Session mode: could not read the game build at premain; it is checked once the game starts");
            io.github.karl3uwu.zombud.api.Callbacks.onGameInitComplete.register(SessionLoader::lateBuildCheck);
        }

        if (s.safeMode()) {
            Logger.info("Session mode: safe mode, Java mods are not loaded");
            lc.patchReport.add(sessionEntry("", "", io.github.karl3uwu.zombud.PatchChecker.Kind.SESSION_ERROR,
                "safe mode: Java mods are not loaded", PatchReport.Action.ALL_JAVA_MODS_OFF));
            return true;
        }

        loadJars(lc, s);
        return true;
    }

    private static void loadJars(LoaderContext lc, Session s) {
        ModApprovalsStore.FileData approvals = ModApprovalsStore.load();
        JarDecisionTable decisions = ModApprovals.buildJarDecisionTable(approvals.mods);

        List<WorkshopItemID> workshopIds = new ArrayList<>();
        for (Session.Mod m : s.mods()) {
            if (m.workshopId() != null && !m.java().isEmpty()) workshopIds.add(new WorkshopItemID(m.workshopId()));
        }
        ModApprovals.ZBSContext zbsCtx = ModApprovals.loadZBSContext(workshopIds);

        for (Session.Mod m : s.mods()) {
            for (Session.JavaJar j : m.java()) {
                if (isNeverLoaded(j.pkg())) {
                    if (ModIdentity.LEGACY_JAVA_PKG.equals(j.pkg())) {
                        skip(lc, m, j, PatchChecker.Kind.NOT_APPROVED,
                            "upstream ZombieBuddy is never loaded as a mod; remove it from the session");
                    } else {
                        Logger.info("Session mode: " + j.jar() + " is " + ModIdentity.DISPLAY_NAME + " itself (already running as the agent)");
                    }
                    continue;
                }
                Path jar = Path.of(j.jar());
                if (!Files.isRegularFile(jar)) {
                    skip(lc, m, j, io.github.karl3uwu.zombud.PatchChecker.Kind.JAR_MISSING, "jar not found");
                    continue;
                }
                String hash = Utils.sha256Hex(jar);
                if (hash == null || !hash.equalsIgnoreCase(j.sha256())) {
                    skip(lc, m, j, io.github.karl3uwu.zombud.PatchChecker.Kind.SHA256_MISMATCH,
                        "sha256 is " + hash + ", the session expects " + j.sha256() + " (the jar changed after the launcher checked it)");
                    continue;
                }
                WorkshopItemID wsId = m.workshopId() != null ? new WorkshopItemID(m.workshopId()) : null;
                ZBSVerifier.CheckResult zbs = ModApprovals.zbsCheck(jar, hash, wsId, zbsCtx);
                if (ModApprovals.zbsBlocked(zbs)) {
                    skip(lc, m, j, io.github.karl3uwu.zombud.PatchChecker.Kind.SIGNATURE_BLOCKED, zbs.blockReason());
                    continue;
                }
                boolean approved = Boolean.TRUE.equals(decisions.get(hash))
                    || ModApprovals.POLICY_ALLOW_ALL.equals(lc.jarPolicy)
                    || (zbs.flags().hasAll(ModFlags.MF_SIGNED, ModFlags.MF_VALID) && ModApprovals.isAuthorTrusted(zbs.sid()));
                if (!approved) {
                    skip(lc, m, j, io.github.karl3uwu.zombud.PatchChecker.Kind.NOT_APPROVED, "needs approval in the launcher");
                    continue;
                }

                Path canonical;
                try {
                    canonical = jar.toRealPath();
                } catch (IOException e) {
                    skip(lc, m, j, io.github.karl3uwu.zombud.PatchChecker.Kind.JAR_MISSING, "cannot resolve path: " + e);
                    continue;
                }
                lc.jarModIds.put(canonical, m.id());
                lc.jarLoadStatus.put(canonical, new Loader.JavaModLoadState(m.id(), canonical,
                    zbs.flags().with(ModFlags.MF_ACTIVE), "loaded (session)", hash, Boolean.TRUE));
                if (!JarLoader.loadJar(canonical, j.pkg(), hash, Loader.Phase.PREMAIN) && lc.disabledJars.contains(canonical)) {
                    continue; // PatchChecker turned it off; already in the report
                }
                if (!lc.disabledJars.contains(canonical)) {
                    lc.sessionJars.add(new LoadedJar(m.id(), canonical, j.pkg(), hash));
                }
            }
        }
        Logger.info("Session mode: " + lc.sessionJars.size() + " Java jar(s) loaded at premain");
    }

    /** Our own package (the agent) and upstream ZombieBuddy's are never loaded as mods, same as in-game. */
    public static boolean isNeverLoaded(String pkg) {
        return ModIdentity.isNeverLoadedAsMod(pkg);
    }

    private static void skip(LoaderContext lc, Session.Mod m, Session.JavaJar j, PatchChecker.Kind kind, String why) {
        Logger.error("Session mode: not loading " + j.jar() + " of " + m.id() + ": " + why);
        lc.patchReport.add(new PatchReport.Entry(m.id(), j.jar(), "", "", "", "", kind, why, false,
            PatchReport.Action.MOD_DISABLED));
    }

    private static PatchReport.Entry sessionEntry(String modId, String jar, PatchChecker.Kind kind, String message,
                                                  PatchReport.Action action) {
        return new PatchReport.Entry(modId, jar, "", "", "", "", kind, message, false, action);
    }

    private static void fail(LoaderContext lc, String message) {
        fail(lc, message, PatchChecker.Kind.SESSION_ERROR);
    }

    /** Session mode without Java mods: log, report, stage file. */
    private static void fail(LoaderContext lc, String message, PatchChecker.Kind kind) {
        lc.sessionError = message;
        Logger.error("Session mode: " + message + ". Starting without Java mods.");
        lc.patchReport.add(sessionEntry("", lc.sessionPath != null ? lc.sessionPath.toString() : "", kind, message,
            PatchReport.Action.ALL_JAVA_MODS_OFF));
        stageLine("error " + message.replace('\n', ' '));
    }

    /** Once Core is loaded, when the build couldn't be read at premain. Java mods are already in; report only. */
    private static void lateBuildCheck() {
        LoaderContext lc = LoaderContext.current();
        Session s = lc.session;
        if (s == null) return;
        try {
            Class<?> core = Class.forName("zombie.core.Core");
            Object inst = core.getMethod("getInstance").invoke(null);
            String build = String.valueOf(core.getMethod("getGameAndBuildVersion").invoke(inst));
            if (!build.equals(s.gameBuild())) {
                String msg = "game build is " + build + " but the session was prepared for " + s.gameBuild()
                    + "; Java mods were already loaded at startup. Restart from the launcher.";
                lc.sessionError = msg;
                Logger.error("Session mode: " + msg);
                lc.patchReport.add(sessionEntry("", "", PatchChecker.Kind.BUILD_MISMATCH, msg, PatchReport.Action.WARNING));
                stageLine("error " + msg);
                Watermark.setMidLine("Game build changed since the launcher prepared this session. Restart from the launcher.");
                lc.patchReport.publish();
            }
        } catch (Throwable t) {
            Logger.warn("Session mode: late game build check failed: " + t);
        }
    }

    // ---- game hooks ------------------------------------------------------------------------------------------

    /**
     * The mod list the game should load: in session mode the session's list (in memory only, nothing is written),
     * except on a multiplayer client or server, where the server decides. Called from the
     * {@code ZomboidFileSystem.loadMods(List)} patch.
     */
    static List<String> modListFor(List<String> gameList) {
        LoaderContext lc = LoaderContext.current();
        Session s = lc.session;
        if (s == null || isMultiplayer()) {
            return gameList;
        }
        List<String> ids = new ArrayList<>(s.modIds());
        Logger.info("Session mode: mod list from the session (" + ids.size() + " mods) instead of the game's " + gameList.size());
        return ids;
    }

    private static boolean isMultiplayer() {
        try {
            return zombie.network.GameClient.client || zombie.network.GameServer.server;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Replaces the in-game Java loading path in session mode: run each premain-loaded jar's Main. */
    static void onLoadMods() {
        LoaderContext lc = LoaderContext.current();
        for (LoadedJar j : lc.sessionJars) {
            JarLoader.loadJar(j.jar(), j.pkg(), j.sha256(), Loader.Phase.MAIN);
        }
    }

    /** After {@code ZomboidFileSystem.loadMods(List)} returned. */
    static void afterLoadMods() {
        stage(STAGE_MODS_LOADED);
        LoaderContext.current().patchReport.publish();
    }

    // ---- stage file ------------------------------------------------------------------------------------------

    /** Lua ({@code KRLZomBud.markSessionStage}): only main-menu and in-game. */
    public static void markStageFromLua(String stage) {
        if (stage != null && LUA_STAGES.contains(stage)) {
            stage(stage);
        }
    }

    /** Appends {@code <ISO time> <stage>}; premain and mods-loaded only once per run. */
    static void stage(String stage) {
        LoaderContext lc = LoaderContext.current();
        if ((STAGE_PREMAIN.equals(stage) || STAGE_MODS_LOADED.equals(stage)) && !lc.stagesWritten.add(stage)) {
            return;
        }
        stageLine(stage);
    }

    private static synchronized void stageLine(String text) {
        Path f = LoaderContext.current().stageFile;
        if (f == null) return;
        String line = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS) + " " + text + System.lineSeparator();
        try {
            Path parent = f.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(f, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            Logger.warn("Session mode: cannot write stage file " + f + ": " + e);
        }
    }

    /** JVM shutdown hook: runs on a normal exit (also System.exit and Ctrl+C), not on a crash or kill. */
    private static void registerQuitStage() {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> stageLine(STAGE_QUIT), "krl-session-quit"));
        } catch (IllegalStateException | SecurityException e) {
            Logger.warn("Session mode: cannot register quit stage: " + e);
        }
    }
}

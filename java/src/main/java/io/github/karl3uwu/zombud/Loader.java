package io.github.karl3uwu.zombud;

import static io.github.karl3uwu.zombud.ModFlags.MF_ACTIVE;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.List;

import io.github.karl3uwu.zombud.api.Callbacks;

/**
 * Entry points of the loader, kept stable for the agent, the bundled patches and Lua. The work happens in
 * {@link ModApprovals} (policy and approvals), {@link JarLoader} (transform and load a jar), {@link ModListLoader}
 * (the in-game mod list) and {@link PreloadMods}; state lives in {@link LoaderContext}.
 */
public class Loader {
    private Loader() {}

    enum Phase {
        PREMAIN,
        MAIN
    }

    static final String BUNDLED_PATCHES_JAR = JarLoader.BUNDLED_PATCHES_JAR;
    static final String BUNDLED_EXPERIMENTAL_JAR = JarLoader.BUNDLED_EXPERIMENTAL_JAR;

    static {
        Callbacks.onDisplayCreate.register(ModApprovals::checkShiftKey);
    }

    /**
     * Per-modId load/decision snapshot captured at loadMods() time so Lua
     * (and other callers) can display "loaded / blocked" next to each mod in
     * the UI. Keyed by modId; for multi-dir B42 mods the version-dir entry
     * (which typically carries the JAR) overwrites the common-dir one.
     */
    public static record JavaModLoadState (
            String id,
            Path jarPath,
            ModFlags flags,
            String reason,      // "loaded" or a short skip reason
            String sha256,      // JAR sha256, null if no JAR
            Boolean decision    // true = allow, false = deny, null = undecided
    ) {}

    /** Read-only snapshot of the jars active in this run. */
    public static List<JavaModLoadState> getActiveJavaMods() {
        return LoaderContext.current().jarLoadStatus.values().stream()
            .filter(s -> s.flags().has(MF_ACTIVE))
            .toList();
    }

    public static Instrumentation getInstrumentation() {
        return LoaderContext.current().instrumentation;
    }

    // ---- agent (premain) -----------------------------------------------------------------------------------

    /** Called from {@link Agent#premain} to load config from disk before any mod loading. */
    static void initConfig() {
        LoaderContext lc = LoaderContext.current();
        lc.config = Config.load();
        lc.configDirty = false;
    }

    static void setPolicy(String value) {
        ModApprovals.setPolicy(value);
    }

    static String getPolicy() {
        return ModApprovals.getPolicy();
    }

    static void configureApprovalFrontend(String value) {
        ModApprovals.configureApprovalFrontend(value);
    }

    static void preloadMods() {
        PreloadMods.loadAll();
    }

    static boolean loadJar(Path jarPath, String packageName, String approvedHash, Phase phase) {
        return JarLoader.loadJar(jarPath, packageName, approvedHash, phase);
    }

    static boolean loadResourceJar(String resourceName, String packageName, Phase phase) {
        return JarLoader.loadResourceJar(resourceName, packageName, phase);
    }

    static byte[] getTransformedClassBytes(String className) {
        return JarLoader.getTransformedClassBytes(className);
    }

    // ---- game hooks (Patch_ZomboidFileSystem, Patch_GameWindow) ---------------------------------------------

    public static void maybeReorderMods(List<String> mods) {
        ModListLoader.maybeReorderMods(mods);
    }

    public static void loadMods(List<String> mods) {
        ModListLoader.loadMods(mods);
    }

    public static void onEnterLoadMods(String activeMods) {
        ModListLoader.onEnterLoadMods(activeMods);
    }

    public static void onExitLoadMods(String activeMods) {
        ModListLoader.onExitLoadMods(activeMods);
    }

    // ---- settings (Lua options) ----------------------------------------------------------------------------

    public static void setAutoFixModOrder(boolean value) {
        LoaderContext lc = LoaderContext.current();
        lc.replaceAndSaveConfig(lc.config.withAutoFixModOrder(value));
    }

    public static boolean fixApprovalDialogCursor() {
        return LoaderContext.current().config.fix_approval_dialog_cursor();
    }

    public static void setFixApprovalDialogCursor(boolean value) {
        LoaderContext lc = LoaderContext.current();
        lc.replaceAndSaveConfig(lc.config.withFixApprovalDialogCursor(value));
    }

    public static boolean fixMacOSRetina() {
        return LoaderContext.current().config.fix_macos_retina();
    }

    public static void setFixMacOSRetina(boolean value) {
        LoaderContext lc = LoaderContext.current();
        lc.replaceAndSaveConfig(lc.config.withFixMacOSRetina(value));
    }
}

package io.github.karl3uwu.zombud;

import static io.github.karl3uwu.zombud.ModFlags.MF_ACTIVE;
import static io.github.karl3uwu.zombud.ModFlags.MF_PRELOAD;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.karl3uwu.zombud.SteamWorkshop.WorkshopItemID;

/**
 * Mods approved for early loading ({@code javaPreload=true} plus {@code KRL-Preload: true} in the jar manifest).
 * The list lives in the config; {@link #loadAll} loads them during agent {@code premain}.
 */
final class PreloadMods {
    private PreloadMods() {}

    static final String MANIFEST_ATTR = "KRL-Preload";

    static void add(String id, Path infPath, Path jarPath) {
        LoaderContext lc = LoaderContext.current();
        if (lc.replaceConfig(lc.config.withPreloadMod(id, new Config.PreloadMod(infPath, jarPath)))) {
            Watermark.setMidLine("Preload mod \"" + id + "\" added/updated. Restart recommended.");
        }
    }

    static void remove(String id) {
        LoaderContext lc = LoaderContext.current();
        lc.replaceConfig(lc.config.withoutPreloadMod(id));
    }

    static List<String> getIds() {
        return new ArrayList<>(LoaderContext.current().config.preload_mods().keySet());
    }

    /**
     * Returns true when the JAR's {@code META-INF/MANIFEST.MF} contains {@code KRL-Preload: true}.
     * Callers must verify the JAR's ZBS signature before trusting this value.
     */
    static boolean hasManifestPreload(Path jarPath) {
        if (jarPath == null || !Files.isRegularFile(jarPath)) {
            return false;
        }
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jarPath.toFile(), false)) {
            java.util.jar.Manifest mf = jf.getManifest();
            if (mf == null) {
                return false;
            }
            String val = mf.getMainAttributes().getValue(MANIFEST_ATTR);
            return "true".equalsIgnoreCase(val != null ? val.trim() : null);
        } catch (Exception e) {
            return false;
        }
    }

    /** Loads every preload mod from the config (agent {@code premain}). */
    static void loadAll() {
        LoaderContext lc = LoaderContext.current();
        if (lc.config.preload_mods().isEmpty()) {
            return;
        }

        // First pass: validate and collect entries
        record PreloadEntry(String id, String javaPkgName, Path canJarPath, String hash, WorkshopItemID workshopItemId) {}
        List<PreloadEntry> entries = new ArrayList<>();

        for (Map.Entry<String, Config.PreloadMod> e : lc.config.preload_mods().entrySet()) {
            String id = e.getKey();
            Config.PreloadMod preloadMod = e.getValue();
            String javaPkgName = JavaModInfo.javaPkgNameFrom(preloadMod.infPath());
            if (Utils.isBlank(javaPkgName)) {
                Logger.warn("Preload mod '" + id + "': cannot read javaPkgName from " + preloadMod.infPath() + "; removing.");
                remove(id);
                continue;
            }
            try {
                Path jarCanonicalPath = preloadMod.jarPath().toRealPath();
                Logger.info("Preloading pkg '" + javaPkgName + "' from " + jarCanonicalPath);
                if (!Files.isRegularFile(jarCanonicalPath)) {
                    Logger.warn("Preload pkg '" + javaPkgName + "': JAR not found at " + jarCanonicalPath + "; removing.");
                    remove(id);
                    continue;
                }
                if (!hasManifestPreload(jarCanonicalPath)) {
                    Logger.warn("Preload pkg '" + javaPkgName + "': JAR manifest missing '" + MANIFEST_ATTR + "' entry in " + jarCanonicalPath + "; removing.");
                    remove(id);
                    continue;
                }
                entries.add(new PreloadEntry(id, javaPkgName, jarCanonicalPath, Utils.sha256Hex(jarCanonicalPath), JavaModInfo.workshopItemIdFromInfPath(preloadMod.infPath())));
            } catch (IOException ex) {
                Logger.warn("Preload pkg '" + javaPkgName + "': cannot resolve JAR path " + preloadMod.jarPath() + "; removing.");
                remove(id);
            }
        }
        if (entries.isEmpty()) return;

        ModApprovals.ZBSContext zbsCtx = ModApprovals.loadZBSContext(entries.stream().map(entry -> entry.workshopItemId()).toList());

        // Second pass: ZBS check and load
        for (PreloadEntry entry : entries) {
            ZBSVerifier.CheckResult zbsResult = ModApprovals.zbsCheck(entry.canJarPath(), entry.hash(), entry.workshopItemId(), zbsCtx);
            if (ModApprovals.zbsBlocked(zbsResult)) {
                Logger.warn("Preload pkg '" + entry.javaPkgName() + "': ZBS check failed (" + zbsResult.blockReason() + "); skipping.");
                remove(entry.id());
                continue;
            }
            lc.jarModIds.put(entry.canJarPath(), entry.id());
            JarLoader.loadJar(entry.canJarPath(), entry.javaPkgName(), entry.hash(), Loader.Phase.PREMAIN);
            if (lc.disabledJars.contains(entry.canJarPath())) {
                continue; // status already set to PATCH_FAILED by JarLoader
            }
            lc.jarLoadStatus.put(entry.canJarPath(), new Loader.JavaModLoadState(
                        entry.id(),
                        entry.canJarPath(),
                        zbsResult.flags().with(MF_PRELOAD).with(MF_ACTIVE),
                        "preloaded",
                        entry.hash(),
                        null
                        ));
        }
        lc.saveConfigIfDirty();
    }
}

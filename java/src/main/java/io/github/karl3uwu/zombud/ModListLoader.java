package io.github.karl3uwu.zombud;

import static io.github.karl3uwu.zombud.ModFlags.MF_ACTIVE;
import static io.github.karl3uwu.zombud.ModFlags.MF_NONE;
import static io.github.karl3uwu.zombud.ModFlags.MF_PERSIST;
import static io.github.karl3uwu.zombud.ModFlags.MF_PRELOAD;
import static io.github.karl3uwu.zombud.ModFlags.MF_SIGNED;
import static io.github.karl3uwu.zombud.ModFlags.MF_TRUST_AUTHOR;
import static io.github.karl3uwu.zombud.ModFlags.MF_VALID;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.karl3uwu.zombud.SteamWorkshop.SteamID64;
import io.github.karl3uwu.zombud.SteamWorkshop.WorkshopItemID;
import io.github.karl3uwu.zombud.api.Reflect;
import zombie.GameWindow;
import zombie.gameStates.ChooseGameInfo;

/**
 * The in-game mod list path ({@code ZomboidFileSystem.loadMods}): pinned mod order, finding Java mods in the
 * active mod list, approval prompts, and loading the approved jars.
 */
final class ModListLoader {
    private ModListLoader() {}

    private static final List<String> PINNED_MOD_ORDER = List.of(
        ModIdentity.MOD_ID,
        ModIdentity.LEGACY_MOD_ID,
        "zdk",
        "ZModUnbork"
    );

    /** Our own mod.info (loaded as the agent) and upstream ZombieBuddy's are never loaded as ordinary mods. */
    static boolean isSelfOrUpstreamPkg(String javaPkgName, String myPackageName) {
        return javaPkgName != null
            && (javaPkgName.equals(myPackageName) || javaPkgName.equals(ModIdentity.LEGACY_JAVA_PKG));
    }

    static void onEnterLoadMods(String activeMods) {
        LoaderContext.current().currModSetID = activeMods;
    }

    static void onExitLoadMods(String activeMods) {
        LoaderContext lc = LoaderContext.current();
        lc.prevModSetID = lc.currModSetID;
        lc.prevModSet = lc.currModSet;
        lc.currModSetID = null;
        lc.currModSet = null;
    }

    static void maybeReorderMods(List<String> mods) {
        if (!LoaderContext.current().config.auto_fix_mod_order()) return;
        if (Utils.isBlank(mods)) return;

        Map<String, Integer> before = pinnedModIndices(mods);
        int targetIndex = 0;
        for (String modId : before.keySet()) {
            moveModToIndex(mods, modId, targetIndex);
            targetIndex++;
        }
        for (Map.Entry<String, Integer> entry : before.entrySet()) {
            String modId = entry.getKey();
            int oldIndex = entry.getValue();
            int newIndex = mods.indexOf(modId);
            if (newIndex != oldIndex) {
                Logger.info("Auto-fix mod order: " + oldIndex + " -> " + newIndex + " " + modId);
            }
        }
    }

    private static Map<String, Integer> pinnedModIndices(List<String> mods) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String modId : PINNED_MOD_ORDER) {
            int index = mods.indexOf(modId);
            if (index >= 0) {
                out.put(modId, index);
            }
        }
        return out;
    }

    private static void moveModToIndex(List<String> mods, String modId, int index) {
        int oldIndex = mods.indexOf(modId);
        if (oldIndex < 0) return;
        mods.remove(oldIndex);
        mods.add(Math.min(index, mods.size()), modId);
    }

    static void loadMods(List<String> mods) {
        LoaderContext lc = LoaderContext.current();
        ArrayList<JavaModInfo> jModInfos = new ArrayList<>();
        ArrayList<String> jModIds = new ArrayList<>();
        HashSet<String> processedIds = new HashSet<>();

        lc.currModSet = new HashSet<>(mods);
        if ("default".equals(lc.currModSetID) && "default".equals(lc.prevModSetID) && lc.currModSet != null && lc.prevModSet != null) {
            for (String mod_id : PreloadMods.getIds()) {
                if (lc.prevModSet.contains(mod_id) && !lc.currModSet.contains(mod_id)) {
                    Logger.info("Preload mod '" + mod_id + "' was removed from the default mod list; removing from preload.");
                    PreloadMods.remove(mod_id);
                    Watermark.setMidLine("Preload mod \"" + mod_id + "\" disabled. Restart the game to deactivate it.");
                }
            }
        }

        ArrayList<String> mergedIds = new ArrayList<>();
        mergedIds.addAll(PreloadMods.getIds()); // inject preloaded mod ids BEFORE actual mod list
        mergedIds.addAll(mods);

        Boolean isB42 = null;
        for (String mod_id : mergedIds) {
            if (Utils.isBlank(mod_id)) continue;
            if (processedIds.contains(mod_id)) continue;
            processedIds.add(mod_id);

            var mod = ChooseGameInfo.getAvailableModDetails(mod_id);
            if (mod == null) continue;

            if (isB42 == null) {
                Reflect r = Reflect.on(mod);
                isB42 = (r.getMethodHandle(String.class, "getVersionDir") != null)
                     && (r.getMethodHandle(String.class, "getCommonDir")  != null);
            }

            JavaModInfo jModInfo = isB42
                ? Utils.firstNonNull(
                        () -> JavaModInfo.parse( mod.getVersionDir() ),
                        () -> JavaModInfo.parse( mod.getCommonDir() ),
                        () -> JavaModInfo.parseMerged( mod.getCommonDir(), mod.getVersionDir() )
                        )
                : JavaModInfo.parse(mod.getDir()); // B41

            if (jModInfo != null) {
                jModInfos.add(jModInfo);
                jModIds.add(mod_id);
            }
        }

        Logger.info("java mod list to load:");
        JarLoader.printModList(jModInfos);

        // Find the last occurrence index for each package name
        Map<String, Integer> lastPkgNameIndex = new HashMap<>();
        for (int i = 0; i < jModInfos.size(); i++) {
            JavaModInfo jModInfo = jModInfos.get(i);
            lastPkgNameIndex.put(jModInfo.javaPkgName(), i);
        }

        // Process the list to determine which mods should be skipped
        String myPackageName = Loader.class.getPackage().getName();
        ArrayList<Boolean> shouldSkipList = new ArrayList<>();
        ArrayList<String> skipReasons = new ArrayList<>();
        ModApprovalsStore.FileData fileData = ModApprovalsStore.load();
        // Store entries for persistence and build lookup table
        lc.storedEntries = new ArrayList<>(fileData.mods);
        lc.modApprovalsDirty = false;
        List<ModApprovalsStore.ModEntry> storedEntriesBefore = ModApprovals.copyStoredEntries(lc.storedEntries);
        JarDecisionTable approvals = ModApprovals.buildJarDecisionTable(lc.storedEntries);
        if (!lc.forceApprovalDialog) {
            ModApprovals.checkShiftKey();
        }
        if (!lc.forceApprovalDialog) {
            ModApprovals.applySessionDecisions(approvals);
        }
        if (lc.forceApprovalDialog) {
            Logger.info("Shift held during game load; forcing Java mod approval dialog.");
        }
        // Structural-only skip flags (must match the policy loop below) — used to batch all PROMPT dialogs.
        ArrayList<Boolean> structuralOnlySkip = new ArrayList<>();
        for (int i = 0; i < jModInfos.size(); i++) {
            JavaModInfo jModInfo = jModInfos.get(i);
            boolean stSkip = false;
            if (isSelfOrUpstreamPkg(jModInfo.javaPkgName(), myPackageName)) {
                stSkip = true;
            }
            Integer lastIdx = lastPkgNameIndex.get(jModInfo.javaPkgName());
            if (lastIdx != null && lastIdx > i) {
                stSkip = true;
            }
            structuralOnlySkip.add(stSkip);
        }
        ModApprovals.ZBSContext zbsCtx = ModApprovals.loadZBSContext(jModInfos.stream().map(JavaModInfo::getWorkshopItemID).toList());
        Map<WorkshopItemID, SteamWorkshop.ItemDetails> workshopDetailsById = zbsCtx.workshopDetailsById();
        Map<SteamID64, KnownAuthors.AuthorEntry> knownAuthorsBySteamId = zbsCtx.knownAuthorsBySteamId();

        // Pre-compute per-mod context to avoid duplicate work in prompt and load loops
        record ModCtx(String modId, Path jarPath, String hash, WorkshopItemID workshopItemId,
                      SteamWorkshop.ItemDetails workshopDetails, SteamWorkshop.BanInfo banInfo, boolean steamBanned,
                      ZBSVerifier.CheckResult zbsResult) {}
        List<ModCtx> modContexts = new ArrayList<>();

        for (int i = 0; i < jModInfos.size(); i++) {
            JavaModInfo jModInfo = jModInfos.get(i);
            String modId = i < jModIds.size() ? jModIds.get(i) : jModInfo.javaPkgName();
            Path jarPath = jModInfo.jarPath();
            String hash = Utils.sha256Hex(jarPath);
            WorkshopItemID workshopItemId = jModInfo.getWorkshopItemID();
            SteamWorkshop.ItemDetails workshopDetails = workshopItemId != null ? workshopDetailsById.get(workshopItemId) : null;
            SteamWorkshop.BanInfo banInfo = workshopItemId == null
                ? new SteamWorkshop.BanInfo(null, "Workshop id not found in mod path.")
                : (workshopDetails != null ? workshopDetails.ban() : null);
            boolean steamBanned = banInfo != null && Boolean.TRUE.equals(banInfo.status());
            modContexts.add(new ModCtx(modId, jarPath, hash, workshopItemId, workshopDetails, banInfo, steamBanned,
                ModApprovals.zbsCheck(jarPath, hash, workshopItemId, zbsCtx)));
        }
        for (ModCtx ctx : modContexts) {
            ModApprovals.untrustAuthorForBannedMod(
                ctx.modId,
                ctx.jarPath,
                ctx.hash,
                ctx.workshopItemId,
                ctx.steamBanned,
                workshopDetailsById
            );
        }

        if (ModApprovals.POLICY_PROMPT.equals(lc.jarPolicy)) {
            // Late Shift re-check: on first boot the display window may not exist when
            // checkShiftKey() runs above (before workshop/ZBS work). By the time we reach
            // here the window is more likely to be ready, so try once more.
            if (!lc.forceApprovalDialog) {
                ModApprovals.checkShiftKey();
                if (lc.forceApprovalDialog) {
                    Logger.info("Shift held during game load (late detection); forcing Java mod approval dialog.");
                }
            }
            ArrayList<JarBatchApprovalProtocol.Entry> batchEntries = new ArrayList<>();
            for (int i = 0; i < jModInfos.size(); i++) {
                if (structuralOnlySkip.get(i)) continue;

                ModCtx ctx = modContexts.get(i);
                if (ctx.hash == null) continue;

                JavaModInfo jModInfo = jModInfos.get(i);
                Date date = null;
                if (ctx.jarPath != null) {
                    try { date = new Date(Files.getLastModifiedTime(ctx.jarPath).toMillis()); } catch (IOException ignored) {}
                }
                String modDisplay = jModInfo.displayName();
                if (modDisplay == null || modDisplay.trim().isEmpty()) {
                    modDisplay = ctx.modId != null ? ctx.modId : "";
                }
                JarBatchApprovalProtocol.Entry.SteamBan steamBan = ctx.steamBanned
                    ? new JarBatchApprovalProtocol.Entry.SteamBan(ctx.banInfo != null ? ctx.banInfo.reason() : "")
                    : null;
                SteamID64 authorID = ctx.zbsResult.sid();
                ModFlags flags = ctx.zbsResult.flags();
                JarBatchApprovalProtocol.Entry.ZBSignature zbs = new JarBatchApprovalProtocol.Entry.ZBSignature(
                    flags.hasAll(MF_SIGNED, MF_VALID),
                    authorID,
                    flags.hasAll(MF_SIGNED, MF_VALID) ? ModApprovals.authorDisplayName(authorID, knownAuthorsBySteamId) : ctx.zbsResult.notice()
                );
                Boolean decision = ModApprovals.lookupJarDecision(approvals, ctx.hash);
                if (decision == null) {
                    // When force-dialog skips applySessionDecisions, still surface session decisions
                    // so the user sees their earlier choices pre-filled in the dialog.
                    decision = lc.sessionJarDecisions.get(ctx.hash);
                }
                if (!lc.forceApprovalDialog && !ctx.steamBanned && zbs.valid() && ModApprovals.isAuthorTrusted(authorID)) {
                    // Auto-approve for this session only — trusted-author approvals are not persisted
                    // because the trust comes from the author record, not the specific JAR hash.
                    approvals.put(ctx.hash, Boolean.TRUE);
                    if (jModInfo.javaPreload() && PreloadMods.hasManifestPreload(ctx.jarPath)) {
                        PreloadMods.add(ctx.modId, jModInfo.infPath(), ctx.jarPath);
                    }
                    continue;
                }
                if (!lc.forceApprovalDialog && !ctx.steamBanned) {
                    if (decision != null) continue;
                }
                ModFlags entryFlags = new ModFlags(ModApprovals.isDecisionStored(ctx.hash, decision) ? MF_PERSIST : MF_NONE);
                if (zbs.valid() && authorID != null && ModApprovals.isAuthorTrusted(authorID) && !ctx.steamBanned) {
                    entryFlags = entryFlags.with(MF_TRUST_AUTHOR);
                }
                final boolean bHasManifestPreload = PreloadMods.hasManifestPreload(ctx.jarPath);
                if (jModInfo.javaPreload() && zbs.valid() && bHasManifestPreload) {
                    entryFlags = entryFlags.with(MF_PRELOAD);
                } else if (jModInfo.javaPreload() || bHasManifestPreload) {
                    Logger.warn(ctx.modId + ": javaPreload=" + jModInfo.javaPreload() + ", hasManifestPreload=" + bHasManifestPreload
                        + ", zbs.valid()=" + zbs.valid());
                }
                batchEntries.add(new JarBatchApprovalProtocol.Entry(
                    ctx.modId,
                    ctx.workshopItemId,
                    ctx.jarPath,
                    jModInfo.infPath(),
                    ctx.hash,
                    date,
                    decision,
                    entryFlags,
                    modDisplay,
                    zbs,
                    steamBan
                ));
            }
            if (!batchEntries.isEmpty()) {
                if (lc.hasDoLoadingText) {
                    GameWindow.DoLoadingText("Waiting for Java mods approval…");
                }
                List<JarBatchApprovalProtocol.Entry> decided = batchEntries;
                try {
                    decided = ModApprovals.approvalFrontend().approvePendingMods(batchEntries);
                } finally {
                    if (lc.hasDoLoadingText) {
                        GameWindow.DoLoadingText("Loading Mods");
                    }
                }
                ModApprovals.applyBatchApprovalLines(decided, approvals);
                lc.forceApprovalDialog = false; // reset after one batch, can be re-triggered by holding Shift on next load
            }
        }

        for (int i = 0; i < jModInfos.size(); i++) {
            JavaModInfo jModInfo = jModInfos.get(i);
            ModCtx ctx = modContexts.get(i);
            boolean shouldSkip = false;
            String skipReason = "";

            // Skip ourselves - we're loaded as a Java agent, not through normal mod loading.
            // Upstream ZombieBuddy's mod (javaPkgName me.zed_0xff.zombie_buddy) lands here too: its jar is never loaded.
            if (isSelfOrUpstreamPkg(jModInfo.javaPkgName(), myPackageName)) {
                shouldSkip = true;
                skipReason = " (loaded as Java agent, skipping normal mod loading)" + SelfUpdater.getExclusionReasonSuffix(ctx.jarPath);
                if (ModIdentity.isLegacyModId(ctx.modId) || ModIdentity.LEGACY_JAVA_PKG.equals(jModInfo.javaPkgName())) {
                    skipReason = " (upstream ZombieBuddy mod: its Java code is not loaded, " + ModIdentity.DISPLAY_NAME + " is the active agent)";
                    Logger.warn("Upstream ZombieBuddy mod is enabled together with " + ModIdentity.DISPLAY_NAME
                        + ". Its Java code is not loaded. " + ModIdentity.DISPLAY_NAME + " is a separate fork: mods must"
                        + " require " + ModIdentity.MOD_ID + ". Disable or unsubscribe ZombieBuddy.");
                }
            }

            // Check if this mod's package name appears in a later mod
            Integer lastIndex = lastPkgNameIndex.get(jModInfo.javaPkgName());
            if (lastIndex != null && lastIndex > i) {
                shouldSkip = true;
                skipReason = " (package " + jModInfo.javaPkgName() + " is overridden by later mod)";
            }

            if (!shouldSkip && ctx.steamBanned) {
                shouldSkip = true;
                skipReason = " (Steam Workshop mod is banned" + (!Utils.isBlank(ctx.banInfo.reason()) ? ": " + ctx.banInfo.reason() : "") + ", modId=" + ctx.modId + ")";
            }

            ModFlags flags = ctx.zbsResult == ZBSVerifier.CheckResult.DISABLED ? ModFlags.EMPTY : ctx.zbsResult.flags();
            if (!shouldSkip && ModApprovals.zbsBlocked(ctx.zbsResult)) {
                shouldSkip = true;
                skipReason = " (" + ctx.zbsResult.blockReason() + ", modId=" + ctx.modId + ")";
            }

            if (flags.hasAll(MF_VALID, MF_SIGNED) && ModApprovals.isAuthorTrusted(ctx.zbsResult.uploaderID())) {
                flags = flags.with(MF_TRUST_AUTHOR);
            }

            // Enforce Java JAR policy for new/changed binaries.
            if (!shouldSkip && !ModApprovals.isJarAllowedByPolicy(ctx.modId, jModInfo, approvals, ctx.hash)) {
                shouldSkip = true;
                skipReason = " (blocked by policy=" + lc.jarPolicy + ", modId=" + ctx.modId + ")";
            }

            Path canonicalJarPath = null;
            if (ctx.jarPath != null) {
                try {
                    canonicalJarPath = ctx.jarPath.toRealPath();
                } catch (IOException e) {
                    Logger.error("Cannot resolve canonical path for " + ctx.jarPath + ": " + e);
                    shouldSkip = true;
                    skipReason = " (cannot resolve JAR path)";
                }
            }

            shouldSkipList.add(shouldSkip);
            skipReasons.add(skipReason);

            if (canonicalJarPath != null) {
                Boolean decision = null;
                if (ctx.hash != null) {
                    decision = approvals.get(ctx.hash);
                    if (ModApprovals.isDecisionStored(ctx.hash, decision)) {
                        flags = flags.with(MF_PERSIST);
                    }
                }
                if (!shouldSkip) {
                    flags = flags.with(MF_ACTIVE);
                }

                // merge in "sticky" flags like MF_ACTIVE or MF_PRELOAD
                Loader.JavaModLoadState prev = lc.jarLoadStatus.get(canonicalJarPath);
                if (prev != null) {
                    flags = flags.merge(prev.flags().getSticky());
                }

                lc.jarLoadStatus.put(canonicalJarPath, new Loader.JavaModLoadState(
                    ctx.modId,
                    canonicalJarPath,
                    flags,
                    shouldSkip ? skipReason.trim() : "loaded",
                    ctx.hash,
                    decision
                ));
            }
        }

        // Save if anything changed
        if (lc.modApprovalsDirty
            && (!storedEntriesBefore.equals(lc.storedEntries)
                || lc.storedEntries.size() != storedEntriesBefore.size())) {
            ModApprovalsStore.FileData dataToSave = new ModApprovalsStore.FileData();
            dataToSave.mods = new ArrayList<>(lc.storedEntries);
            ModApprovalsStore.save(dataToSave);
        }
        lc.saveConfigIfDirty();

        if (!shouldSkipList.isEmpty()) {
            // Print excluded mods first
            for (int i = 0; i < jModInfos.size(); i++) {
                if (shouldSkipList.get(i)) {
                    JavaModInfo jModInfo = jModInfos.get(i);
                    String reason = i < skipReasons.size() ? skipReasons.get(i) : "";
                    Logger.info("Excluded: " + jModInfo.infPath().toAbsolutePath() + reason);
                }
            }

            // Build list of mods that will be loaded
            ArrayList<JavaModInfo> modsToLoad = new ArrayList<>();
            for (int i = 0; i < jModInfos.size(); i++) {
                if (!shouldSkipList.get(i)) {
                    modsToLoad.add(jModInfos.get(i));
                }
            }
            Logger.info("java mod list after processing:");
            JarLoader.printModList(modsToLoad);
        }

        // Load only the mods that should be loaded
        for (int i = 0; i < jModInfos.size(); i++) {
            if (!shouldSkipList.get(i)) {
                JarLoader.loadJar(jModInfos.get(i).jarPath(), jModInfos.get(i).javaPkgName(), modContexts.get(i).hash, Loader.Phase.MAIN);
            }
        }
    }
}

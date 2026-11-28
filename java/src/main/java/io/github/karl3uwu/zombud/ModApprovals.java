package io.github.karl3uwu.zombud;

import static io.github.karl3uwu.zombud.ModFlags.MF_PERSIST;
import static io.github.karl3uwu.zombud.ModFlags.MF_PRELOAD;
import static io.github.karl3uwu.zombud.ModFlags.MF_TRUST_AUTHOR;
import static io.github.karl3uwu.zombud.ModFlags.MF_VALID;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.glfw.GLFW;
import org.lwjglx.opengl.Display;

import io.github.karl3uwu.zombud.SteamWorkshop.SteamID64;
import io.github.karl3uwu.zombud.SteamWorkshop.WorkshopItemID;
import io.github.karl3uwu.zombud.frontend.ModApprovalFrontend;
import io.github.karl3uwu.zombud.frontend.ModApprovalFrontends;

/**
 * Jar policy, ZBS signature checks, stored and session approval decisions, trusted authors and the approval
 * frontend. State lives in {@link LoaderContext}.
 */
final class ModApprovals {
    private ModApprovals() {}

    static final String POLICY_PROMPT       = "prompt";
    static final String POLICY_DENY_NEW     = "deny-new";
    static final String POLICY_ALLOW_ALL    = "allow-all";
    static final Set<String> VALID_POLICIES = Set.of(POLICY_PROMPT, POLICY_DENY_NEW, POLICY_ALLOW_ALL);

    // ---- policy --------------------------------------------------------------------------------------------

    /**
     * Write-once policy: must be set during Agent.premain, before any other Java mod is on the classpath.
     * Later attempts are logged and ignored, so a later-loading Java mod cannot switch to allow-all.
     * (Reflection/bytecode redefinition can still bypass this: same threat model as the approvals file.)
     */
    static void setPolicy(String value) {
        LoaderContext lc = LoaderContext.current();
        synchronized (lc) {
            if (lc.jarPolicyLocked) {
                Logger.warn("Ignoring attempt to change policy (already locked at '" + lc.jarPolicy + "')");
                return;
            }
            String v = value == null ? "" : value.trim().toLowerCase();
            if (!VALID_POLICIES.contains(v)) {
                Logger.warn("Invalid policy '" + value + "', keeping '" + lc.jarPolicy + "'. Valid: " + VALID_POLICIES);
                return;
            }
            lc.jarPolicy = v;
            lc.jarPolicyLocked = true;
            Logger.info("Policy set to '" + lc.jarPolicy + "' (locked)");
        }
    }

    static String getPolicy() {
        return LoaderContext.current().jarPolicy;
    }

    /** ZBS verification applies for every policy except {@code allow-all}. */
    private static boolean zbsSignatureChecksEnabled() {
        return !POLICY_ALLOW_ALL.equals(getPolicy());
    }

    // ---- ZBS -----------------------------------------------------------------------------------------------

    record ZBSContext(
        Map<WorkshopItemID, SteamWorkshop.ItemDetails> workshopDetailsById,
        Map<SteamID64, KnownAuthors.AuthorEntry> knownAuthorsBySteamId
    ) {}

    static ZBSContext loadZBSContext(Iterable<WorkshopItemID> workshopIds) {
        Set<WorkshopItemID> ids = new HashSet<>();
        for (WorkshopItemID id : workshopIds) {
            if (id != null) ids.add(id);
        }
        return new ZBSContext(SteamWorkshop.fetchItemDetails(ids), KnownAuthors.loadAuthors());
    }

    static ZBSVerifier.CheckResult zbsCheck(Path jarPath, String hash, WorkshopItemID workshopItemId, ZBSContext ctx) {
        if (!zbsSignatureChecksEnabled() || jarPath == null || hash == null) {
            return ZBSVerifier.CheckResult.DISABLED;
        }
        return ZBSVerifier.check(jarPath, hash, workshopItemId, workshopItemId != null,
            LoaderContext.current().allowUnsignedMods, ctx.workshopDetailsById(), ctx.knownAuthorsBySteamId());
    }

    static boolean zbsBlocked(ZBSVerifier.CheckResult r) {
        return r != ZBSVerifier.CheckResult.DISABLED && !r.flags().has(MF_VALID);
    }

    // ---- approval frontend ---------------------------------------------------------------------------------

    /** Called from {@link Agent#premain} with {@code frontend=...} (default {@code auto}). */
    static void configureApprovalFrontend(String value) {
        LoaderContext lc = LoaderContext.current();
        String v = value == null ? "" : value.trim();
        synchronized (lc.approvalFrontendLock) {
            lc.approvalFrontendConfig = v.isEmpty() ? ModApprovalFrontends.ARG_AUTO : v;
            lc.approvalFrontend = null;
        }
        Logger.info("Java mod approval frontend: " + lc.approvalFrontendConfig);
    }

    static ModApprovalFrontend approvalFrontend() {
        LoaderContext lc = LoaderContext.current();
        ModApprovalFrontend f = lc.approvalFrontend;
        if (f != null) {
            return f;
        }
        synchronized (lc.approvalFrontendLock) {
            if (lc.approvalFrontend == null) {
                lc.approvalFrontend = ModApprovalFrontends.resolve(lc.approvalFrontendConfig);
            }
            return lc.approvalFrontend;
        }
    }

    /** Sets {@link LoaderContext#forceApprovalDialog} while Shift is held (needs the game window). */
    static void checkShiftKey() {
        try {
            long window = Display.getWindow();
            if (window == 0) {
                return;
            }
            if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                    || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS) {
                LoaderContext.current().forceApprovalDialog = true;
            }
        } catch (Throwable t) {
        }
    }

    // ---- decisions -----------------------------------------------------------------------------------------

    static Boolean lookupJarDecision(JarDecisionTable disk, String sha256) {
        if (sha256 == null) return null;
        return disk.get(sha256);
    }

    static void applySessionDecisions(JarDecisionTable approvals) {
        JarDecisionTable session = LoaderContext.current().sessionJarDecisions;
        for (String hash : session.hashes()) {
            approvals.put(hash, session.get(hash));
        }
    }

    static JarDecisionTable buildJarDecisionTable(List<ModApprovalsStore.ModEntry> mods) {
        JarDecisionTable table = new JarDecisionTable();
        for (ModApprovalsStore.ModEntry entry : mods) {
            if (entry.jarHash != null) {
                table.put(entry.jarHash, entry.decision);
            }
        }
        return table;
    }

    static boolean isDecisionStored(String jarHash, Boolean decision) {
        if (jarHash == null || decision == null) return false;
        for (ModApprovalsStore.ModEntry entry : LoaderContext.current().storedEntries) {
            if (jarHash.equals(entry.jarHash) && entry.decision == decision) {
                return true;
            }
        }
        return false;
    }

    static List<ModApprovalsStore.ModEntry> copyStoredEntries(List<ModApprovalsStore.ModEntry> entries) {
        List<ModApprovalsStore.ModEntry> copy = new ArrayList<>();
        if (entries == null) return copy;
        for (ModApprovalsStore.ModEntry entry : entries) {
            if (entry == null) continue;
            copy.add(new ModApprovalsStore.ModEntry(
                entry.id,
                entry.workshopId,
                entry.jarHash,
                entry.decision,
                entry.time,
                entry.authorId
            ));
        }
        return copy;
    }

    /** Adds or updates a decision in the stored entries (for persistence), matched by jar hash. */
    static void storeDecision(String jarHash, boolean allow, String modId, WorkshopItemID workshopId, SteamID64 authorId) {
        if (jarHash == null) return;
        LoaderContext lc = LoaderContext.current();
        for (ModApprovalsStore.ModEntry e : lc.storedEntries) {
            if (jarHash.equals(e.jarHash)) {
                e.decision = allow;
                if (!Utils.isBlank(modId)) e.id = modId;
                if (workshopId != null) e.workshopId = workshopId;
                if (authorId != null) e.authorId = authorId;
                lc.modApprovalsDirty = true;
                return;
            }
        }
        lc.storedEntries.add(new ModApprovalsStore.ModEntry(
            modId != null ? modId : "",
            workshopId,
            jarHash,
            allow,
            null,
            authorId
        ));
        lc.modApprovalsDirty = true;
    }

    static boolean isJarAllowedByPolicy(String modId, JavaModInfo jModInfo, JarDecisionTable disk, String hash) {
        Path jarPath = jModInfo != null ? jModInfo.jarPath() : null;
        if (hash == null) return false;

        String policy = getPolicy();
        Boolean decision = lookupJarDecision(disk, hash);
        if (Boolean.TRUE.equals(decision)) return true;
        if (Boolean.FALSE.equals(decision)) {
            Logger.warn("Blocking Java mod by stored denial: " + modId + " (" + jarPath + ")");
            return false;
        }

        if (POLICY_ALLOW_ALL.equals(policy)) {
            disk.put(hash, Boolean.TRUE);
            storeDecision(hash, true, modId, jModInfo != null ? jModInfo.getWorkshopItemID() : null, null);
            return true;
        }
        if (POLICY_DENY_NEW.equals(policy)) {
            Logger.warn("Blocking Java mod by policy=deny-new: " + modId + " (" + jarPath + ")");
            return false;
        }

        // policy=prompt: decisions come from approvePendingMods() in loadMods(); should not reach here.
        Logger.warn("No approval decision for " + modId + " (hash " + hash + ") — denying.");
        return false;
    }

    static void applyBatchApprovalLines(List<JarBatchApprovalProtocol.Entry> entries, JarDecisionTable disk) {
        if (entries == null) return;
        JarDecisionTable session = LoaderContext.current().sessionJarDecisions;
        for (JarBatchApprovalProtocol.Entry entry : entries) {
            if (entry == null || entry.decision == null || Utils.isBlank(entry.sha256)) continue;
            boolean allow = entry.decision;
            disk.put(entry.sha256, allow);
            session.put(entry.sha256, allow);
            if (entry.flags.has(MF_PERSIST)) {
                storeDecision(entry.sha256, allow, entry.modId, entry.workshopItemId,
                    entry.zbs.valid() ? entry.zbs.authorSteamId() : null);
            }
            if (entry.flags.has(MF_PERSIST) && entry.zbs.valid() && entry.zbs.authorSteamId() != null) {
                if (entry.flags.has(MF_TRUST_AUTHOR)) {
                    storeTrustedAuthor(entry.zbs.authorSteamId());
                } else if (isAuthorTrusted(entry.zbs.authorSteamId())) {
                    // 'trust author' checkbox unchecked
                    removeTrustedAuthor(entry.zbs.authorSteamId());
                }
            }
            if (entry.flags.has(MF_PRELOAD) && !Utils.isBlank(entry.modId) && !Utils.isBlank(entry.infAbsolutePath) && !Utils.isBlank(entry.jarAbsolutePath)) {
                if (allow) {
                    PreloadMods.add(entry.modId, entry.infAbsolutePath, entry.jarAbsolutePath);
                } else {
                    PreloadMods.remove(entry.modId);
                }
            }
        }
    }

    // ---- trusted authors -----------------------------------------------------------------------------------

    static boolean isAuthorTrusted(SteamID64 sid) {
        return sid != null && LoaderContext.current().config.trustsAuthor(sid);
    }

    static void storeTrustedAuthor(SteamID64 sid) {
        if (sid == null) return;
        LoaderContext lc = LoaderContext.current();
        lc.replaceConfig(lc.config.withTrustedAuthor(sid));
    }

    static void removeTrustedAuthor(SteamID64 sid) {
        if (sid == null) return;
        LoaderContext lc = LoaderContext.current();
        lc.replaceConfig(lc.config.withoutTrustedAuthor(sid));
    }

    static void untrustAuthorForBannedMod(
        String modId,
        Path jarPath,
        String hash,
        WorkshopItemID workshopItemId,
        boolean steamBanned,
        Map<WorkshopItemID, SteamWorkshop.ItemDetails> workshopDetailsById
    ) {
        if (!steamBanned || jarPath == null || hash == null) {
            return;
        }
        SteamID64 uploaderID = SteamWorkshop.getUploaderID(workshopItemId, workshopDetailsById);
        if (uploaderID != null && isAuthorTrusted(uploaderID)) {
            removeTrustedAuthor(uploaderID);
            Logger.warn("Removed trusted-author flag for " + uploaderID + " because banned mod was detected: " + modId);
        }
    }

    static String authorDisplayName(SteamID64 sid, Map<SteamID64, KnownAuthors.AuthorEntry> knownAuthors) {
        if (sid == null) {
            return "";
        }
        KnownAuthors.AuthorEntry known = knownAuthors != null ? knownAuthors.get(sid) : null;
        return known != null && !Utils.isBlank(known.name) ? known.name : sid.toString();
    }
}

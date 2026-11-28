package io.github.karl3uwu.zombud;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.karl3uwu.zombud.frontend.ModApprovalFrontend;
import io.github.karl3uwu.zombud.frontend.ModApprovalFrontends;
import io.github.karl3uwu.zombud.transformers.TransformedJar;

/**
 * All mutable loader state of one agent run: agent arguments, config, jar policy and approvals, loaded mod jars
 * and their status. {@link Agent#premain} creates it with {@link #start}; everything else reads it through
 * {@link #current()}, including code called from game code (patch advice, Lua).
 *
 * <p>Before {@code premain} runs (unit tests, the {@code jardump} tool) {@link #current()} hands out a default
 * context with no instrumentation, so the same code paths work there.
 *
 * <p>Fields are package-private on purpose: mods (other packages) can't reach the instrumentation handle or flip
 * the jar policy. Game-facing setters are public methods.
 */
public final class LoaderContext {
    private static volatile LoaderContext s_current;

    /** The context of this agent run, or a default one when {@link #start} hasn't been called. */
    public static LoaderContext current() {
        LoaderContext ctx = s_current;
        if (ctx != null) {
            return ctx;
        }
        synchronized (LoaderContext.class) {
            if (s_current == null) {
                s_current = new LoaderContext(null);
            }
            return s_current;
        }
    }

    /**
     * Creates the context for this agent run and makes it {@link #current()}. Called once from
     * {@link Agent#premain}; a default context handed out before that is replaced.
     */
    static synchronized LoaderContext start(Instrumentation inst) {
        LoaderContext ctx = new LoaderContext(inst);
        s_current = ctx;
        return ctx;
    }

    /** Drops the current context; the next {@link #current()} creates a fresh default one. For tests. */
    static synchronized void reset() {
        s_current = null;
    }

    LoaderContext(Instrumentation inst) {
        this.instrumentation = inst;
    }

    // ---- agent ---------------------------------------------------------------------------------------------

    /** Only our own classes may touch the instrumentation handle. */
    final Instrumentation instrumentation;

    /** Agent arguments ({@code -javaagent:KRLZomBud.jar=k=v,...} plus {@code prop_prefix} system properties). */
    final Map<String, String> arguments = new ConcurrentHashMap<>();

    volatile int verbosity = 0;

    // ---- config (~/.krl_zombud/config.json) -----------------------------------------------------------------

    Config config = new Config();
    boolean configDirty;

    // ---- jar policy and approvals --------------------------------------------------------------------------

    /** Write-once; see {@link ModApprovals#setPolicy}. */
    volatile String jarPolicy = ModApprovals.POLICY_PROMPT;
    volatile boolean jarPolicyLocked = false;

    /**
     * When {@code true} (default), a missing {@code .zbs} next to the JAR is allowed as "unsigned".
     * When {@code false}, a missing {@code .zbs} is treated like a failed signature.
     */
    volatile boolean allowUnsignedMods = true;

    /** Set by holding Shift during game load; reset after one approval batch. */
    volatile boolean forceApprovalDialog = false;

    /** Persisted approval entries loaded from disk, the source of truth for saving. */
    List<ModApprovalsStore.ModEntry> storedEntries = new ArrayList<>();
    boolean modApprovalsDirty;

    /** Decisions made in this session (survive a mod list reload without a restart). */
    final JarDecisionTable sessionJarDecisions = new JarDecisionTable();

    final Object approvalFrontendLock = new Object();
    volatile ModApprovalFrontend approvalFrontend;
    /** Resolved lazily: don't touch LWJGL during agent {@code premain}. */
    volatile String approvalFrontendConfig = ModApprovalFrontends.ARG_AUTO;

    // ---- loaded jars ---------------------------------------------------------------------------------------

    final Map<Path, ClassLoader> modLoaders = new ConcurrentHashMap<>();
    final Map<Path, TransformedJar> transformedJars = new ConcurrentHashMap<>();
    /** Keyed by canonical jar path. */
    final Map<Path, Loader.JavaModLoadState> jarLoadStatus = new ConcurrentHashMap<>();
    final Set<String> knownMains = ConcurrentHashMap.newKeySet();
    final Map<String, EnumSet<Loader.Phase>> knownPackagePhases = new HashMap<>();
    /** Widest package name printed so far, for aligned mod lists. */
    int maxPkgLen = 0;

    /** Mod id per loaded jar key, for messages; falls back to the package name. */
    final Map<Path, String> jarModIds = new ConcurrentHashMap<>();
    /** Jars embedded in the agent (patches.jar, experimental.jar): a failed check skips only that patch. */
    final Set<Path> bundledJars = ConcurrentHashMap.newKeySet();
    /** Jars whose Java side was turned off by a failed required patch check. */
    final Set<Path> disabledJars = ConcurrentHashMap.newKeySet();
    /** Installed MethodDelegations: {@link PatchChecker#delegationKey} → "modId (patch class)". */
    final Map<String, String> delegationOwners = new ConcurrentHashMap<>();
    /** Results of the load-time patch checks and transform errors. */
    final PatchReport patchReport = new PatchReport();

    public PatchReport patchReport() {
        return patchReport;
    }

    // ---- session mode (launcher session file, see SessionLoader) -----------------------------------------------

    /** {@code -Dkrl.session} or {@code KRL_SESSION} was given: the in-game Java loading path is off. */
    volatile boolean sessionRequested;
    volatile Path sessionPath;
    /** The parsed session; null when none was requested or it couldn't be used. */
    volatile io.github.karl3uwu.zombud.session.Session session;
    /** Why session mode runs without Java mods (bad file, build mismatch); null when it doesn't. */
    volatile String sessionError;
    volatile Path stageFile;
    /** Where patch-report.json goes; null means the config dir. */
    volatile Path reportDir;
    /** Game build read at premain from Core.class bytes; null when the shape wasn't recognised. */
    volatile String premainGameBuild;
    /** Jars loaded at premain in session mode, in session order. */
    final List<SessionLoader.LoadedJar> sessionJars = new java.util.concurrent.CopyOnWriteArrayList<>();
    final Set<String> stagesWritten = ConcurrentHashMap.newKeySet();

    /** Classes patched when first defined (no retransformation), and classes retransformed after loading. */
    final Set<String> firstLoadClasses = ConcurrentHashMap.newKeySet();
    final Set<String> retransformedClasses = ConcurrentHashMap.newKeySet();

    public Set<String> firstLoadClasses() {
        return Set.copyOf(firstLoadClasses);
    }

    public Set<String> retransformedClasses() {
        return Set.copyOf(retransformedClasses);
    }

    /** Classes already redefined by {@link PatchTransformer}, by name. */
    final Map<String, Class<?>> transformedClasses = new ConcurrentHashMap<>();

    // ---- in-game mod list (ZomboidFileSystem.loadMods) -----------------------------------------------------

    String prevModSetID, currModSetID;
    HashSet<String> prevModSet, currModSet;

    /** {@code GameWindow.DoLoadingText} has run, so the loading screen can show text. */
    volatile boolean hasDoLoadingText = false;

    // ---- misc settings -------------------------------------------------------------------------------------

    volatile boolean dirHiderEnabled = true;

    volatile int watermarkTtl = Watermark.DEFAULT_TTL;
    volatile float watermarkAlpha = Watermark.DEFAULT_ALPHA;
    volatile String watermarkMidLine = null;

    // ---- accessors for game code ---------------------------------------------------------------------------

    public boolean hasDoLoadingText() {
        return hasDoLoadingText;
    }

    /** Called from the {@code GameWindow.DoLoadingText} patch. */
    public void setHasDoLoadingText(boolean value) {
        hasDoLoadingText = value;
    }

    public Instrumentation instrumentation() {
        return instrumentation;
    }

    public String argument(String key) {
        return key == null ? null : arguments.get(key);
    }

    public boolean hasArgument(String key) {
        return key != null && arguments.containsKey(key);
    }

    public int verbosity() {
        return verbosity;
    }

    // ---- config helpers ------------------------------------------------------------------------------------

    /** Replaces the config and marks it dirty; returns false (and changes nothing) when {@code next} is equal. */
    boolean replaceConfig(Config next) {
        if (next == null || next.equals(config)) {
            return false;
        }
        config = next;
        configDirty = true;
        return true;
    }

    /** Replaces the config and saves it at once (settings changed from the in-game options). */
    void replaceAndSaveConfig(Config next) {
        if (replaceConfig(next)) {
            Config.save(config);
            configDirty = false;
        }
    }

    void saveConfigIfDirty() {
        if (configDirty) {
            Config.save(config);
            configDirty = false;
        }
    }
}

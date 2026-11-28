package io.github.karl3uwu.zombud;

/**
 * Names that identify KRL ZomBud, plus upstream ZombieBuddy's names so its presence can be detected and reported.
 *
 * <p>KRL ZomBud ships as mod id {@value #MOD_ID} with agent jar {@value #JAR_NAME}. It is a standalone fork:
 * a requirement on {@value #LEGACY_MOD_ID} is not mapped to it. See {@code doc/RENAME.md}.
 */
public final class ModIdentity {
    private ModIdentity() {}

    /** Human-readable product name. */
    public static final String DISPLAY_NAME = "KRL ZomBud";

    /** Mod id in {@code mod.info} (and the folder name under {@code mods/}). */
    public static final String MOD_ID = "KRLZomBud";

    /** File name of the agent jar, in the mod's {@code libs/} folder and in the game folder. */
    public static final String JAR_NAME = MOD_ID + ".jar";

    /** Upstream ZombieBuddy's mod id (used only to detect and warn about upstream being installed too). */
    public static final String LEGACY_MOD_ID = "ZombieBuddy";

    /** Upstream ZombieBuddy's agent jar name. */
    public static final String LEGACY_JAR_NAME = LEGACY_MOD_ID + ".jar";

    /** Upstream ZombieBuddy's Java package (its {@code javaPkgName}); a mod declaring it is never loaded. */
    public static final String LEGACY_JAVA_PKG = "me.zed_0xff.zombie_buddy";

    /** Default prefix for {@code prop_prefix}: {@code -Dkrl.verbosity=2} and so on. */
    public static final String PROP_PREFIX = "krl";

    /** Environment variable that overrides the {@code verbosity} argument. */
    public static final String ENV_VERBOSITY = "KRL_VERBOSITY";

    /** ZombieBuddy's name for {@link #ENV_VERBOSITY}; no longer read, only warned about. */
    public static final String LEGACY_ENV_VERBOSITY = "ZB_VERBOSITY";

    /** Our own package (running as the agent) and upstream ZombieBuddy's are never loaded as a mod's Java side. */
    public static boolean isNeverLoadedAsMod(String javaPkgName) {
        return javaPkgName != null
            && (javaPkgName.equals(ModIdentity.class.getPackageName()) || javaPkgName.equals(LEGACY_JAVA_PKG));
    }

    public static boolean isLegacyModId(String modId) {
        return modId != null && modId.trim().equals(LEGACY_MOD_ID);
    }
}

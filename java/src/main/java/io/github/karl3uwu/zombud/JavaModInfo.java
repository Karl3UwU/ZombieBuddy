package io.github.karl3uwu.zombud;
import io.github.karl3uwu.zombud.api.ZomBud;

import static io.github.karl3uwu.zombud.SteamWorkshop.WorkshopItemID;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents Java mod information parsed from a mod.info file.
 * Contains JAR file path and package name for a Java mod.
 */
record JavaModInfo(
    Path infPath,        // mod.info file path
    Path jarPath,        // JAR file path
    String javaPkgName,  // Package name
    String krlVersionMin, // Minimum KRL ZomBud version required (KRLVersionMin)
    String krlVersionMax, // Maximum KRL ZomBud version required (KRLVersionMax)
    String displayName,  // From {@code name=} in mod.info; may be null
    boolean javaPreload  // From {@code javaPreload=true} in mod.info; both this and MANIFEST.MF KRL-Preload must be set
) {
    /** Project Zomboid Steam app id used in Workshop paths: .../content/108600/<publishedfileid>/... */
    private static final Pattern WORKSHOP_ITEM_ID_IN_PATH = Pattern.compile("/content/" + SteamWorkshop.PZ_APP_ID + "/([0-9]+)/", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORKSHOP_ITEM_ID_IN_TXT  = Pattern.compile("^id=([0-9]+)$");

    /**
     * Extracts Steam Workshop {@code publishedfileid} from mod directory path:
     * {@code .../content/108600/<workshopItemId>/...}
     *
     * @return typed Workshop item id, or {@code null} when not a Workshop-installed mod path.
     */
    WorkshopItemID getWorkshopItemID() {
        return workshopItemIdFromInfPath(infPath);
    }

    static WorkshopItemID workshopItemIdFromInfPath(Path path) {
        WorkshopItemID id = workshopItemIdFromSteamPath(path);
        if (id != null) return id;

        Path p = path.getParent();
        int depth = 4;                     // XXX assume B41 java-mods live in /41 subdir
        while (p != null && depth > 0) {
            p = p.getParent();
            depth--;
        }
        if (p == null || p.getParent() == null) {
            return null;
        }
        Path ws = p.getParent();
        Path wsName = ws.getFileName(); // null when ws is a filesystem root (e.g. "G:\\" or "/")
        if (wsName != null && wsName.toString().equalsIgnoreCase("workshop") && Utils.isSameFile(ws.getParent(), Utils.getCachePath())) {
            return workshopItemIdFromWorkshopTxtIn(p);
        }
        return null;
    }

    /** Extracts the Steam Workshop item ID from any absolute path */
    static WorkshopItemID workshopItemIdFromSteamPath(Path path) {
        if (path == null) return null;

        path = path.toAbsolutePath();
        String p = path.toString().replace('\\', '/');
        Matcher m = WORKSHOP_ITEM_ID_IN_PATH.matcher(p + "/");
        if (m.find()) {
            try {
                return new WorkshopItemID(Long.parseLong(m.group(1)));
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static WorkshopItemID workshopItemIdFromWorkshopTxtIn(Path dir) {
        Path workshopTxt = dir.resolve("workshop.txt");
        if (!Files.isRegularFile(workshopTxt)) {
            return null;
        }
        try (var reader = new java.io.BufferedReader(new java.io.FileReader(workshopTxt.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String s = line.trim();
                Matcher m = WORKSHOP_ITEM_ID_IN_TXT.matcher(s);
                if (!m.matches()) {
                    continue;
                }
                try {
                    return new WorkshopItemID(Long.parseLong(m.group(1)));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }
    
    /**
     * One (jarFile, krlVersionMin, krlVersionMax) triple parsed from mod.info. The numeric suffix on
     * javaJarFileN / krlVersionMinN / krlVersionMaxN keys (implicitly 0 when absent) ties these three
     * fields together, so a single mod.info can offer several JARs built against different
     * KRL ZomBud API versions; the loader picks the first candidate (lowest index) whose range covers
     * the running KRL ZomBud version. Keys are case-insensitive ({@code KRLVersionMin2=...}).
     * ZombieBuddy's {@code ZBVersionMin}/{@code ZBVersionMax} keys are not read.
     */
    private record JarCandidate(String jarFilePath, String krlVersionMin, String krlVersionMax) {}

    private static final class CandidateBuilder {
        String jarFilePath;
        String krlVersionMin;
        String krlVersionMax;
    }

    private static final Pattern JAR_KEY = Pattern.compile("^javajarfile(\\d*)=");
    private static final Pattern MIN_KEY = Pattern.compile("^krlversionmin(\\d*)=");
    private static final Pattern MAX_KEY = Pattern.compile("^krlversionmax(\\d*)=");

    /**
     * Internal record to hold parsed values from a mod.info file.
     */
    private record ParsedValues(
        List<JarCandidate> jarCandidates,
        String javaPkgName,
        String displayName,
        boolean javaPreload
    ) {}

    private static String trimmedValue(String line) {
        return line.split("=", 2)[1].trim();
    }

    private static String versionMismatchMessage(List<JarCandidate> candidates, String loaderVersion) {
        StringBuilder sb = new StringBuilder("(requires one of: ");
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) sb.append(", ");
            JarCandidate c = candidates.get(i);
            sb.append(c.krlVersionMin() != null ? c.krlVersionMin() : "any")
              .append(" to ")
              .append(c.krlVersionMax() != null ? c.krlVersionMax() : "any");
        }
        sb.append("; KRL ZomBud version: ").append(loaderVersion).append(")");
        return sb.toString();
    }

    /**
     * Validates parsed values and creates JavaModInfo, or null if invalid.
     */
    private static JavaModInfo validateAndCreate(ParsedValues parsed, Path infPath, Path infDir, Path jarDir, boolean bLogMissingJar) {
        return validateAndCreate(parsed, infPath, infDir, jarDir, bLogMissingJar, ZomBud.getVersion(), Utils.isServer());
    }

    private static JavaModInfo validateAndCreate(ParsedValues parsed, Path infPath, Path infDir, Path jarDir, boolean bLogMissingJar,
                                                 String loaderVersion, boolean isServer) {
        List<JarCandidate> candidates = parsed.jarCandidates();
        String javaPkgName = parsed.javaPkgName();

        if (candidates.isEmpty()) {
            Logger.trace("No 'javaJarFile' in", infPath);
            return null;
        }
        if (Utils.isBlank(javaPkgName)) {
            Logger.error("No 'javaPkgName' in", infPath);
            return null;
        }


        boolean anyPlatformMatch = false;
        JarCandidate versionMatch = null;

        for (JarCandidate candidate : candidates) {
            String jarFilePath = candidate.jarFilePath();
            if (isServer ? jarFilePath.contains("media/java/client/") : jarFilePath.contains("media/java/server/")) {
                continue;
            }
            anyPlatformMatch = true;
            if (isVersionInRange(loaderVersion, candidate.krlVersionMin(), candidate.krlVersionMax())) {
                versionMatch = candidate;
                break;
            }
        }

        if (!anyPlatformMatch) {
            Logger.warn(isServer ? "Skipping client-only mod" : "Skipping server-only mod", infPath);
            return null;
        }
        if (versionMatch == null) {
            Logger.error("Skipping mod due to version mismatch", infPath, versionMismatchMessage(candidates, loaderVersion));
            return null;
        }

        Path jarPath = jarDir.resolve(versionMatch.jarFilePath());
        if (!Files.isRegularFile(jarPath)) {
            Logger.log(bLogMissingJar ? Logger.ERROR : Logger.TRACE, "JAR not found", jarPath);
            return null;
        }

        return new JavaModInfo(
            infPath,
            jarPath,
            javaPkgName,
            versionMatch.krlVersionMin(),
            versionMatch.krlVersionMax(),
            parsed.displayName(),
            parsed.javaPreload()
        );
    }

    /**
     * Parses a mod.info file and extracts jarFile and javaPkgName values.
     * Returns null if the file doesn't exist, cannot be read, or parsing fails.
     *
     * @param infPath The mod.info file to parse
     * @return ParsedValues containing jarFile and javaPkgName, or null if parsing fails
     */
    private static ParsedValues parseModInfoFile(Path infPath) {
        if (infPath == null || !Files.isRegularFile(infPath)) {
            return null;
        }

        Map<Integer, CandidateBuilder> candidates = new HashMap<>();
        String javaPkgName  = null;
        String displayName  = null;
        boolean javaPreload = false;

        try (var reader = new java.io.BufferedReader(new java.io.FileReader(infPath.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || !line.contains("=")) {
                    continue;
                }
                String lowerLine = line.toLowerCase();
                String value = trimmedValue(line);
                Matcher m;

                if ((m = JAR_KEY.matcher(lowerLine)).find()) {
                    int idx = m.group(1).isEmpty() ? 0 : Integer.parseInt(m.group(1));
                    if (!value.isEmpty()) {
                        if (!value.endsWith(".jar")) {
                            Logger.error("Error! javaJarFile entry must end with \".jar\": " + value);
                            continue;
                        }
                        CandidateBuilder b = candidates.computeIfAbsent(idx, k -> new CandidateBuilder());
                        if (b.jarFilePath != null) {
                            Logger.error("Warning! Multiple javaJarFile" + (idx == 0 ? "" : idx)
                                + " entries found, only the first one will be used: " + infPath);
                            continue;
                        }
                        b.jarFilePath = value;
                    }
                } else if (lowerLine.startsWith("javapkgname=")) {
                    if (javaPkgName != null) {
                        Logger.error("Warning! Multiple javaPkgName entries found, only the first one will be used: " + infPath);
                        continue;
                    }
                    if (!value.isEmpty()) {
                        javaPkgName = value;
                    }
                } else if ((m = MIN_KEY.matcher(lowerLine)).find()) {
                    int idx = m.group(1).isEmpty() ? 0 : Integer.parseInt(m.group(1));
                    candidates.computeIfAbsent(idx, k -> new CandidateBuilder()).krlVersionMin = value;
                } else if ((m = MAX_KEY.matcher(lowerLine)).find()) {
                    int idx = m.group(1).isEmpty() ? 0 : Integer.parseInt(m.group(1));
                    candidates.computeIfAbsent(idx, k -> new CandidateBuilder()).krlVersionMax = value;
                } else if (lowerLine.startsWith("name=")) {
                    if (displayName == null && !value.isEmpty()) {
                        displayName = value;
                    }
                } else if (lowerLine.startsWith("javapreload=")) {
                    javaPreload = "true".equalsIgnoreCase(value);
                }
            }
        } catch (Exception e) {
            Logger.error("error reading " + infPath + ": " + e);
            return null;
        }

        List<Integer> indices = new ArrayList<>(candidates.keySet());
        Collections.sort(indices);

        List<JarCandidate> jarCandidates = new ArrayList<>();
        for (Integer idx : indices) {
            CandidateBuilder b = candidates.get(idx);
            if (Utils.isBlank(b.jarFilePath)) {
                Logger.error("Warning! KRLVersionMin" + (idx == 0 ? "" : idx) + "/KRLVersionMax" + (idx == 0 ? "" : idx)
                    + " given without a matching javaJarFile" + (idx == 0 ? "" : idx) + ": " + infPath);
                continue;
            }
            jarCandidates.add(new JarCandidate(b.jarFilePath, b.krlVersionMin, b.krlVersionMax));
        }

        return new ParsedValues(jarCandidates, javaPkgName, displayName, javaPreload);
    }
    
    /**
     * Parses a mod.info file and returns a JavaModInfo object.
     * Returns null if the mod.info file or JAR file doesn't exist or cannot be read.
     * 
     * @param modDir The directory containing the mod.info file
     * @return JavaModInfo object, or null if the file doesn't exist or cannot be parsed
     */
    static JavaModInfo parse(Path modDir) {
        if (modDir == null || !Files.isDirectory(modDir)) {
            Logger.trace("not a dir      ", modDir);
            return null;
        }

        Path infPath = modDir.resolve("mod.info");
        ParsedValues parsed = parseModInfoFile(infPath);
        if (parsed == null) {
            Logger.trace("no/bad mod.info", infPath);
            return null;
        }
        return validateAndCreate(parsed, infPath, modDir, modDir, false);
    }

    static String javaPkgNameFrom(Path infPath) {
        ParsedValues parsed = parseModInfoFile(infPath);
        return parsed != null ? parsed.javaPkgName() : null;
    }

    static JavaModInfo parse(String modDirPath) {
        if (Utils.isBlank(modDirPath)) {
            return null;
        }
        return parse(Path.of(modDirPath));
    }
    
    /**
     * Parses mod.info from commonDir and uses versionDir to locate the JAR file.
     * This is useful when mod.info is in commonDir but the JAR file is in versionDir.
     * Only reads mod.info from commonDir, not from versionDir.
     * 
     * @param commonDir The common directory containing mod.info
     * @param versionDir The version directory where the JAR file may be located
     * @return JavaModInfo object, or null if mod.info doesn't exist or cannot be parsed
     */
    static JavaModInfo parseMerged(Path commonDir, Path versionDir) {
        if (commonDir == null || !Files.isDirectory(commonDir)) {
            return null;
        }
        if (versionDir == null || !Files.isDirectory(versionDir)) {
            return null;
        }

        // common/mod.info
        // 42.13/media/java/ZBBetterFPS.jar
        Path commonModInfoFile = commonDir.resolve("mod.info");
        ParsedValues commonParsed = parseModInfoFile(commonModInfoFile);

        if (commonParsed == null) {
            // 42/mod.info
            // common/media/java/shared/ZBSpec.jar
            Path verModInfoFile = versionDir.resolve("mod.info");
            ParsedValues verParsed = parseModInfoFile(verModInfoFile);
            if (verParsed == null) {
                return null;
            }
            return validateAndCreate(verParsed, verModInfoFile, versionDir, commonDir, true);
        }
        return validateAndCreate(commonParsed, commonModInfoFile, commonDir, versionDir, true);
    }

    /**
     * For tools outside the game (session CLI, a Java launcher): the same B42 lookup the in-game path does
     * (version dir, then common dir, then mod.info in one and the jar in the other), with the loader version and
     * client/server side given instead of read from the running game. {@code versionDir} or {@code commonDir} may
     * be null (a B41-style mod passes its folder as {@code versionDir}).
     */
    static JavaModInfo resolveForTool(Path versionDir, Path commonDir, String loaderVersion, boolean isServer) {
        JavaModInfo r = parseForTool(versionDir, loaderVersion, isServer);
        if (r == null) r = parseForTool(commonDir, loaderVersion, isServer);
        if (r == null && versionDir != null && commonDir != null
                && Files.isDirectory(versionDir) && Files.isDirectory(commonDir)) {
            ParsedValues common = parseModInfoFile(commonDir.resolve("mod.info"));
            if (common != null) {
                r = validateAndCreate(common, commonDir.resolve("mod.info"), commonDir, versionDir, false, loaderVersion, isServer);
            } else {
                ParsedValues ver = parseModInfoFile(versionDir.resolve("mod.info"));
                if (ver != null) {
                    r = validateAndCreate(ver, versionDir.resolve("mod.info"), versionDir, commonDir, false, loaderVersion, isServer);
                }
            }
        }
        return r;
    }

    private static JavaModInfo parseForTool(Path dir, String loaderVersion, boolean isServer) {
        if (dir == null || !Files.isDirectory(dir)) return null;
        ParsedValues parsed = parseModInfoFile(dir.resolve("mod.info"));
        return parsed == null ? null : validateAndCreate(parsed, dir.resolve("mod.info"), dir, dir, false, loaderVersion, isServer);
    }

    static JavaModInfo parseMerged(String commonDirStr, String versionDirStr) {
        if (Utils.isBlank(commonDirStr)) {
            return null;
        }
        if (Utils.isBlank(versionDirStr)) {
            return null;
        }
        return parseMerged(Path.of(commonDirStr), Path.of(versionDirStr));
    }

    /**
     * Checks if a version is within the specified minimum and maximum range (both inclusive).
     * Uses {@link Utils#compareVersionsForUpdate}, so a prerelease sorts before its release:
     * {@code 3.0.0-beta3 < 3.0.0}, and {@code KRLVersionMin=3.0.0} rejects {@code 3.0.0-beta3}.
     * 
     * @param currentVersion The current version to check
     * @param minVersion The minimum version allowed (inclusive), or null if no minimum
     * @param maxVersion The maximum version allowed (inclusive), or null if no maximum
     * @return true if the version is in range, false otherwise
     */
    static boolean isVersionInRange(String currentVersion, String minVersion, String maxVersion) {
        if (currentVersion == null || currentVersion.equals("unknown")) {
            // If we don't know our own version, we can't really check.
            // But usually this means we are in development mode.
            return true;
        }
        
        if (!Utils.isBlank(minVersion)) {
            if (Utils.compareVersionsForUpdate(currentVersion, minVersion) < 0) {
                return false;
            }
        }
        
        if (!Utils.isBlank(maxVersion)) {
            if (Utils.compareVersionsForUpdate(currentVersion, maxVersion) > 0) {
                return false;
            }
        }
        
        return true;
    }
}


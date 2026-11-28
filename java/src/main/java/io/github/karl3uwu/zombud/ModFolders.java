package io.github.karl3uwu.zombud;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds installed mods the way the game does, without the game running: local {@code <user dir>/mods}, the
 * {@code <user dir>/Workshop/<item>/Contents/mods} upload folders and Steam Workshop content
 * ({@code steamapps/workshop/content/108600/<id>/mods}). For B42-style mods it picks the highest version folder
 * not newer than the game build, plus {@code common}. Java jars come from mod.info through the same rules the
 * loader uses ({@link JavaModInfo}).
 *
 * <p>Used by {@code java -jar KRLZomBud.jar session create}; a launcher written in Java can share it.
 */
public final class ModFolders {
    private ModFolders() {}

    public static final long PZ_APP_ID = 108600L;

    public record FoundJar(Path jar, String sha256, String pkg) {}

    /** {@code dir}: the folder for the chosen build (version folder, else common, else the mod folder). */
    public record FoundMod(String id, Long workshopId, Path dir, List<FoundJar> jars) {}

    private static final Pattern VERSION_DIR = Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");

    /** Mod roots to scan, in the order the game prefers them (local first). */
    public static List<Path> modRoots(Path userDir, Path gameDir) {
        List<Path> roots = new ArrayList<>();
        if (userDir != null) {
            roots.add(userDir.resolve("mods"));
            Path uploads = userDir.resolve("Workshop");
            if (Files.isDirectory(uploads)) {
                try (DirectoryStream<Path> items = Files.newDirectoryStream(uploads)) {
                    for (Path item : items) roots.add(item.resolve("Contents").resolve("mods"));
                } catch (IOException ignored) {
                }
            }
        }
        if (gameDir != null) {
            Path content = workshopContentDir(gameDir);
            if (content != null && Files.isDirectory(content)) {
                try (DirectoryStream<Path> items = Files.newDirectoryStream(content)) {
                    for (Path item : items) roots.add(item.resolve("mods"));
                } catch (IOException ignored) {
                }
            }
        }
        return roots;
    }

    /** {@code <steamapps>/workshop/content/108600} for a game in {@code <steamapps>/common/<game>}. */
    static Path workshopContentDir(Path gameDir) {
        Path common = gameDir.toAbsolutePath().normalize().getParent();
        Path steamapps = common != null ? common.getParent() : null;
        return steamapps == null ? null : steamapps.resolve("workshop").resolve("content").resolve(Long.toString(PZ_APP_ID));
    }

    /**
     * All mods under {@code roots}, by id; the first root that has an id wins. {@code gameBuild} picks version
     * folders ({@code "42.22.2"} uses folders up to 42.22); {@code loaderVersion} applies KRLVersionMin/Max.
     */
    public static Map<String, FoundMod> scan(List<Path> roots, String gameBuild, String loaderVersion) {
        Map<String, FoundMod> out = new LinkedHashMap<>();
        for (Path root : roots) {
            if (root == null || !Files.isDirectory(root)) continue;
            try (DirectoryStream<Path> mods = Files.newDirectoryStream(root, Files::isDirectory)) {
                for (Path modFolder : mods) {
                    FoundMod m = describe(modFolder, gameBuild, loaderVersion);
                    if (m != null) out.putIfAbsent(m.id(), m);
                }
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    /** One mod folder, or null when it has no mod.info with an id for this build. */
    public static FoundMod describe(Path modFolder, String gameBuild, String loaderVersion) {
        Path common = modFolder.resolve("common");
        Path version = bestVersionDir(modFolder, gameBuild);
        Path chosen;
        Path commonOrNull = Files.isDirectory(common) ? common : null;
        if (version != null) {
            chosen = version;
        } else if (commonOrNull != null) {
            chosen = commonOrNull;
        } else if (Files.isRegularFile(modFolder.resolve("mod.info"))) {
            chosen = modFolder; // B41-style flat mod
        } else {
            return null;
        }

        String id = readModInfoId(chosen.resolve("mod.info"));
        if (id == null && commonOrNull != null) id = readModInfoId(commonOrNull.resolve("mod.info"));
        if (id == null) return null;

        List<FoundJar> jars = new ArrayList<>();
        JavaModInfo info = JavaModInfo.resolveForTool(chosen == commonOrNull ? null : chosen, commonOrNull,
            loaderVersion, false);
        if (info != null && Files.isRegularFile(info.jarPath()) && !ModIdentity.isNeverLoadedAsMod(info.javaPkgName())) {
            Path jar = info.jarPath().toAbsolutePath().normalize();
            jars.add(new FoundJar(jar, Utils.sha256Hex(jar), info.javaPkgName()));
        }
        return new FoundMod(id, workshopIdOf(modFolder), chosen.toAbsolutePath().normalize(), List.copyOf(jars));
    }

    /** Highest {@code N[.M[.P]]} folder not newer than {@code gameBuild}; null when there is none. */
    static Path bestVersionDir(Path modFolder, String gameBuild) {
        int[] game = parseVersion(gameBuild);
        Path best = null;
        int[] bestV = null;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(modFolder, Files::isDirectory)) {
            for (Path d : dirs) {
                int[] v = parseVersion(d.getFileName().toString());
                if (v == null) continue;
                if (game != null && compare(v, game) > 0) continue;
                if (bestV == null || compare(v, bestV) > 0) {
                    best = d;
                    bestV = v;
                }
            }
        } catch (IOException ignored) {
        }
        return best;
    }

    private static int[] parseVersion(String s) {
        if (s == null) return null;
        Matcher m = VERSION_DIR.matcher(s.trim());
        if (!m.matches()) return null;
        return new int[] {
            Integer.parseInt(m.group(1)),
            m.group(2) != null ? Integer.parseInt(m.group(2)) : 0,
            m.group(3) != null ? Integer.parseInt(m.group(3)) : 0,
        };
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(a[i], b[i]);
            if (c != 0) return c;
        }
        return 0;
    }

    static String readModInfoId(Path modInfo) {
        if (!Files.isRegularFile(modInfo)) return null;
        try {
            for (String line : Files.readAllLines(modInfo, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.regionMatches(true, 0, "id=", 0, 3)) {
                    String id = t.substring(3).trim();
                    while (id.startsWith("\\")) id = id.substring(1);
                    return id.isEmpty() ? null : id;
                }
            }
        } catch (IOException | RuntimeException e) {
            // not UTF-8 or unreadable
        }
        return null;
    }

    /** Steam Workshop item id from {@code .../content/108600/<id>/mods/<mod>}, else null. */
    static Long workshopIdOf(Path modFolder) {
        Path mods = modFolder.toAbsolutePath().normalize().getParent();
        Path item = mods != null ? mods.getParent() : null;
        Path content = item != null ? item.getParent() : null;
        if (item == null || content == null || content.getFileName() == null) return null;
        if (!Long.toString(PZ_APP_ID).equals(content.getFileName().toString())) return null;
        try {
            return Long.parseLong(item.getFileName().toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

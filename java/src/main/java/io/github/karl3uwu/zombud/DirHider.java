package io.github.karl3uwu.zombud;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class DirHider {

    public static void setEnabled(boolean enabled) {
        LoaderContext.current().dirHiderEnabled = enabled;
    }

    public static boolean isEnabled() {
        return LoaderContext.current().dirHiderEnabled;
    }

    private static final Set<String> canonHideNames = new HashSet<>(Arrays.asList(
        "tmp",
        ".git",
        ".hg",
        ".svn",
        ".vscode",
        ".idea"
    ));

    private static boolean shouldHideName(String name) {
        return name != null && canonHideNames.contains(name.toLowerCase());
    }

    enum CMP_MODE {
        FIRST,
        LAST,
        ANY,
    }

    private static boolean shouldHidePath(Path path, CMP_MODE mode) {
        if (path == null) return false;
        if (path.getNameCount() == 0 ) return false;

        switch (path.getNameCount()) {
            case 0:
                return false;
            case 1:
                return shouldHideName(path.getFileName().toString());
            default:
                switch (mode) {
                    case FIRST:
                        return shouldHideName(path.getName(0).toString());
                    case LAST:
                        return shouldHideName(path.getFileName().toString());
                    case ANY:
                        return shouldHideName(path.getName(0).toString()) || shouldHideName(path.getFileName().toString());
                }
        }

        return false;
    }

    public static boolean shouldHide(File file) {
        return LoaderContext.current().dirHiderEnabled && (file != null) && shouldHidePath(file.toPath(), CMP_MODE.ANY);
    }

    public static boolean shouldHide(File base, String relPath) {
        if (!LoaderContext.current().dirHiderEnabled) return false;
        if (base != null && shouldHidePath(base.toPath(), CMP_MODE.LAST)) return true;
        if (relPath != null && shouldHidePath(Path.of(relPath), CMP_MODE.FIRST)) return true;
        return false;   
    }

    public static boolean shouldHide(URI base, File relPath) {
        if (!LoaderContext.current().dirHiderEnabled) return false;
        if (base != null && shouldHidePath(Path.of(base), CMP_MODE.LAST)) return true;
        if (relPath != null && shouldHidePath(relPath.toPath(), CMP_MODE.FIRST)) return true;
        return false;
    }
}

package io.github.karl3uwu.zombud;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * One-time copy of the config directory from its ZombieBuddy-era name ({@code ~/.zombie_buddy}) to
 * {@code ~/.krl_zombud}. Runs at agent start when no {@code config_dir} was given.
 *
 * <p>The copy happens only when the new directory doesn't exist and the old one does. The old directory is never
 * modified or deleted (upstream ZombieBuddy may still use it). Files are copied into a temporary sibling first and
 * then moved into place, so an interrupted copy doesn't leave a half-filled new directory behind.
 */
final class ConfigDirMigration {
    private ConfigDirMigration() {}

    static final String DIR_NAME = ".krl_zombud";
    static final String LEGACY_DIR_NAME = ".zombie_buddy";

    enum Result {
        /** The new directory already exists; nothing was done. */
        ALREADY_PRESENT,
        /** Neither directory exists; nothing to copy. */
        NOTHING_TO_MIGRATE,
        /** The old directory was copied to the new one. */
        COPIED,
        /** Copying failed; the new directory was not created. */
        FAILED,
    }

    static Path defaultDir() {
        return Path.of(System.getProperty("user.home"), DIR_NAME);
    }

    static Path legacyDefaultDir() {
        return Path.of(System.getProperty("user.home"), LEGACY_DIR_NAME);
    }

    static Result migrate(Path legacyDir, Path newDir) {
        if (Files.exists(newDir)) {
            return Result.ALREADY_PRESENT;
        }
        if (!Files.isDirectory(legacyDir)) {
            return Result.NOTHING_TO_MIGRATE;
        }

        Path parent = newDir.toAbsolutePath().getParent();
        Path tmp = parent.resolve(newDir.getFileName() + ".migrating-" + ProcessHandle.current().pid());
        try {
            deleteTree(tmp); // leftover from an earlier interrupted run with the same pid
            copyTree(legacyDir, tmp);
            try {
                Files.move(tmp, newDir, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, newDir);
            }
            Logger.info("Copied config directory " + legacyDir + " to " + newDir + " (the old directory is left as is)");
            return Result.COPIED;
        } catch (IOException e) {
            Logger.error("Could not copy config directory " + legacyDir + " to " + newDir + ": " + e);
            try {
                deleteTree(tmp);
            } catch (IOException ignored) {
            }
            return Result.FAILED;
        }
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    Files.copy(file, dst.resolve(src.relativize(file).toString()),
                        StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

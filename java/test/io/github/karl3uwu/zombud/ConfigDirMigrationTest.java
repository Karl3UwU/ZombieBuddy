package io.github.karl3uwu.zombud;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigDirMigrationTest {

    @TempDir
    Path home;

    @AfterEach
    void resetContext() {
        LoaderContext.reset();
    }

    private Path legacy() {
        return home.resolve(ConfigDirMigration.LEGACY_DIR_NAME);
    }

    private Path target() {
        return home.resolve(ConfigDirMigration.DIR_NAME);
    }

    private List<String> homeEntries() throws Exception {
        try (Stream<Path> s = Files.list(home)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void copiesFilesAndSubdirectories_andLeavesTheOldDirectoryAlone() throws Exception {
        Files.createDirectories(legacy().resolve("cache"));
        Files.writeString(legacy().resolve("config.json"), "{\"auto_fix_mod_order\":false}");
        Files.writeString(legacy().resolve("mod_approvals.json"), "{\"mods\":[]}");
        Files.writeString(legacy().resolve("cache/item.json"), "cached");

        assertEquals(ConfigDirMigration.Result.COPIED, ConfigDirMigration.migrate(legacy(), target()));

        assertEquals("{\"auto_fix_mod_order\":false}", Files.readString(target().resolve("config.json")));
        assertEquals("{\"mods\":[]}", Files.readString(target().resolve("mod_approvals.json")));
        assertEquals("cached", Files.readString(target().resolve("cache/item.json")));

        assertEquals("{\"auto_fix_mod_order\":false}", Files.readString(legacy().resolve("config.json")), "old files kept");
        assertTrue(Files.exists(legacy().resolve("cache/item.json")));
        assertEquals(List.of(ConfigDirMigration.DIR_NAME, ConfigDirMigration.LEGACY_DIR_NAME), homeEntries(),
            "no temporary directory is left behind");
    }

    @Test
    void doesNothingWhenTheNewDirectoryExists() throws Exception {
        Files.createDirectories(legacy());
        Files.writeString(legacy().resolve("config.json"), "old");
        Files.createDirectories(target());
        Files.writeString(target().resolve("config.json"), "new");

        assertEquals(ConfigDirMigration.Result.ALREADY_PRESENT, ConfigDirMigration.migrate(legacy(), target()));
        assertEquals("new", Files.readString(target().resolve("config.json")));
    }

    @Test
    void doesNothingWithoutAnOldDirectory() throws Exception {
        assertEquals(ConfigDirMigration.Result.NOTHING_TO_MIGRATE, ConfigDirMigration.migrate(legacy(), target()));
        assertFalse(Files.exists(target()));
    }

    @Test
    void anOldFileInsteadOfADirectoryIsNotMigrated() throws Exception {
        Files.writeString(legacy(), "not a directory");
        assertEquals(ConfigDirMigration.Result.NOTHING_TO_MIGRATE, ConfigDirMigration.migrate(legacy(), target()));
        assertFalse(Files.exists(target()));
    }

    @Test
    void runsOnlyOnce() throws Exception {
        Files.createDirectories(legacy());
        Files.writeString(legacy().resolve("config.json"), "v1");
        assertEquals(ConfigDirMigration.Result.COPIED, ConfigDirMigration.migrate(legacy(), target()));

        Files.writeString(legacy().resolve("config.json"), "v2");
        assertEquals(ConfigDirMigration.Result.ALREADY_PRESENT, ConfigDirMigration.migrate(legacy(), target()));
        assertEquals("v1", Files.readString(target().resolve("config.json")), "later changes to the old dir aren't copied");
    }

    @Test
    void defaultConfigDirIsKrlZombud_unlessConfigured() {
        assertEquals(".krl_zombud", Agent.configDir().getFileName().toString());

        LoaderContext.current().arguments.put("config_dir", home.toString());
        assertEquals(home, Agent.configDir());
    }
}

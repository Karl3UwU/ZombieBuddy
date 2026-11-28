package io.github.karl3uwu.zombud.test.session;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.github.karl3uwu.zombud.Loader;
import io.github.karl3uwu.zombud.LoaderContext;
import testjar.BrokenModTarget;

/**
 * Agent started with -Dkrl.session=session.json (made by the real CLI, see prepareSessionFixtures in build.gradle):
 * SessionTestOptional is approved with the right sha256; SessionTestMissing has a wrong sha256 in the session;
 * SessionTestArgtype has the right sha256 but no approval.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SessionModeTest {
    static final Path FIXTURE = Path.of(System.getProperty("krl.test.fixtureDir"));

    @Test
    @Order(1)
    void sessionIsActive() {
        assertTrue(io.github.karl3uwu.zombud.SessionLoader.isSessionMode());
        assertNotNull(io.github.karl3uwu.zombud.SessionLoader.session());
        assertNull(io.github.karl3uwu.zombud.SessionLoader.sessionError(), io.github.karl3uwu.zombud.SessionLoader.sessionError());
    }

    @Test
    @Order(2)
    void approvedJarIsLoadedAtPremain_byFirstLoadWeaving() {
        assertTrue(BrokenModTarget.optionalModPreMainCalled, "PreMain ran at premain");
        assertEquals(300, BrokenModTarget.valueC(), "its valid patch is installed");
        assertTrue(LoaderContext.current().firstLoadClasses().contains("testjar.BrokenModTarget"),
            "patched when first defined: " + LoaderContext.current().firstLoadClasses());
        assertFalse(LoaderContext.current().retransformedClasses().contains("testjar.BrokenModTarget"),
            "never retransformed: " + LoaderContext.current().retransformedClasses());
    }

    @Test
    @Order(3)
    void wrongSha256_isSkipped() {
        assertFalse(BrokenModTarget.missingModPreMainCalled);
        assertEquals(1, BrokenModTarget.valueA());
    }

    @Test
    @Order(4)
    void unapprovedJar_isSkipped() {
        assertFalse(BrokenModTarget.argTypeModPreMainCalled);
        assertEquals(2, BrokenModTarget.valueB());
    }

    @Test
    @Order(5)
    void gameLoadModsPath_usesTheSessionList_andRunsMainOnce() throws Exception {
        // what the ZomboidFileSystem.loadMods(List) patch does, twice (the game reloads the list on some screens)
        List<String> gameList = new ArrayList<>(List.of("SomethingFromDefaultTxt"));
        List<String> used = Loader.modListFor(gameList);
        assertEquals(List.of("SessionTestOptional", "SessionTestMissing", "SessionTestArgtype"), used);
        assertEquals(List.of("SomethingFromDefaultTxt"), gameList, "the game's own list is not modified");

        Loader.loadMods(used);
        Loader.afterLoadMods();
        Loader.loadMods(used);
        Loader.afterLoadMods();
        assertEquals(1, BrokenModTarget.optionalModMainCalls, "Main runs once, no second Java load");
        assertFalse(BrokenModTarget.missingModPreMainCalled, "skipped jars stay skipped on the in-game path");
    }

    @Test
    @Order(6)
    void stageFileAndReport() throws Exception {
        List<String> stages = Files.readAllLines(FIXTURE.resolve("session.stage"));
        assertEquals(2, stages.size(), "premain and mods-loaded, each once: " + stages);
        assertTrue(stages.get(0).endsWith(" premain"), stages.get(0));
        assertTrue(stages.get(1).endsWith(" mods-loaded"), stages.get(1));
        java.time.OffsetDateTime.parse(stages.get(0).substring(0, stages.get(0).indexOf(' ')));

        String report = Files.readString(FIXTURE.resolve("session-report").resolve("patch-report.json"));
        assertTrue(report.contains("\"SHA256_MISMATCH\""), report);
        assertTrue(report.contains("needs approval in the launcher"), report);
        assertTrue(report.contains("\"session\""), report);
        assertTrue(report.contains("\"javaModsLoaded\": 1"), report);
    }
}

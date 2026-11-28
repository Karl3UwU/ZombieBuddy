package io.github.karl3uwu.zombud.test.session;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.karl3uwu.zombud.Loader;
import testjar.BrokenModTarget;

/** session-safe.json: same mods, safeMode true. No Java mod loads; the mod list still comes from the session. */
public class SessionSafeModeTest {
    static final Path FIXTURE = Path.of(System.getProperty("krl.test.fixtureDir"));

    @Test
    void noJavaModsInSafeMode() throws Exception {
        assertTrue(io.github.karl3uwu.zombud.SessionLoader.isSessionMode());
        assertTrue(io.github.karl3uwu.zombud.SessionLoader.session().safeMode());
        assertFalse(BrokenModTarget.optionalModPreMainCalled);
        assertEquals(3, BrokenModTarget.valueC(), "approved jar not loaded in safe mode");

        List<String> used = Loader.modListFor(List.of("X"));
        assertEquals(3, used.size(), "Lua mods still come from the session");
        Loader.loadMods(used);
        assertEquals(0, BrokenModTarget.optionalModMainCalls);

        List<String> stages = Files.readAllLines(FIXTURE.resolve("session-safe.stage"));
        assertTrue(stages.get(0).endsWith(" premain"), stages.toString());
    }
}

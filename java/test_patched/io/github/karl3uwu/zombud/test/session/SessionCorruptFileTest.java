package io.github.karl3uwu.zombud.test.session;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.karl3uwu.zombud.Loader;
import testjar.BrokenModTarget;

/** session-corrupt.json isn't JSON: fail open. The game runs, no Java mods, the game's own mod list, error reported. */
public class SessionCorruptFileTest {

    @Test
    void failsOpen() throws Exception {
        assertTrue(io.github.karl3uwu.zombud.SessionLoader.isSessionMode());
        assertNull(io.github.karl3uwu.zombud.SessionLoader.session());
        String error = io.github.karl3uwu.zombud.SessionLoader.sessionError();
        assertNotNull(error);
        assertTrue(error.contains("invalid"), error);

        assertFalse(BrokenModTarget.optionalModPreMainCalled);
        assertEquals(List.of("FromGame"), Loader.modListFor(List.of("FromGame")), "no session list to apply");
        Loader.loadMods(List.of("FromGame")); // the in-game Java path stays off too
        assertEquals(0, BrokenModTarget.optionalModMainCalls);

        // no usable stage file / report dir: the report goes to the config dir
        Path report = Path.of(System.getProperty("krl.test.configDir"), "patch-report.json");
        String json = Files.readString(report);
        assertTrue(json.contains("\"SESSION_ERROR\""), json);
        assertTrue(json.contains("\"ALL_JAVA_MODS_OFF\""), json);
    }
}

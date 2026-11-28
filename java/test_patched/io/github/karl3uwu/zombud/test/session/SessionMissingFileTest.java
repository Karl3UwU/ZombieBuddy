package io.github.karl3uwu.zombud.test.session;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import testjar.BrokenModTarget;

/** KRL_SESSION (environment, not the property) points at a file that doesn't exist: fail open. */
public class SessionMissingFileTest {

    @Test
    void failsOpen() throws Exception {
        assertNull(System.getProperty("krl.session"), "this run uses the environment variable");
        assertTrue(io.github.karl3uwu.zombud.SessionLoader.isSessionMode());
        assertNull(io.github.karl3uwu.zombud.SessionLoader.session());
        assertTrue(io.github.karl3uwu.zombud.SessionLoader.sessionError().contains("can't be read"),
            io.github.karl3uwu.zombud.SessionLoader.sessionError());
        assertFalse(BrokenModTarget.optionalModPreMainCalled);

        String json = Files.readString(Path.of(System.getProperty("krl.test.configDir"), "patch-report.json"));
        assertTrue(json.contains("\"SESSION_ERROR\""), json);
    }
}

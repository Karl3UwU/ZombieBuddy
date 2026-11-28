package io.github.karl3uwu.zombud.session;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The reader against schema/examples (the same files the docs show and SessionSchemaTest validates). */
class SessionFileTest {
    static final Path EXAMPLES = Path.of("..", "schema", "examples");

    static Stream<Path> invalidExamples() throws IOException {
        return Files.list(EXAMPLES.resolve("invalid")).sorted();
    }

    @Test
    void windowsExample() throws Exception {
        Session s = SessionFile.read(EXAMPLES.resolve("valid-windows.json"));
        assertEquals(1, s.schema());
        assertEquals("42.22.2", s.gameBuild());
        assertEquals(Session.IntentKind.CONTINUE, s.intent().kind());
        assertEquals("Sandbox/2026-11-27_20-15-03", s.intent().save());
        assertEquals(List.of("KRLZomBud", "BetterFPS", "SomeLuaMod"), s.modIds(), "leading backslash dropped");
        Session.Mod fps = s.mods().get(1);
        assertEquals(3022543997L, fps.workshopId());
        assertEquals(1, fps.java().size());
        assertEquals("com.example.betterfps", fps.java().get(0).pkg());
        assertTrue(fps.java().get(0).jar().startsWith("C:\\Program Files (x86)"));
        assertFalse(s.safeMode());
        assertTrue(s.coreFixes().enabled());
        assertEquals(Boolean.FALSE, s.coreFixes().overrides().get("fix-macos-retina"));
    }

    @Test
    void minimalExample() throws Exception {
        Session s = SessionFile.read(EXAMPLES.resolve("valid-minimal.json"));
        assertTrue(s.safeMode());
        assertTrue(s.mods().isEmpty());
        assertTrue(s.coreFixes().overrides().isEmpty());
        assertEquals(Session.IntentKind.NEW, s.intent().kind());
    }

    @ParameterizedTest
    @MethodSource("invalidExamples")
    void invalidExamplesAreRejected(Path file) {
        SessionFile.InvalidSessionException e = assertThrows(SessionFile.InvalidSessionException.class, () -> SessionFile.read(file));
        assertFalse(e.errors().isEmpty());
    }

    @Test
    void unknownSchemaVersion_isItsOwnError() {
        assertThrows(SessionFile.UnsupportedSchemaException.class,
            () -> SessionFile.read(EXAMPLES.resolve("invalid").resolve("schema-2.json")));
    }

    @Test
    void allProblemsAreReportedTogether() {
        String json = """
            { "schema": 1, "id": "", "created": "x", "gameBuild": "42.22.2", "loaderVersion": "1",
              "intent": { "kind": "join" }, "mods": [ { "id": "A", "dir": "rel" }, { "id": "\\\\A", "dir": "/a" } ],
              "safeMode": "no", "coreFixes": { "enabled": true }, "stageFile": "/s", "reportDir": "/r" }
            """;
        var e = assertThrows(SessionFile.InvalidSessionException.class, () -> SessionFile.parse(json));
        String all = String.join("\n", e.errors());
        assertTrue(all.contains("id: must not be empty"), all);
        assertTrue(all.contains("created: not an RFC 3339"), all);
        assertTrue(all.contains("intent.address: required"), all);
        assertTrue(all.contains("mods[0].dir: must be an absolute path"), all);
        assertTrue(all.contains("mods[1].id: \"A\" is listed twice"), all);
        assertTrue(all.contains("safeMode: required true or false"), all);
    }

    @Test
    void utf8BomIsTolerated() throws Exception {
        String json = "\uFEFF" + Files.readString(EXAMPLES.resolve("valid-minimal.json"));
        assertEquals("s1", SessionFile.parse(json).id());
    }

    @Test
    void writeThenReadGivesTheSameSession() throws Exception {
        Session s = SessionFile.read(EXAMPLES.resolve("valid-windows.json"));
        Session again = SessionFile.parse(SessionFile.toJson(s));
        assertEquals(s, again);
    }

    @Test
    void absolutePathForms() {
        assertTrue(SessionFile.isAbsolutePath("/home/a"));
        assertTrue(SessionFile.isAbsolutePath("C:\\a"));
        assertTrue(SessionFile.isAbsolutePath("c:/a"));
        assertTrue(SessionFile.isAbsolutePath("\\\\server\\share\\a"));
        assertFalse(SessionFile.isAbsolutePath("a/b"));
        assertFalse(SessionFile.isAbsolutePath("C:a"));
        assertFalse(SessionFile.isAbsolutePath(""));
    }
}

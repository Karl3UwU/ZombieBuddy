package io.github.karl3uwu.zombud.session;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code session create/validate} against fixture mod folders laid out like the game's. */
class SessionCliTest {

    @TempDir
    Path tmp;

    Path userDir;
    Path gameDir;
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return SessionCli.run(args, new PrintStream(out, true), new PrintStream(err, true));
    }

    private static void write(Path p, String content) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private static void jar(Path p, String pkgPath) throws Exception {
        Files.createDirectories(p.getParent());
        try (OutputStream o = Files.newOutputStream(p); JarOutputStream j = new JarOutputStream(o)) {
            j.putNextEntry(new JarEntry(pkgPath + "/Main.class"));
            j.write(new byte[] {(byte) 0xCA, (byte) 0xFE});
            j.closeEntry();
        }
    }

    @BeforeEach
    void fixtures() throws Exception {
        userDir = tmp.resolve("Zomboid");
        Path steamapps = tmp.resolve("steamapps");
        gameDir = steamapps.resolve("common").resolve("ProjectZomboid");
        Files.createDirectories(gameDir);

        // local B42 mod with a Java jar in the 42 folder
        Path a = userDir.resolve("mods").resolve("ModA");
        write(a.resolve("common/mod.info"), "name=Mod A\nid=ModA\n");
        write(a.resolve("42/mod.info"), "name=Mod A\nid=ModA\njavaJarFile=media/java/a.jar\njavaPkgName=com.example.a\n");
        jar(a.resolve("42/media/java/a.jar"), "com/example/a");

        // local mod with a folder newer than the game build: 42.13 must be picked, not 42.30
        Path b = userDir.resolve("mods").resolve("ModB");
        write(b.resolve("common/mod.info"), "id=ModB\n");
        write(b.resolve("42.13/mod.info"), "id=ModB\n");
        write(b.resolve("42.30/mod.info"), "id=ModB\n");

        // Workshop mod next to the game
        Path w = steamapps.resolve("workshop/content/108600/123456/mods/WorkshopMod");
        write(w.resolve("42/mod.info"), "id=\\WMod\n");

        // KRL ZomBud itself: its jar is never listed (it runs as the agent)
        Path self = userDir.resolve("mods").resolve("KRLZomBud");
        write(self.resolve("42/mod.info"), "id=KRLZomBud\njavaJarFile=../libs/KRLZomBud.jar\njavaPkgName=io.github.karl3uwu.zombud\n");
        jar(self.resolve("libs/KRLZomBud.jar"), "io/github/karl3uwu/zombud");
    }

    @Test
    void createWritesAValidSession() throws Exception {
        Path outFile = tmp.resolve("out").resolve("s.json");
        int code = run("create", "--mods", "ModA,\\WMod,ModB,KRLZomBud", "--game-dir", gameDir.toString(),
            "--user-dir", userDir.toString(), "--game-build", "42.22.2", "--out", outFile.toString(), "--id", "test-1",
            "--intent", "continue", "--save", "Sandbox/x");
        assertEquals(0, code, err.toString());

        Session s = SessionFile.read(outFile);
        assertEquals("test-1", s.id());
        assertEquals("42.22.2", s.gameBuild());
        assertEquals(java.util.List.of("ModA", "WMod", "ModB", "KRLZomBud"), s.modIds());
        assertEquals(Session.IntentKind.CONTINUE, s.intent().kind());

        Session.Mod a = s.mods().get(0);
        assertTrue(a.dir().endsWith("42"), a.dir());
        assertEquals(1, a.java().size());
        assertEquals("com.example.a", a.java().get(0).pkg());
        assertEquals(io.github.karl3uwu.zombud.Utils.sha256Hex(Path.of(a.java().get(0).jar())), a.java().get(0).sha256());

        Session.Mod w = s.mods().get(1);
        assertEquals(123456L, w.workshopId());
        assertTrue(s.mods().get(2).dir().endsWith("42.13"), s.mods().get(2).dir());
        assertTrue(s.mods().get(3).java().isEmpty(), "KRL ZomBud's own jar is not a mod jar");

        assertEquals(outFile.getParent().resolve("test-1.stage").toString(), s.stageFile());
        assertEquals(outFile.getParent().toString(), s.reportDir());

        assertEquals(0, run("validate", outFile.toString()));
    }

    @Test
    void safeModeFlag() throws Exception {
        Path outFile = tmp.resolve("s.json");
        assertEquals(0, run("create", "--mods", "ModB", "--user-dir", userDir.toString(), "--game-build", "42.22.2",
            "--out", outFile.toString(), "--safe-mode"), err.toString());
        assertTrue(SessionFile.read(outFile).safeMode());
    }

    @Test
    void missingModFails() {
        assertEquals(1, run("create", "--mods", "ModA,NoSuchMod", "--user-dir", userDir.toString(),
            "--game-build", "42.22.2", "--out", tmp.resolve("x.json").toString()));
        assertTrue(err.toString().contains("NoSuchMod"));
        assertFalse(Files.exists(tmp.resolve("x.json")));
    }

    @Test
    void gameBuildIsRequiredWithoutAGameJar() {
        assertEquals(1, run("create", "--mods", "ModA", "--user-dir", userDir.toString(), "--game-dir", gameDir.toString(),
            "--out", tmp.resolve("x.json").toString()));
        assertTrue(err.toString().contains("--game-build"));
    }

    @Test
    void validateReportsEveryProblem() throws Exception {
        assertEquals(1, run("validate", SessionFileTest.EXAMPLES.resolve("invalid").resolve("bad-sha256.json").toString()));
        assertTrue(err.toString().contains("sha256"), err.toString());
    }

    @Test
    void usageErrors() {
        assertEquals(2, run());
        assertEquals(2, run("frobnicate"));
        assertEquals(2, run("create"));
        assertEquals(2, run("create", "--mods"));
    }
}

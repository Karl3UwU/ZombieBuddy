package io.github.karl3uwu.zombud;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.karl3uwu.zombud.PatchChecker.Kind;
import io.github.karl3uwu.zombud.PatchChecker.ModResult;
import io.github.karl3uwu.zombud.PatchChecker.PatchResult;
import io.github.karl3uwu.zombud.checkfixtures.CheckPatches;
import io.github.karl3uwu.zombud.transformers.Pipeline;
import io.github.karl3uwu.zombud.transformers.TransformedJar;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;

/**
 * PatchChecker against hand-written targets ({@code checkfixtures.targets.CheckTarget}). The fixture patches go
 * through the same jar transform as a real mod (so @Patch.* become ByteBuddy annotations) and are defined on their
 * own class loader, like ModJarInjector does.
 */
class PatchCheckerTest {
    private static final String PKG = "io.github.karl3uwu.zombud.checkfixtures";

    private static TransformedJar jar;
    private static ClassLoader modLoader;

    @BeforeAll
    static void transformFixtures() throws Exception {
        List<Class<?>> classes = new ArrayList<>();
        classes.add(CheckPatches.class);
        for (Class<?> c : CheckPatches.class.getDeclaredClasses()) classes.add(c);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(bytes)) {
            for (Class<?> c : classes) {
                String res = c.getName().replace('.', '/') + ".class";
                try (InputStream in = PatchCheckerTest.class.getClassLoader().getResourceAsStream(res)) {
                    jos.putNextEntry(new JarEntry(res));
                    jos.write(in.readAllBytes());
                    jos.closeEntry();
                }
            }
        }
        jar = Pipeline.transformPatchJar(bytes.toByteArray(), PKG);
        modLoader = new ByteArrayClassLoader.ChildFirst(PatchCheckerTest.class.getClassLoader(), jar.classes(),
            ByteArrayClassLoader.PersistenceHandler.MANIFEST);
    }

    private static ModResult check(Map<String, String> otherOwners, String... simpleNames) throws Exception {
        List<Class<?>> patches = new ArrayList<>();
        for (String n : simpleNames) {
            patches.add(modLoader.loadClass(CheckPatches.class.getName() + "$" + n));
        }
        PatchChecker.Env env = new PatchChecker.Env(jar, modLoader, PatchCheckerTest.class.getClassLoader(), name -> null);
        return PatchChecker.check(patches, env, "TestMod", otherOwners::get);
    }

    private static ModResult check(String... simpleNames) throws Exception {
        return check(Map.of(), simpleNames);
    }

    private static PatchResult only(ModResult r) {
        assertEquals(1, r.patches().size());
        return r.patches().get(0);
    }

    private static List<Kind> kinds(PatchResult p) {
        return p.problems().stream().map(PatchChecker.Problem::kind).toList();
    }

    @Test
    void validAdvice_hasNoProblems() throws Exception {
        PatchResult p = only(check("ValidAdvice"));
        assertEquals(List.of(), p.problems());
        assertFalse(p.failed());
    }

    @Test
    void missingClass() throws Exception {
        PatchResult p = only(check("MissingClass"));
        assertEquals(List.of(Kind.CLASS_MISSING), kinds(p));
        assertTrue(p.failed());
    }

    @Test
    void missingMethod() throws Exception {
        PatchResult p = only(check("MissingMethod"));
        assertEquals(List.of(Kind.METHOD_MISSING), kinds(p));
    }

    @Test
    void descriptorMismatch_listsTheRealOverloads() throws Exception {
        PatchResult p = only(check("DescriptorMismatch"));
        assertEquals(List.of(Kind.DESCRIPTOR_MISMATCH), kinds(p));
        assertTrue(p.problems().get(0).message().contains("add(II)I"), p.problems().get(0).message());
    }

    @Test
    void descriptorPicksOneOverload_noAmbiguityWarning() throws Exception {
        PatchResult p = only(check("DescriptorPicksOverload"));
        assertEquals(List.of(), p.problems());
    }

    @Test
    void wrongArgumentType() throws Exception {
        PatchResult p = only(check("WrongArgumentType"));
        assertEquals(List.of(Kind.ADVICE_BIND), kinds(p));
    }

    @Test
    void argumentIndexOutOfRange() throws Exception {
        PatchResult p = only(check("ArgumentIndexOutOfRange"));
        assertEquals(List.of(Kind.ADVICE_BIND), kinds(p));
    }

    @Test
    void returnTypeMismatch_isCaughtByTheDryRun() throws Exception {
        PatchResult p = only(check("ReturnTypeMismatch"));
        assertEquals(List.of(Kind.ADVICE_BIND), kinds(p));
    }

    @Test
    void thisOnStaticMethod_isCaughtByTheDryRun() throws Exception {
        PatchResult p = only(check("ThisOnStaticMethod"));
        assertEquals(List.of(Kind.ADVICE_BIND), kinds(p));
    }

    @Test
    void ambiguousOverloads_isOnlyAWarning() throws Exception {
        ModResult r = check("AmbiguousOverloads");
        PatchResult p = only(r);
        assertEquals(List.of(Kind.AMBIGUOUS), kinds(p));
        assertFalse(p.failed());
        assertFalse(r.requiredFailure());
    }

    @Test
    void requiredFailure_turnsOffTheMod_optionalFailureOnlySkipsThePatch() throws Exception {
        ModResult required = check("ValidAdvice", "MissingMethod");
        assertTrue(required.requiredFailure());

        ModResult optional = check("ValidAdvice", "OptionalMissingMethod");
        assertFalse(optional.requiredFailure());
        assertEquals(List.of(CheckPatches.class.getName() + "$OptionalMissingMethod"), optional.failedPatchClasses());
    }

    @Test
    void twoDelegationsOnOneMethod_conflict() throws Exception {
        ModResult r = check("DelegationA", "DelegationB");
        assertEquals(List.of(), r.patches().get(0).problems());
        assertEquals(List.of(Kind.DELEGATION_CONFLICT), kinds(r.patches().get(1)));
        assertTrue(r.patches().get(1).problems().get(0).message().contains("DelegationA"));
    }

    @Test
    void delegationAlreadyInstalledByAnotherMod_conflict() throws Exception {
        String key = PatchChecker.delegationKey(
            "io.github.karl3uwu.zombud.checkfixtures.targets.CheckTarget", "greet", "");
        ModResult r = check(Map.of(key, "OtherMod (other.Patch)"), "DelegationA");
        PatchResult p = only(r);
        assertEquals(List.of(Kind.DELEGATION_CONFLICT), kinds(p));
        assertTrue(p.problems().get(0).message().contains("OtherMod"));
    }

    @Test
    void validDelegation_passesTheDryRun() throws Exception {
        PatchResult p = only(check("DelegationB"));
        assertEquals(List.of(), p.problems());
    }
}

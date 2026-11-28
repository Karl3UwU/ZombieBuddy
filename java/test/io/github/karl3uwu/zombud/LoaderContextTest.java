package io.github.karl3uwu.zombud;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoaderContextTest {

    @BeforeEach
    @AfterEach
    void resetContext() {
        LoaderContext.reset();
    }

    @Test
    void currentWithoutStart_isOneDefaultContextWithoutInstrumentation() {
        LoaderContext a = LoaderContext.current();
        LoaderContext b = LoaderContext.current();
        assertSame(a, b);
        assertNull(a.instrumentation());
        assertNull(Loader.getInstrumentation());
        assertEquals(ModApprovals.POLICY_PROMPT, a.jarPolicy);
        assertFalse(a.jarPolicyLocked);
    }

    @Test
    void start_replacesTheDefaultContext() {
        LoaderContext before = LoaderContext.current();
        before.arguments.put("verbosity", "2");

        Instrumentation inst = mock(Instrumentation.class);
        LoaderContext started = LoaderContext.start(inst);

        assertNotSame(before, started);
        assertSame(started, LoaderContext.current());
        assertSame(inst, Loader.getInstrumentation());
        assertTrue(started.arguments.isEmpty(), "a started context begins with no arguments");
        assertSame(started.arguments, Agent.arguments());
    }

    @Test
    void reset_dropsTheContext() {
        LoaderContext started = LoaderContext.start(mock(Instrumentation.class));
        LoaderContext.reset();
        LoaderContext fresh = LoaderContext.current();
        assertNotSame(started, fresh);
        assertNull(fresh.instrumentation());
    }

    @Test
    void policyIsWriteOncePerContext() {
        Loader.setPolicy("deny-new");
        assertEquals("deny-new", Loader.getPolicy());

        Loader.setPolicy("allow-all");
        assertEquals("deny-new", Loader.getPolicy(), "a locked policy can't be changed");

        LoaderContext.start(null);
        assertEquals("prompt", Loader.getPolicy(), "a new context starts unlocked");
        Loader.setPolicy("allow-all");
        assertEquals("allow-all", Loader.getPolicy());
    }

    @Test
    void invalidPolicyDoesNotLock() {
        Loader.setPolicy("bogus");
        assertEquals("prompt", Loader.getPolicy());
        assertFalse(LoaderContext.current().jarPolicyLocked);
    }

    @Test
    void replaceConfig_marksDirtyOnlyOnChange() {
        LoaderContext lc = LoaderContext.current();
        assertFalse(lc.replaceConfig(lc.config));
        assertFalse(lc.configDirty);

        Config next = lc.config.withAutoFixModOrder(!lc.config.auto_fix_mod_order());
        assertTrue(lc.replaceConfig(next));
        assertTrue(lc.configDirty);
        assertSame(next, lc.config);
    }

    @Test
    void sessionDecisionsAndLoadStatusDoNotLeakBetweenContexts() {
        LoaderContext first = LoaderContext.start(null);
        first.sessionJarDecisions.put("abc", Boolean.TRUE);
        Path jar = Path.of("some.jar");
        first.jarLoadStatus.put(jar, new Loader.JavaModLoadState("Mod", jar,
            new ModFlags(ModFlags.MF_ACTIVE), "loaded", "abc", Boolean.TRUE));
        assertEquals(1, Loader.getActiveJavaMods().size());

        LoaderContext second = LoaderContext.start(null);
        assertNull(second.sessionJarDecisions.get("abc"));
        assertTrue(Loader.getActiveJavaMods().isEmpty());
    }

    @Test
    void gameFacingFlagsGoThroughCurrent() {
        assertFalse(LoaderContext.current().hasDoLoadingText());
        LoaderContext.current().setHasDoLoadingText(true);
        assertTrue(LoaderContext.current().hasDoLoadingText());

        DirHider.setEnabled(false);
        assertFalse(DirHider.isEnabled());
        LoaderContext.reset();
        assertTrue(DirHider.isEnabled(), "settings belong to the context");
    }
}

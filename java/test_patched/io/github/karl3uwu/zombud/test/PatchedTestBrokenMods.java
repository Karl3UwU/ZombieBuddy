package io.github.karl3uwu.zombud.test;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import testjar.BrokenModTarget;

/**
 * Three deliberately broken mods are loaded next to testpatches (see testbroken/ and build.gradle):
 * <ul>
 *   <li>missing: a valid patch plus one whose method doesn't exist (required)</li>
 *   <li>argtype: a valid patch plus one with a wrong @Argument type (required)</li>
 *   <li>optional: a valid patch plus a broken optional one</li>
 * </ul>
 * The JVM keeps running (we're here), required failures turn off the whole mod, optional ones only that patch.
 */
public class PatchedTestBrokenMods {

    @Test
    void requiredMissingMethod_turnsOffTheWholeMod() {
        assertEquals(1, BrokenModTarget.valueA(), "the mod's valid patch must not be installed");
        assertFalse(BrokenModTarget.missingModPreMainCalled, "the mod's PreMain must not run");
    }

    @Test
    void requiredWrongArgumentType_turnsOffTheWholeMod() {
        assertEquals(2, BrokenModTarget.valueB(), "the mod's valid patch must not be installed");
        assertFalse(BrokenModTarget.argTypeModPreMainCalled, "the mod's PreMain must not run");
        assertEquals(7, BrokenModTarget.takeInt(7));
    }

    @Test
    void optionalFailure_skipsOnlyThatPatch() {
        assertEquals(300, BrokenModTarget.valueC(), "the mod's valid patch is installed");
        assertTrue(BrokenModTarget.optionalModPreMainCalled, "the mod's PreMain runs");
    }

    @Test
    void reportListsEveryProblem() throws Exception {
        Path report = Path.of(".config", "patch-report.json");
        assertTrue(Files.isRegularFile(report), "patch-report.json is written to the config dir");
        String json = Files.readString(report);

        assertTrue(json.contains("\"disabledMods\""));
        assertTrue(json.contains("\"io.github.karl3uwu.zombud.testbroken.missing\""), json);
        assertTrue(json.contains("\"io.github.karl3uwu.zombud.testbroken.argtype\""), json);

        assertEntry(json, "testbroken.missing.Patches$Broken", "METHOD_MISSING", "MOD_DISABLED");
        assertEntry(json, "testbroken.argtype.Patches$Broken", "ADVICE_BIND", "MOD_DISABLED");
        assertEntry(json, "testbroken.optional.Patches$BrokenButOptional", "METHOD_MISSING", "PATCH_SKIPPED");

        // the optional-only mod is not listed as turned off
        int disabledStart = json.indexOf("\"disabledMods\"");
        int disabledEnd = json.indexOf(']', disabledStart);
        assertFalse(json.substring(disabledStart, disabledEnd).contains("testbroken.optional"));
    }

    private static void assertEntry(String json, String patchClassSuffix, String kind, String action) {
        int at = json.indexOf(patchClassSuffix + "\"");
        assertTrue(at >= 0, "no entry for " + patchClassSuffix);
        int end = json.indexOf('}', at);
        String entry = json.substring(at, end);
        assertTrue(entry.contains("\"kind\": \"" + kind + "\""), entry);
        assertTrue(entry.contains("\"actionTaken\": \"" + action + "\""), entry);
    }
}

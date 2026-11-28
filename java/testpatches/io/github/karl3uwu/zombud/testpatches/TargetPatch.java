package io.github.karl3uwu.zombud.testpatches;

import io.github.karl3uwu.zombud.api.Patch;
import testjar.CustomObject;

public class TargetPatch {
    @Patch(className = "testjar.TargetClass", methodName = "get_42")
    public class TargetPatchGet42 {
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) int returnValue) {
            returnValue = 999;
        }
    }

    @Patch(className = "testjar.TargetClass", methodName = "getString")
    public class TargetPatchString {
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) String returnValue) {
            returnValue = returnValue + " world";
        }
    }

    @Patch(className = "testjar.TargetClass", methodName = "getCustomObject")
    public class TargetPatchCustomObject {
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) CustomObject returnValue) {
            returnValue = new CustomObject(200, "patched");
        }
    }

    @Patch(className = "testjar.TargetClass", methodName = "getStringToNull")
    public class TargetPatchStringToNull {
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) String returnValue) {
            returnValue = null;
        }
    }

    // should trigger warnings:
    //  - not existing method
    //  - non-void return type
    // optional: this patch is broken on purpose; a required one would turn off the whole test mod
    @Patch(className = "testjar.TargetClass", methodName = "notExistingMethod", optional = true)
    public class BadTargetPatch {
        @Patch.OnExit
        public static int exit() {
            return 0;
        }
    }

    // Test strictMatch=false (default) - should match any method
    @Patch(className = "testjar.MatchNoArgsTarget", methodName = "getValue", strictMatch = false)
    public class TargetPatchMatchNoArgsFalse {
        @Patch.OnExit
        public static void exit() {
            testjar.MatchNoArgsTarget.patchCalled = "match_any";
        }
    }

    // Test strictMatch=true - should match only methods with no parameters
    @Patch(className = "testjar.MatchNoArgsTarget", methodName = "getValue", strictMatch = true)
    public class TargetPatchMatchNoArgsTrue {
        @Patch.OnExit
        public static void exit() {
            testjar.MatchNoArgsTarget.patchCalled = "match_no_args_only";
        }
    }
}

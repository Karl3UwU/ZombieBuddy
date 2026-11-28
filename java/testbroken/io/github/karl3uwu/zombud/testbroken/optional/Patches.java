package io.github.karl3uwu.zombud.testbroken.optional;

import io.github.karl3uwu.zombud.api.Patch;

/** One valid patch and one broken optional patch: only the broken one is skipped. */
public class Patches {
    @Patch(className = "testjar.BrokenModTarget", methodName = "valueC")
    public static class Valid {
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) int ret) {
            ret = 300;
        }
    }

    @Patch(className = "testjar.BrokenModTarget", methodName = "removedInThisGameVersion", optional = true)
    public static class BrokenButOptional {
        @Patch.OnEnter
        public static void enter() {}
    }
}

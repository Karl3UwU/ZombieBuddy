package io.github.karl3uwu.zombud.testbroken.missing;

import io.github.karl3uwu.zombud.api.Patch;

/** One valid patch and one whose method doesn't exist: the whole mod must stay off. */
public class Patches {
    @Patch(className = "testjar.BrokenModTarget", methodName = "valueA")
    public static class Valid {
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) int ret) {
            ret = 100;
        }
    }

    @Patch(className = "testjar.BrokenModTarget", methodName = "removedInThisGameVersion")
    public static class Broken {
        @Patch.OnEnter
        public static void enter() {}
    }
}

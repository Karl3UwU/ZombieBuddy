package io.github.karl3uwu.zombud.testbroken.argtype;

import io.github.karl3uwu.zombud.api.Patch;

/** One valid patch and one with a wrong @Argument type: the whole mod must stay off. */
public class Patches {
    @Patch(className = "testjar.BrokenModTarget", methodName = "valueB")
    public static class Valid {
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) int ret) {
            ret = 200;
        }
    }

    @Patch(className = "testjar.BrokenModTarget", methodName = "takeInt")
    public static class Broken {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) String notAnInt) {}
    }
}

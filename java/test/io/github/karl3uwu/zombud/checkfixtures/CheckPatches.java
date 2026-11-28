package io.github.karl3uwu.zombud.checkfixtures;

import io.github.karl3uwu.zombud.api.Patch;

/** Patches for PatchCheckerTest. Each nested class is one @Patch; the test picks which ones form a "mod". */
public class CheckPatches {
    static final String T = "io.github.karl3uwu.zombud.checkfixtures.targets.CheckTarget";

    @Patch(className = T, methodName = "add")
    public static class ValidAdvice {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) int a, @Patch.Argument(1) int b) {}
    }

    @Patch(className = "io.github.karl3uwu.zombud.checkfixtures.targets.NoSuchClass", methodName = "x")
    public static class MissingClass {
        @Patch.OnEnter
        public static void enter() {}
    }

    @Patch(className = T, methodName = "nope")
    public static class MissingMethod {
        @Patch.OnEnter
        public static void enter() {}
    }

    @Patch(className = T, methodName = "nope", optional = true)
    public static class OptionalMissingMethod {
        @Patch.OnEnter
        public static void enter() {}
    }

    @Patch(className = T, methodName = "add", descriptor = "(JJ)J")
    public static class DescriptorMismatch {
        @Patch.OnEnter
        public static void enter() {}
    }

    @Patch(className = T, methodName = "log", descriptor = "(Ljava/lang/String;)V")
    public static class DescriptorPicksOverload {
        @Patch.OnEnter
        public static void enter() {}
    }

    @Patch(className = T, methodName = "add")
    public static class WrongArgumentType {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) String a) {}
    }

    @Patch(className = T, methodName = "add")
    public static class ArgumentIndexOutOfRange {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(5) int x) {}
    }

    @Patch(className = T, methodName = "greet")
    public static class ReturnTypeMismatch {
        @Patch.OnExit
        public static void exit(@Patch.Return Integer r) {}
    }

    @Patch(className = T, methodName = "name")
    public static class ThisOnStaticMethod {
        @Patch.OnEnter
        public static void enter(@Patch.This Object self) {}
    }

    @Patch(className = T, methodName = "log")
    public static class AmbiguousOverloads {
        @Patch.OnEnter
        public static void enter() {}
    }

    @Patch(className = T, methodName = "greet", isAdvice = false)
    public static class DelegationA {
        public static String greet(@Patch.Argument(0) String who) {
            return "A " + who;
        }
    }

    @Patch(className = T, methodName = "greet", isAdvice = false)
    public static class DelegationB {
        public static String greet(@Patch.Argument(0) String who) {
            return "B " + who;
        }
    }
}

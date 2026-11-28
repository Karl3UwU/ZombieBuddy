package io.github.karl3uwu.zombud.checkfixtures.targets;

/** Hand-written patch target for PatchCheckerTest (no game code). */
public class CheckTarget {
    public int value;

    public int add(int a, int b) {
        return a + b;
    }

    public void log(String s) {
    }

    public void log(int level, String s) {
    }

    public static String name() {
        return "target";
    }

    public String greet(String who) {
        return "hi " + who;
    }
}

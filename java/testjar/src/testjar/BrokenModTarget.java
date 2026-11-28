package testjar;

/** Targets for the deliberately broken test mods (test_patched, PatchedTestBrokenMods). */
public class BrokenModTarget {
    public static boolean missingModPreMainCalled;
    public static boolean argTypeModPreMainCalled;
    public static boolean optionalModPreMainCalled;
    public static int optionalModMainCalls;

    public static int valueA() { return 1; }
    public static int valueB() { return 2; }
    public static int valueC() { return 3; }
    public static int takeInt(int x) { return x; }
}

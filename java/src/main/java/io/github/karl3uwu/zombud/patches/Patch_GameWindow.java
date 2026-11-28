package io.github.karl3uwu.zombud.patches;

import io.github.karl3uwu.zombud.api.Callbacks;
import io.github.karl3uwu.zombud.LoaderContext;
import io.github.karl3uwu.zombud.api.Patch;

public class Patch_GameWindow {
    @Patch(className = "zombie.GameWindow", methodName = "init")
    public static class Patch_init {
        @Patch.OnExit
        static void exit() {
            Callbacks.onGameInitComplete.run();
        }
    }

    @Patch(className = "zombie.GameWindow", methodName = "DoLoadingText")
    public static class Patch_DoLoadingText {
        @Patch.OnExit
        static void exit(String text) {
            LoaderContext.current().setHasDoLoadingText(true);
        }
    }
}

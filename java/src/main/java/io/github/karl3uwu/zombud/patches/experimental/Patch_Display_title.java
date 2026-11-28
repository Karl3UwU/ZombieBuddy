package io.github.karl3uwu.zombud.patches.experimental;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import io.github.karl3uwu.zombud.Agent;
import io.github.karl3uwu.zombud.api.Patch;

@Patch(className = "org.lwjglx.opengl.Display", methodName = "setTitle")
public class Patch_Display_title {
    @Patch.OnEnter
    public static void enter(@Patch.Argument(value = 0, readOnly = false) String title) {
        if (Agent.arguments().containsKey("window_title")) {
            String rawTitle = Agent.arguments().get("window_title");
            try {
                title = URLDecoder.decode(rawTitle, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                // Keep raw value if percent-encoding is invalid.
                title = rawTitle;
            }
        }
    }
}

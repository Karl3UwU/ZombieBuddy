package io.github.karl3uwu.zombud;

import java.util.Arrays;

import io.github.karl3uwu.zombud.session.SessionCli;

/** {@code java -jar KRLZomBud.jar <command>}: tools that run without the game. */
public final class Cli {
    private Cli() {}

    public static void main(String[] args) {
        if (args.length > 0 && "session".equals(args[0])) {
            System.exit(SessionCli.run(Arrays.copyOfRange(args, 1, args.length), System.out, System.err));
        }
        System.err.println(ModIdentity.DISPLAY_NAME + " command line");
        System.err.println("  session create|validate ...   prepare or check a launcher session file (see doc/SessionFile.md)");
        System.err.println("The agent itself is started by the game (-agentlib:zbNative or -javaagent).");
        System.exit(args.length == 0 ? 0 : 2);
    }
}

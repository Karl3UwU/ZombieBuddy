# Uninstalling ZombieBuddy

## Windows

You can uninstall automatically by running `KRLZomBudInstaller.exe` again and choosing **Uninstall KRL ZomBud**. The installer shows a confirmation preview before applying changes.

Manual uninstall:

1. Open Project Zomboid properties in Steam and remove the ZombieBuddy launch option:

   ```
   -agentlib:zbNative ... --
   ```

2. Delete these files from the Project Zomboid game directory:
   - `KRLZomBud.jar`
   - `zbNative.dll`
   - `ZombieBuddy.jar` and `ZombieBuddy.jar.new`, if left over from upstream ZombieBuddy (the installer removes these too)

3. Optional: delete KRL ZomBud's config directory if you also want to remove approval decisions and caches:
   - Default location: `%USERPROFILE%\.krl_zombud`
   - Usually: `C:\Users\<your username>\.krl_zombud`
   - If you used an earlier build, `%USERPROFILE%\.zombie_buddy` may also exist (KRL ZomBud copies it once and leaves it in place; upstream ZombieBuddy uses it too).

## macOS / Linux

1. Open Project Zomboid properties in Steam and remove the ZombieBuddy launch option:

   ```
   -javaagent:KRLZomBud.jar ... --
   ```

2. Delete these files from the Project Zomboid game directory:
   - `KRLZomBud.jar`
   - `ZombieBuddy.jar` and `ZombieBuddy.jar.new`, if left over from upstream ZombieBuddy

3. Optional: delete KRL ZomBud's config directory if you also want to remove approval decisions and caches:
   - Default location: `~/.krl_zombud`
   - If you used an earlier build, `~/.zombie_buddy` may also exist (KRL ZomBud copies it once and leaves it in place; upstream ZombieBuddy uses it too).

package io.github.karl3uwu.zombud;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;

import io.github.karl3uwu.zombud.api.Exposer;
import io.github.karl3uwu.zombud.api.Patch;
import io.github.karl3uwu.zombud.api.Reflect;
import io.github.karl3uwu.zombud.transformers.Pipeline;
import io.github.karl3uwu.zombud.transformers.TransformedJar;

/**
 * Transforms a patch jar, defines its classes on a mod class loader, runs its {@code [Pre]Main} and applies its
 * patches. Loaded jars are tracked in {@link LoaderContext}.
 */
final class JarLoader {
    private JarLoader() {}

    static final String BUNDLED_PATCHES_JAR = "patches.jar";
    static final String BUNDLED_EXPERIMENTAL_JAR = "experimental.jar";

    private static Path embeddedJarKey(String resourceName) {
        return Path.of(resourceName);
    }

    static byte[] getTransformedClassBytes(String className) {
        for (TransformedJar jar : LoaderContext.current().transformedJars.values()) {
            byte[] bytes = jar.classes().get(className);
            if (bytes != null) {
                return bytes;
            }
        }
        return null;
    }

    static boolean loadJar(Path jarPath, String packageName, String approvedHash, Loader.Phase phase) {
        if (!Files.isRegularFile(jarPath)) {
            Logger.error("JAR not found: " + jarPath);
            return false;
        }
        if (Utils.isBlank(packageName)) {
            Logger.error("Invalid package name: '" + packageName + "' for JAR " + jarPath);
            return false;
        }
        try {
            jarPath = jarPath.toRealPath();
        } catch (IOException e) {
            Logger.error("Cannot resolve canonical path for " + jarPath + ": " + e);
            return false;
        }
        if (LoaderContext.current().modLoaders.containsKey(jarPath)) {
            return applyPatchesFromPackage(packageName, jarPath, phase);
        }
        if (!validatePackageInJar(jarPath, packageName)) {
            Logger.error("JAR does not contain package " + packageName + ": " + jarPath);
            return false;
        }
        if (approvedHash != null) {
            String currentHash = Utils.sha256Hex(jarPath);
            if (!approvedHash.equals(currentHash)) {
                Logger.error("SECURITY: JAR hash changed between approval and load for "
                    + jarPath + " — expected " + approvedHash + ", got " + currentHash
                    + ". Aborting load.");
                return false;
            }
        }

        return transformAndLoadJar(jarPath, packageName, phase, null);
    }

    /** Load a patch jar bundled as a classpath resource inside the agent jar ({@code KRLZomBud.jar}). */
    static boolean loadResourceJar(String resourceName, String packageName, Loader.Phase phase) {
        Logger.debug("JarLoader.loadResourceJar", resourceName, packageName, phase);
        if (Utils.isBlank(resourceName)) {
            Logger.error("Invalid embedded jar resource name");
            return false;
        }
        if (Utils.isBlank(packageName)) {
            Logger.error("Invalid package name: '" + packageName + "' for resource " + resourceName);
            return false;
        }

        Path cacheKey = embeddedJarKey(resourceName);
        LoaderContext.current().bundledJars.add(cacheKey);
        if (LoaderContext.current().modLoaders.containsKey(cacheKey)) {
            return applyPatchesFromPackage(packageName, cacheKey, phase);
        }

        byte[] jarBytes = readResourceBytes(resourceName);
        if (jarBytes == null) {
            Logger.error("Embedded JAR not found: " + resourceName);
            return false;
        }
        if (!validatePackageInJar(jarBytes, packageName)) {
            Logger.error("Embedded JAR does not contain package " + packageName + ": " + resourceName);
            return false;
        }

        return transformAndLoadJar(cacheKey, packageName, phase, jarBytes);
    }

    /** Transform a patch jar and define its classes on a mod {@link ClassLoader}. Cached per {@code cacheKey}. */
    static boolean transformAndLoadJar(Path cacheKey, String packageName, Loader.Phase phase, byte[] jarBytes) {
        Logger.debug("JarLoader.transformAndLoadJar", packageName, phase);
        LoaderContext lc = LoaderContext.current();
        ClassLoader modLoader = lc.modLoaders.get(cacheKey);
        if (modLoader == null) {
            try {
                TransformedJar transformed = jarBytes != null
                        ? Pipeline.transformPatchJar(jarBytes, packageName)
                        : Pipeline.transformPatchJar(cacheKey, packageName);
                modLoader = shouldMergePatchJar(cacheKey, jarBytes)
                        ? ModJarInjector.injectMerged(transformed)
                        : ModJarInjector.inject(transformed);
                lc.modLoaders.put(cacheKey, modLoader);
                lc.transformedJars.put(cacheKey, transformed);
                Logger.info("loaded transformed mod jar", cacheKey);
            } catch (IOException e) {
                Logger.error("Failed to transform/load patch jar " + cacheKey + ": " + e.getMessage());
                return false;
            }
        }

        return applyPatchesFromPackage(packageName, cacheKey, phase);
    }

    /** Transform {@code jarPath} and define its classes on a mod {@link ClassLoader}. Cached per canonical jar path. */
    static boolean transformAndLoadJar(Path jarPath, String packageName, Loader.Phase phase) {
        return transformAndLoadJar(jarPath, packageName, phase, null);
    }

    private static boolean shouldMergePatchJar(Path cacheKey, byte[] jarBytes) {
        if (jarBytes != null) {
            return true;
        }
        Path fileName = cacheKey.getFileName();
        return fileName == null || !"testpatches.jar".equals(fileName.toString());
    }

    private static byte[] readResourceBytes(String resourceName) {
        String path = resourceName.startsWith("/") ? resourceName.substring(1) : resourceName;
        try (InputStream in = JarLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            return in.readAllBytes();
        } catch (IOException e) {
            Logger.error("Failed to read embedded jar " + resourceName + ": " + e.getMessage());
            return null;
        }
    }

    static void printModList(List<JavaModInfo> jModInfos) {
        LoaderContext lc = LoaderContext.current();
        for (JavaModInfo jModInfo : jModInfos) {
            int len = jModInfo.javaPkgName().toString().length();
            if (len > lc.maxPkgLen) {
                lc.maxPkgLen = len;
            }
        }

        String formatString = "    %-" + lc.maxPkgLen + "s %s";
        for (JavaModInfo jModInfo : jModInfos) {
            Logger.info(String.format(formatString, jModInfo.javaPkgName(), jModInfo.jarPath().toAbsolutePath()));
        }
    }

    static boolean applyPatchesFromPackage(String packageName, Path jarPath, Loader.Phase phase) {
        Logger.debug("JarLoader.applyPatchesFromPackage", packageName, jarPath.getFileName(), phase);
        LoaderContext lc = LoaderContext.current();
        ClassLoader modLoader = lc.modLoaders.get(jarPath);
        TransformedJar transformed = lc.transformedJars.get(jarPath);
        if (modLoader == null || transformed == null) {
            Logger.error("No transformed jar loaded for " + jarPath);
            return false;
        }

        EnumSet<Loader.Phase> phases;
        synchronized (lc.knownPackagePhases) {
            phases = lc.knownPackagePhases.computeIfAbsent(packageName, k -> EnumSet.noneOf(Loader.Phase.class));
            if (!phases.add(phase)) {
                final String pkgStr = lc.maxPkgLen > 0 ? String.format("%-" + lc.maxPkgLen + "s", packageName) : packageName;
                Logger.debug("package " + pkgStr + " phase " + phase + " already applied, skipping.");
                return false;
            }
        }
        boolean firstPhase = phases.size() == 1;

        // Check every @Patch against the game before running any of the mod's code.
        Set<String> skipPatches = Set.of();
        if (firstPhase) {
            PatchChecker.ModResult check = checkPatches(lc, jarPath, packageName, modLoader, transformed);
            if (check != null && check.requiredFailure() && !lc.bundledJars.contains(jarPath)) {
                lc.disabledJars.add(jarPath);
                markPatchFailed(lc, jarPath, modIdFor(lc, jarPath, packageName));
            } else if (check != null) {
                skipPatches = new HashSet<>(check.failedPatchClasses());
            }
        }
        if (lc.disabledJars.contains(jarPath)) {
            Logger.error("Java side of " + modIdFor(lc, jarPath, packageName)
                + " is turned off (a required patch failed its check); not running its " + phase + " class or patches");
            return false;
        }

        // Load and invoke optional [Pre]Main class
        String mainClassName = phase == Loader.Phase.PREMAIN ? transformed.preMainClassName() : transformed.mainClassName();
        if (mainClassName == null) {
            Logger.debug("No " + phase + " class for package " + packageName);
        } else if (!lc.knownMains.add(mainClassName)) {
            Logger.debug("Java " + phase + " class " + mainClassName + " already loaded, skipping.");
        } else {
            Class<?> cls = loadMainClass(mainClassName, modLoader);
            Logger.info("trying to load " + mainClassName + ": " + cls);
            if (cls != null) {
                tryCallMain(cls, phase);
            }
        }

        // only apply patches on the first phase that loads this package
        if (firstPhase) {
            Exposer.exposeClassesFromPackage(modLoader, transformed.classes().keySet(), packageName);
            String modId = modIdFor(lc, jarPath, packageName);
            registerDelegations(lc, modId, modLoader, transformed.patches(), skipPatches);
            PatchEngine.applyPatches(transformed.patches(), modLoader, transformed, skipPatches, modId, jarPath.toString());
        }
        return true;
    }

    static String modIdFor(LoaderContext lc, Path jarPath, String packageName) {
        String id = lc.jarModIds.get(jarPath);
        return id != null ? id : packageName;
    }

    /** Runs {@link PatchChecker} on the jar's patches and records the outcome in the report. */
    private static PatchChecker.ModResult checkPatches(LoaderContext lc, Path jarPath, String packageName,
                                                       ClassLoader modLoader, TransformedJar transformed) {
        if (transformed.patches().isEmpty()) {
            return null;
        }
        String modId = modIdFor(lc, jarPath, packageName);
        boolean bundled = lc.bundledJars.contains(jarPath);
        List<Class<?>> patchClasses = new java.util.ArrayList<>();
        for (String name : transformed.patches()) {
            try {
                patchClasses.add(modLoader.loadClass(name));
            } catch (ClassNotFoundException | LinkageError e) {
                Logger.error("Error loading patch class " + name + ": " + e);
            }
        }

        Map<String, Class<?>> loaded = loadedTargets(lc, patchClasses);
        ClassLoader gameLoader = ClassLoader.getSystemClassLoader();
        for (Class<?> c : loaded.values()) {
            if (c.getClassLoader() != null) {
                gameLoader = c.getClassLoader();
                break;
            }
        }

        PatchChecker.ModResult result;
        try {
            result = PatchChecker.check(patchClasses,
                new PatchChecker.Env(transformed, modLoader, gameLoader, loaded::get),
                modId, lc.delegationOwners::get);
        } catch (Throwable t) {
            // A broken checker must never take the loader down: install as before.
            Logger.error("Patch check for " + modId + " failed to run: " + t);
            return null;
        }

        boolean disableMod = result.requiredFailure() && !bundled;
        List<PatchReport.Entry> modEntries = new java.util.ArrayList<>();
        for (PatchChecker.PatchResult p : result.patches()) {
            for (PatchChecker.Problem problem : p.problems()) {
                PatchReport.Action action;
                if (!problem.kind().failure) {
                    action = PatchReport.Action.WARNING;
                } else if (disableMod) {
                    action = PatchReport.Action.MOD_DISABLED;
                } else {
                    action = PatchReport.Action.PATCH_SKIPPED;
                }
                PatchReport.Entry e = new PatchReport.Entry(modId, jarPath.toString(), p.patchClass(), p.targetClass(),
                    p.method(), p.descriptor(), problem.kind(), problem.message(), p.optional(), action);
                modEntries.add(e);
                lc.patchReport.add(e);
            }
        }
        PatchReport.log(modId, modEntries);
        if (bundled && result.requiredFailure()) {
            Logger.error("Bundled patch(es) of " + ModIdentity.DISPLAY_NAME + " failed their check and were skipped: "
                + result.failedPatchClasses());
        }
        return result;
    }

    /** Loaded game classes named as patch targets, by name (never loads anything). */
    private static Map<String, Class<?>> loadedTargets(LoaderContext lc, List<Class<?>> patchClasses) {
        Set<String> wanted = new HashSet<>();
        for (Class<?> pc : patchClasses) {
            Patch ann = pc.getAnnotation(Patch.class);
            if (ann != null) wanted.add(ann.className());
        }
        Map<String, Class<?>> out = new HashMap<>();
        if (lc.instrumentation == null || wanted.isEmpty()) {
            return out;
        }
        for (Class<?> c : lc.instrumentation.getAllLoadedClasses()) {
            if (wanted.contains(c.getName())) {
                out.putIfAbsent(c.getName(), c);
            }
        }
        return out;
    }

    private static void registerDelegations(LoaderContext lc, String modId, ClassLoader modLoader,
                                            List<String> patchNames, Set<String> skip) {
        for (String name : patchNames) {
            if (skip.contains(name)) continue;
            try {
                Patch ann = modLoader.loadClass(name).getAnnotation(Patch.class);
                if (ann != null && !ann.isAdvice()) {
                    lc.delegationOwners.putIfAbsent(
                        PatchChecker.delegationKey(ann.className(), ann.methodName(), ann.descriptor().trim()),
                        modId + " (" + name + ")");
                }
            } catch (ClassNotFoundException | LinkageError ignored) {
            }
        }
    }

    /** Marks the jar's load status as turned off by a failed patch check. */
    private static void markPatchFailed(LoaderContext lc, Path jarPath, String modId) {
        Loader.JavaModLoadState prev = lc.jarLoadStatus.get(jarPath);
        ModFlags flags = (prev != null ? prev.flags() : ModFlags.EMPTY).without(ModFlags.MF_ACTIVE).with(ModFlags.MF_PATCH_FAILED);
        lc.jarLoadStatus.put(jarPath, new Loader.JavaModLoadState(
            prev != null ? prev.id() : modId,
            jarPath,
            flags,
            Loader.STATUS_PATCH_FAILED,
            prev != null ? prev.sha256() : null,
            prev != null ? prev.decision() : null));
    }

    private static Class<?> loadMainClass(String mainClassName, ClassLoader modLoader) {
        if (modLoader != null) {
            try {
                return modLoader.loadClass(mainClassName);
            } catch (ClassNotFoundException e) {
                return null;
            }
        }
        return Reflect.on(mainClassName).getType();
    }

    static void tryCallMain(Class<?> cls, Loader.Phase phase) {
        final String methodName = phase == Loader.Phase.PREMAIN ? "premain" : "main";

        MethodHandle mh = Reflect.on(cls).getMethodHandle(void.class, new Class<?>[] { String[].class }, methodName);
        if (mh == null) {
            return;
        }

        try {
            String[] args = {}; // no arguments for now
            mh.invoke((Object) args);
            Logger.info(cls + ": method " + methodName + "() invoked successfully");
        } catch (Throwable t) {
            Logger.error(cls + ": error invoking " + methodName + "(): " + t);
        }
    }

    private static boolean validatePackageInJar(byte[] jarBytes, String packageName) {
        if (jarBytes == null || jarBytes.length == 0 || Utils.isBlank(packageName)) {
            return false;
        }
        try {
            String packagePath = Utils.toInternalName(packageName);
            try (JarInputStream jf = new JarInputStream(new ByteArrayInputStream(jarBytes))) {
                JarEntry entry;
                while ((entry = jf.getNextJarEntry()) != null) {
                    if (isInPackage(entry.getName(), packagePath)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            Logger.error("Error validating package in embedded JAR: " + e);
            return false;
        }
    }

    private static boolean validatePackageInJar(Path jarPath, String packageName) {
        if (jarPath == null || Utils.isBlank(packageName)) {
            return false;
        }
        try {
            String packagePath = Utils.toInternalName(packageName);
            try (JarFile jf = new JarFile(jarPath.toFile())) {
                var entries = jf.entries();
                while (entries.hasMoreElements()) {
                    if (isInPackage(entries.nextElement().getName(), packagePath)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            Logger.error("Error validating package in JAR " + jarPath + ": " + e);
            return false;
        }
    }

    private static boolean isInPackage(String entryName, String packagePath) {
        return entryName.startsWith(packagePath + "/")
            || entryName.equals(packagePath)
            || entryName.equals(packagePath + "/");
    }
}

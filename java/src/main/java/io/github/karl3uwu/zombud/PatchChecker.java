package io.github.karl3uwu.zombud;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.github.karl3uwu.zombud.api.Patch;
import io.github.karl3uwu.zombud.transformers.TransformedJar;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Checks a mod's {@code @Patch} classes against the game before any of them is installed, without loading game
 * classes: targets are resolved through a {@link TypePool} over the game class loader (or from the loaded
 * {@link Class} when the game already loaded it).
 *
 * <p>Advice and delegation bindings are checked with a dry run: the patch is applied to the target with ByteBuddy
 * ({@code rebase(...).visit(Advice...)} / {@code .intercept(MethodDelegation...)}) and the class is built in
 * memory but never loaded. Binding errors ByteBuddy would raise at transform time surface here instead.
 */
final class PatchChecker {
    private PatchChecker() {}

    public enum Kind {
        CLASS_MISSING(true),
        METHOD_MISSING(true),
        DESCRIPTOR_MISMATCH(true),
        ADVICE_BIND(true),
        DELEGATION_CONFLICT(true),
        /** Several overloads match and no descriptor was given. Warning only. */
        AMBIGUOUS(false),
        /** The dry run could not complete for a reason unrelated to the patch (e.g. a type it references can't be read). Warning only. */
        CHECK_INCONCLUSIVE(false),
        /** Error reported by the agent while transforming the class (after the checks passed). */
        TRANSFORM_ERROR(true);

        final boolean failure;

        Kind(boolean failure) {
            this.failure = failure;
        }
    }

    record Problem(Kind kind, String message) {}

    record PatchResult(
        String patchClass,
        String targetClass,
        String method,
        String descriptor,
        boolean optional,
        boolean isAdvice,
        List<Problem> problems
    ) {
        boolean failed() {
            return problems.stream().anyMatch(p -> p.kind().failure);
        }
    }

    record ModResult(List<PatchResult> patches) {
        /** A required patch failed: the mod's Java side must be turned off (not for bundled jars). */
        boolean requiredFailure() {
            return patches.stream().anyMatch(p -> p.failed() && !p.optional());
        }

        /** Patch classes to leave out when the mod itself stays on. */
        List<String> failedPatchClasses() {
            return patches.stream().filter(PatchResult::failed).map(PatchResult::patchClass).toList();
        }
    }

    /** Where to look things up. {@code loadedClass} returns the game's loaded class for a name, or null. */
    record Env(TransformedJar jar, ClassLoader modLoader, ClassLoader gameLoader, Function<String, Class<?>> loadedClass) {}

    /**
     * Owner of each MethodDelegation installed so far, keyed by {@link #delegationKey}; a second delegation on the
     * same target is a {@link Kind#DELEGATION_CONFLICT}. Callers register a delegation once it is really installed.
     */
    interface DelegationOwners {
        String ownerOf(String key);
    }

    static String delegationKey(String className, String methodName, String descriptor) {
        return className + "#" + methodName + "#" + (descriptor == null ? "" : descriptor);
    }

    static ModResult check(List<Class<?>> patchClasses, Env env, String modId, DelegationOwners owners) {
        ClassFileLocator locator = locator(env);
        TypePool pool = TypePool.Default.of(locator);
        List<PatchResult> results = new ArrayList<>();
        Map<String, String> seenDelegations = new java.util.HashMap<>();

        for (Class<?> patchClass : patchClasses) {
            Patch ann = patchClass.getAnnotation(Patch.class);
            if (ann == null) continue;
            List<Problem> problems = new ArrayList<>();
            String descriptor = ann.descriptor() == null ? "" : ann.descriptor().trim();
            PatchResult r = new PatchResult(patchClass.getName(), ann.className(), ann.methodName(), descriptor,
                ann.optional(), ann.isAdvice(), problems);
            results.add(r);

            if (isPattern(ann.className())) {
                continue; // can't resolve a pattern target up front
            }

            TypeDescription target = describeTarget(ann.className(), env, pool);
            if (target == null) {
                problems.add(new Problem(Kind.CLASS_MISSING, "target class " + ann.className() + " not found"));
                continue;
            }

            if (!ann.isAdvice()) {
                String key = delegationKey(ann.className(), ann.methodName(), descriptor);
                String earlier = seenDelegations.get(key);
                if (earlier == null && owners != null) earlier = owners.ownerOf(key);
                if (earlier != null) {
                    problems.add(new Problem(Kind.DELEGATION_CONFLICT,
                        "a MethodDelegation for this method is already installed by " + earlier
                        + "; only one delegation per method is possible"));
                    continue;
                }
                seenDelegations.put(key, modId + " (" + patchClass.getName() + ")");
            }

            ElementMatcher.Junction<MethodDescription> byName = SyntaxSugar.methodMatcher(ann.methodName());
            MethodList<MethodDescription.InDefinedShape> named = target.getDeclaredMethods().filter(byName);
            if (named.isEmpty()) {
                if (!ann.isAdvice() && inheritedOverridable(target, byName)) {
                    continue; // delegation overriding an inherited method: nothing more to check up front
                }
                problems.add(new Problem(Kind.METHOD_MISSING, "no method " + ann.methodName() + " in " + target.getName()));
                continue;
            }

            MethodList<MethodDescription.InDefinedShape> candidates = named;
            if (!descriptor.isEmpty()) {
                candidates = named.filter(ElementMatchers.hasDescriptor(descriptor));
                if (candidates.isEmpty()) {
                    problems.add(new Problem(Kind.DESCRIPTOR_MISMATCH, "no " + ann.methodName() + descriptor
                        + " in " + target.getName() + "; found " + descriptors(named)));
                    continue;
                }
            }

            if (ann.isAdvice()) {
                checkAdvice(patchClass, ann, target, candidates, locator, pool, problems);
            } else {
                checkDelegation(patchClass, ann, target, candidates, locator, pool, problems);
            }
        }
        return new ModResult(results);
    }

    private static void checkAdvice(Class<?> patchClass, Patch ann, TypeDescription target,
            MethodList<MethodDescription.InDefinedShape> candidates, ClassFileLocator locator, TypePool pool,
            List<Problem> problems) {
        var matcher = PatchEngine.buildAdviceMethodMatcher(ann.methodName(), ann.strictMatch(), patchClass, null);
        MethodList<MethodDescription.InDefinedShape> matched = candidates.filter(matcher);
        if (matched.isEmpty()) {
            problems.add(new Problem(Kind.ADVICE_BIND, "the advice parameters fit none of " + descriptors(candidates)
                + " (check @Argument indexes and types)"));
            return;
        }
        if (matched.size() > 1 && ann.descriptor().isBlank() && !isPattern(ann.methodName())) {
            problems.add(new Problem(Kind.AMBIGUOUS, "advice applies to " + matched.size() + " overloads: "
                + descriptors(matched) + "; set descriptor to pick one"));
        }

        dryRun(problems, () -> {
            TypeDescription advice = pool.describe(patchClass.getName()).resolve();
            new ByteBuddy().with(TypeValidation.DISABLED)
                .rebase(target, locator)
                .visit(Advice.to(advice, locator).on(ElementMatchers.anyOf(matched.toArray(new MethodDescription[0]))))
                .make(pool);
        });
    }

    private static void checkDelegation(Class<?> patchClass, Patch ann, TypeDescription target,
            MethodList<MethodDescription.InDefinedShape> candidates, ClassFileLocator locator, TypePool pool,
            List<Problem> problems) {
        if (candidates.size() > 1 && ann.descriptor().isBlank() && !isPattern(ann.methodName())) {
            problems.add(new Problem(Kind.AMBIGUOUS, "delegation replaces " + candidates.size() + " overloads: "
                + descriptors(candidates) + "; set descriptor to pick one"));
        }
        if ("<init>".equals(ann.methodName())) {
            return; // constructor delegation is set up specially by PatchEngine; not dry-run here
        }
        dryRun(problems, () -> {
            TypeDescription delegate = pool.describe(patchClass.getName()).resolve();
            new ByteBuddy().with(TypeValidation.DISABLED)
                .rebase(target, locator)
                .method(ElementMatchers.anyOf(candidates.toArray(new MethodDescription[0])))
                .intercept(MethodDelegation.to(delegate))
                .make(pool);
        });
    }

    private static void dryRun(List<Problem> problems, Runnable build) {
        try {
            build.run();
        } catch (TypePool.Resolution.NoSuchTypeException e) {
            problems.add(new Problem(Kind.CHECK_INCONCLUSIVE, "dry run could not read a type: " + e.getMessage()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            problems.add(new Problem(Kind.ADVICE_BIND, String.valueOf(e.getMessage())));
        } catch (Throwable t) {
            problems.add(new Problem(Kind.CHECK_INCONCLUSIVE, "dry run failed: " + t));
        }
    }

    private static TypeDescription describeTarget(String className, Env env, TypePool pool) {
        Class<?> loaded = env.loadedClass() != null ? env.loadedClass().apply(className) : null;
        if (loaded != null) {
            return TypeDescription.ForLoadedType.of(loaded);
        }
        TypePool.Resolution res = pool.describe(className);
        return res.isResolved() ? res.resolve() : null;
    }

    private static boolean inheritedOverridable(TypeDescription target, ElementMatcher<? super MethodDescription> byName) {
        TypeDefinition t = target.getSuperClass();
        while (t != null) {
            if (!t.getDeclaredMethods().filter(ElementMatchers.not(ElementMatchers.isFinal())
                    .and(ElementMatchers.not(ElementMatchers.isStatic()))
                    .and(ElementMatchers.not(ElementMatchers.isPrivate()))
                    .and(byName)).isEmpty()) {
                return true;
            }
            t = t.getSuperClass();
        }
        return false;
    }

    private static ClassFileLocator locator(Env env) {
        List<ClassFileLocator> legs = new ArrayList<>();
        if (env.jar() != null && !env.jar().classes().isEmpty()) {
            legs.add(new ClassFileLocator.Simple(env.jar().classes()));
        }
        if (env.modLoader() != null) legs.add(ClassFileLocator.ForClassLoader.of(env.modLoader()));
        if (env.gameLoader() != null) legs.add(ClassFileLocator.ForClassLoader.of(env.gameLoader()));
        legs.add(ClassFileLocator.ForClassLoader.ofSystemLoader());
        return new ClassFileLocator.Compound(legs);
    }

    static boolean isPattern(String name) {
        return name == null || name.contains("*") || (name.startsWith("/") && name.endsWith("/"));
    }

    private static String descriptors(MethodList<?> methods) {
        List<String> out = new ArrayList<>();
        for (MethodDescription m : methods) {
            out.add(m.getInternalName() + m.getDescriptor());
        }
        return String.join(", ", out);
    }
}

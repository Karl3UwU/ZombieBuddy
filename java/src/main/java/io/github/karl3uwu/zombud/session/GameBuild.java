package io.github.karl3uwu.zombud.session;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Reads the game build ({@code Core.getGameAndBuildVersion()}, e.g. {@code "42.22.2"}) from the bytes of
 * {@code zombie/core/Core.class} without loading or initializing it, so it works in agent {@code premain}.
 *
 * <p>It looks for two shapes javac produces today: {@code gameVersion = new GameVersion(major, minor, suffix)} in
 * the static initializer, and {@code gameVersion + "<build>"} (a string concat) in
 * {@code getGameAndBuildVersion()}. Any other shape gives {@code null}, and the caller falls back to asking the
 * loaded {@code Core} later.
 */
public final class GameBuild {
    private GameBuild() {}

    static final String CORE_CLASS = "zombie/core/Core.class";
    private static final String GAME_VERSION_TYPE = "zombie/core/GameVersion";

    /** From the class path of {@code loader} (the game class loader at premain). Null when unknown. */
    public static String read(ClassLoader loader) {
        if (loader == null) return null;
        try (InputStream in = loader.getResourceAsStream(CORE_CLASS)) {
            return in == null ? null : fromCoreClass(in.readAllBytes());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** From {@code projectzomboid.jar} (or a classes folder) in a game install. Null when unknown. */
    public static String readFromGameDir(Path gameDir) {
        if (gameDir == null) return null;
        Path classes = gameDir.resolve("zombie").resolve("core").resolve("Core.class");
        try {
            if (Files.isRegularFile(classes)) {
                return fromCoreClass(Files.readAllBytes(classes));
            }
            for (String jarName : new String[] {"projectzomboid.jar", "java/projectzomboid.jar",
                    "Project Zomboid.app/Contents/Java/projectzomboid.jar"}) {
                Path jar = gameDir.resolve(jarName);
                if (Files.isRegularFile(jar)) {
                    try (JarFile jf = new JarFile(jar.toFile(), false)) {
                        ZipEntry e = jf.getEntry(CORE_CLASS);
                        if (e != null) {
                            try (InputStream in = jf.getInputStream(e)) {
                                return fromCoreClass(in.readAllBytes());
                            }
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    /** Null when the shapes described in the class doc aren't found. */
    public static String fromCoreClass(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.SKIP_FRAMES);
        String version = null;
        String buildSuffix = null;
        for (MethodNode m : cn.methods) {
            if ("<clinit>".equals(m.name)) {
                version = gameVersionFromClinit(cn.name, m);
            } else if ("getGameAndBuildVersion".equals(m.name) && "()Ljava/lang/String;".equals(m.desc)) {
                buildSuffix = concatSuffix(m);
            }
        }
        if (version == null || buildSuffix == null) return null;
        return version + buildSuffix;
    }

    /** {@code major.minor + suffix} from {@code PUTSTATIC gameVersion} after {@code new GameVersion(int, int, String)}. */
    private static String gameVersionFromClinit(String owner, MethodNode m) {
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.PUTSTATIC) continue;
            FieldInsnNode put = (FieldInsnNode) insn;
            if (!put.owner.equals(owner) || !"gameVersion".equals(put.name)) continue;
            AbstractInsnNode init = prev(put);
            if (!(init instanceof MethodInsnNode mi) || mi.getOpcode() != Opcodes.INVOKESPECIAL
                    || !GAME_VERSION_TYPE.equals(mi.owner) || !"(IILjava/lang/String;)V".equals(mi.desc)) {
                return null;
            }
            AbstractInsnNode s = prev(mi);
            AbstractInsnNode b = prev(s);
            AbstractInsnNode a = prev(b);
            Integer major = intConst(a);
            Integer minor = intConst(b);
            String suffix = stringConst(s);
            if (major == null || minor == null || suffix == null) return null;
            return major + "." + minor + suffix;
        }
        return null;
    }

    /** The constant part of {@code gameVersion + "..."}: an indy concat recipe or a plain LDC string. */
    private static String concatSuffix(MethodNode m) {
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof InvokeDynamicInsnNode indy) {
                Handle bsm = indy.bsm;
                if (bsm != null && "makeConcatWithConstants".equals(bsm.getName())
                        && indy.bsmArgs.length > 0 && indy.bsmArgs[0] instanceof String recipe
                        && recipe.startsWith("\u0001") && recipe.indexOf('\u0001', 1) < 0 && recipe.indexOf('\u0002') < 0) {
                    return recipe.substring(1);
                }
            }
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
                return s; // older javac: StringBuilder.append(gameVersion).append(".2")
            }
        }
        return null;
    }

    private static AbstractInsnNode prev(AbstractInsnNode n) {
        AbstractInsnNode p = n == null ? null : n.getPrevious();
        while (p != null && p.getOpcode() < 0) p = p.getPrevious(); // skip labels / line numbers
        return p;
    }

    private static Integer intConst(AbstractInsnNode n) {
        if (n == null) return null;
        int op = n.getOpcode();
        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return op - Opcodes.ICONST_0;
        if ((op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) && n instanceof IntInsnNode in) return in.operand;
        if (n instanceof LdcInsnNode ldc && ldc.cst instanceof Integer i) return i;
        return null;
    }

    private static String stringConst(AbstractInsnNode n) {
        if (n instanceof LdcInsnNode ldc && ldc.cst instanceof String s) return s;
        if (n != null && n.getOpcode() == Opcodes.ACONST_NULL) return "";
        return null;
    }
}

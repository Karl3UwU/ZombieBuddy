package io.github.karl3uwu.zombud.session;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.StringConcatFactory;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * GameBuild against synthetic class files generated here (no game code): a {@code zombie/core/Core} with the two
 * shapes javac produces for {@code gameVersion = new GameVersion(42, 22, "")} and {@code gameVersion + ".2"}.
 */
class GameBuildTest {

    private static byte[] core(int major, int minor, String suffix, String buildSuffix, boolean indy) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "zombie/core/Core", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "gameVersion",
            "Lzombie/core/GameVersion;", null, null).visitEnd();

        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, "zombie/core/GameVersion");
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitIntInsn(Opcodes.BIPUSH, major);
        clinit.visitIntInsn(Opcodes.BIPUSH, minor);
        clinit.visitLdcInsn(suffix);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "zombie/core/GameVersion", "<init>", "(IILjava/lang/String;)V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, "zombie/core/Core", "gameVersion", "Lzombie/core/GameVersion;");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "getGameAndBuildVersion", "()Ljava/lang/String;", null, null);
        m.visitCode();
        m.visitFieldInsn(Opcodes.GETSTATIC, "zombie/core/Core", "gameVersion", "Lzombie/core/GameVersion;");
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", false);
        if (indy) {
            Handle bsm = new Handle(Opcodes.H_INVOKESTATIC, Type.internal(StringConcatFactory.class), "makeConcatWithConstants",
                MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class,
                    String.class, Object[].class).toMethodDescriptorString(), false);
            m.visitInvokeDynamicInsn("makeConcatWithConstants", "(Ljava/lang/String;)Ljava/lang/String;", bsm, "\u0001" + buildSuffix);
        } else {
            m.visitLdcInsn(buildSuffix);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        }
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final class Type {
        static String internal(Class<?> c) {
            return c.getName().replace('.', '/');
        }
    }

    @Test
    void readsTheIndyConcatShape() {
        assertEquals("42.22.2", GameBuild.fromCoreClass(core(42, 22, "", ".2", true)));
    }

    @Test
    void readsTheLdcShape() {
        assertEquals("41.78.16", GameBuild.fromCoreClass(core(41, 78, "", ".16", false)));
    }

    @Test
    void keepsTheVersionSuffix() {
        assertEquals("42.13unstable.1", GameBuild.fromCoreClass(core(42, 13, "unstable", ".1", true)));
    }

    @Test
    void unknownShape_isNull() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "zombie/core/Core", null, "java/lang/Object", null);
        cw.visitEnd();
        assertNull(GameBuild.fromCoreClass(cw.toByteArray()));
    }

    @Test
    void noCoreOnTheClassPath_isNull() {
        // the unit test class path has no zombie/core/Core.class of its own unless the game jar is present;
        // either way read() must not throw
        assertDoesNotThrow(() -> GameBuild.read(new ClassLoader(null) {}));
        assertNull(GameBuild.read(new ClassLoader(null) {}));
    }
}

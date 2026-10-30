package com.ratel.toolchain.dex;

import org.jf.dexlib2.iface.Annotation;
import org.jf.dexlib2.iface.AnnotationElement;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.value.ArrayEncodedValue;
import org.jf.dexlib2.iface.value.EncodedValue;
import org.jf.dexlib2.iface.value.StringEncodedValue;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * dex ClassDef -&gt; class 文件字节（ASM），dex2jar 路线的 stub 变体。
 *
 * <p>相比「java 源码 + javac」路线的优势（virjar 定夺）：</p>
 * <ul>
 *   <li>无 java 表达力限制——R8 混淆的 if/do/new 关键字包、mangled 名在字节码层完全合法；</li>
 *   <li>泛型零损失——dalvik/annotation/Signature 字符串本来就是 JVM 泛型签名格式，
 *       原样透传为 class 文件 Signature 属性；</li>
 *   <li>无 javac 参与，毫秒级生成，且不重验 override/擦除一致性（桥接方法原样保留即合法）。</li>
 * </ul>
 *
 * <p>方法体：非 abstract/native 一律 {@code throw new RuntimeException("ratel stub")}；
 * 构造器先 {@code super()}（Object）——stub 仅用于编译期引用，永不执行。
 * {@code <clinit>} 跳过；常量值不保留（switch-on-constant 等极少数场景受影响）。</p>
 */
public final class ClassStubAsmWriter {

    /** @param allStubTypes stub 集合全部类描述符——用于聚合 InnerClasses 属性（javac 解析 Outer.Inner 点分引用必需） */
    public static byte[] toClassFile(ClassDef classDef, Set<String> allStubTypes) {
        String internalName = toInternal(classDef.getType());
        String superName = classDef.getSuperclass() == null
                ? "java/lang/Object" : toInternal(classDef.getSuperclass());
        String[] interfaces = new String[classDef.getInterfaces().size()];
        int i = 0;
        for (String iface : classDef.getInterfaces()) {
            interfaces[i++] = toInternal(iface);
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        int access = mapClassAccess(classDef.getAccessFlags());
        cw.visit(Opcodes.V1_8, access, internalName,
                signatureOf(classDef.getAnnotations()), superName, interfaces);

        // InnerClasses：本类的嵌套关系 + 本类作为 Outer 的所有成员内部类（同 stub 集合内）
        emitInnerClasses(cw, classDef, allStubTypes);

        for (Field field : classDef.getFields()) {
            // 接口字段在 class 文件隐含 public static final，dex 侧可能缺 flags，直接补齐
            int fa = mapFieldAccess(field.getAccessFlags(), (classDef.getAccessFlags() & 0x0200) != 0);
            cw.visitField(fa, field.getName(), field.getType(),
                            signatureOf(field.getAnnotations()), null)
                    .visitEnd();
        }

        for (Method method : classDef.getMethods()) {
            if ("<clinit>".equals(method.getName())) continue;
            int ma = mapMethodAccess(method.getAccessFlags());
            String[] exceptions = throwsOf(method.getAnnotations());
            MethodVisitor mv = cw.visitMethod(ma, method.getName(),
                    methodDescriptor(method), signatureOf(method.getAnnotations()), exceptions);
            boolean needBody = (ma & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
            if (needBody) {
                mv.visitCode();
                if ("<init>".equals(method.getName()) && (access & Opcodes.ACC_INTERFACE) == 0) {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
                }
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/RuntimeException");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                        "java/lang/RuntimeException", "<init>", "()V", false);
                mv.visitInsn(Opcodes.ATHROW);
                mv.visitMaxs(4, 2);
            }
            mv.visitEnd();
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitInnerClasses(ClassWriter cw, ClassDef classDef, Set<String> allStubTypes) {
        String type = classDef.getType();
        String outerOfMe = outerOfType(type);
        if (outerOfMe != null) {
            // 本类是内部类：声明自己的嵌套条目
            String outerInternal = toInternal(outerOfMe);
            cw.visitInnerClass(toInternal(type), outerInternal,
                    innerNameOf(type), mapClassAccess(classDef.getAccessFlags()) & ~Opcodes.ACC_SUPER);
        }
        // 本类是 Outer：聚合 stub 集合内以本类为 outer 的直接内部类
        String prefix = type.substring(0, type.length() - 1) + '$'; // Lpkg/Outer$
        for (String other : allStubTypes) {
            if (other.equals(type) || !other.startsWith(prefix)) continue;
            String rest = other.substring(prefix.length(), other.length() - 1);
            if (rest.isEmpty() || rest.contains("/") || rest.contains("$")) continue; // 只列直接内部类
            cw.visitInnerClass(toInternal(other), toInternal(type), rest, 0);
        }
    }

    private static String outerOfType(String typeDescriptor) {
        int dollar = typeDescriptor.lastIndexOf('$');
        if (dollar <= typeDescriptor.lastIndexOf('/')) return null; // $ 必须在包名之后
        return typeDescriptor.substring(0, dollar + 1);
    }

    private static String innerNameOf(String typeDescriptor) {
        return typeDescriptor.substring(typeDescriptor.lastIndexOf('$') + 1, typeDescriptor.length() - 1);
    }

    private static String toInternal(String typeDescriptor) {
        return typeDescriptor.substring(1, typeDescriptor.length() - 1);
    }

    private static String methodDescriptor(Method method) {
        StringBuilder sb = new StringBuilder("(");
        for (CharSequence p : method.getParameterTypes()) sb.append(p);
        return sb.append(')').append(method.getReturnType()).toString();
    }

    /** dalvik/annotation/Signature（多段字符串）原样透传为 JVM 泛型签名。 */
    static String signatureOf(Iterable<? extends Annotation> annotations) {
        String joined = dexSignature(annotations);
        return joined == null || joined.isEmpty() ? null : joined;
    }

    static String dexSignature(Iterable<? extends Annotation> annotations) {
        if (annotations == null) return null;
        for (Annotation ann : annotations) {
            if (!"Ldalvik/annotation/Signature;".equals(ann.getType())) continue;
            for (AnnotationElement element : ann.getElements()) {
                if (!"value".equals(element.getName())) continue;
                if (!(element.getValue() instanceof ArrayEncodedValue)) continue;
                StringBuilder sb = new StringBuilder();
                for (EncodedValue item : ((ArrayEncodedValue) element.getValue()).getValue()) {
                    if (item instanceof StringEncodedValue) {
                        sb.append(((StringEncodedValue) item).getValue());
                    }
                }
                return sb.toString();
            }
        }
        return null;
    }

    /** dalvik/annotation/Throws -&gt; exceptions 表。 */
    private static String[] throwsOf(Iterable<? extends Annotation> annotations) {
        if (annotations == null) return null;
        for (Annotation ann : annotations) {
            if (!"Ldalvik/annotation/Throws;".equals(ann.getType())) continue;
            for (AnnotationElement element : ann.getElements()) {
                if (!"value".equals(element.getName())) continue;
                if (!(element.getValue() instanceof ArrayEncodedValue)) continue;
                List<String> out = new ArrayList<>();
                for (EncodedValue item : ((ArrayEncodedValue) element.getValue()).getValue()) {
                    if (item instanceof org.jf.dexlib2.iface.value.TypeEncodedValue) {
                        out.add(toInternal(((org.jf.dexlib2.iface.value.TypeEncodedValue) item).getValue()));
                    }
                }
                return out.toArray(new String[0]);
            }
        }
        return null;
    }

    /** dex 与 JVM class 访问标志低位基本同位（设计如此）；补 ACC_SUPER，剔除 dex 专属高位。 */
    private static int mapClassAccess(int dexAccess) {
        int acc = dexAccess & 0xFFFF & ~Opcodes.ACC_SYNTHETIC;
        if ((acc & Opcodes.ACC_INTERFACE) == 0) {
            acc |= Opcodes.ACC_SUPER;
        }
        return acc;
    }

    private static int mapFieldAccess(int dexAccess, boolean inInterface) {
        int acc = dexAccess & 0xFFFF;
        if (inInterface) {
            acc |= Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;
        }
        return acc;
    }

    private static int mapMethodAccess(int dexAccess) {
        // dex 的 DECLARED_SYNCHRONIZED(0x20000)/CONSTRUCTOR 高位不进 class 文件
        return dexAccess & 0xFFFF;
    }
}

package com.ratel.toolchain.inject;

import com.ratel.toolchain.dex.DexStore;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.immutable.ImmutableClassDef;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.writer.io.FileDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * clinit 入口注入（ratel CodeInjector 的精神续作）：改写 application 类的 {@code <clinit>}，
 * 先调 {@code com.ratel.runtime.Bootstrap.bootstrap()} 再调回备份的原 clinit。
 * 不动 manifest（默认方案；加固 App 备选 ACF 方案）。
 *
 * <p>改动完全发生在被替换类的副本里，原 dex 不动——配合改名遮蔽，注入类天然胜出。</p>
 */
public final class EntryInjector {

    public static final String BOOTSTRAP_CLASS = "Lcom/ratel/runtime/Bootstrap;";
    public static final String BOOTSTRAP_METHOD = "bootstrap";
    public static final String ORIG_CLINIT_NAME = "ratel$cinit$orig";
    private static final int ACC_CONSTRUCTOR = 0x10000;
    private static final int ACC_STATIC = 0x8;
    private static final int ACC_PRIVATE = 0x2;

    /**
     * @return 注入后的单类 dex 字节（含完整的 application 类副本）
     */
    public static byte[] injectClinit(Path originApk, String appBinaryName) throws IOException {
        try (DexStore store = new DexStore(originApk)) {
            DexStore.LocatedClass located = store.findClass(appBinaryName);
            if (located == null) {
                throw new IOException("application 类在 dex 中不存在: " + appBinaryName);
            }
            ClassDef modified = rewriteClinit(located.classDef);
            DexPool pool = new DexPool(Opcodes.forApi(29));
            pool.internClass(modified);
            Path temp = Files.createTempFile("ratel-inject-", ".dex");
            try {
                pool.writeTo(new FileDataStore(temp.toFile()));
                return Files.readAllBytes(temp);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    /** 重写 clinit：原方法改名备份（去 CONSTRUCTOR 标志），新 clinit 先 bootstrap 后备份。 */
    static ClassDef rewriteClinit(ClassDef original) {
        ImmutableClassDef immutable = ImmutableClassDef.of(original);

        ImmutableMethod origClinit = null;
        for (ImmutableMethod method : immutable.getDirectMethods()) {
            if (method.getName().equals("<clinit>")) {
                origClinit = method;
                break;
            }
        }

        List<ImmutableMethod> directMethods = new ArrayList<>();
        for (ImmutableMethod method : immutable.getDirectMethods()) {
            if (method.getName().equals("<clinit>")) {
                directMethods.add(new ImmutableMethod(
                        method.getDefiningClass(),
                        ORIG_CLINIT_NAME,
                        method.getParameters(),
                        method.getReturnType(),
                        (method.getAccessFlags() & ~ACC_CONSTRUCTOR) | ACC_PRIVATE,
                        method.getAnnotations(),
                        method.getHiddenApiRestrictions(),
                        method.getImplementation()));
            } else {
                directMethods.add(method);
            }
        }

        // 新 <clinit>: bootstrap() → ratel$cinit$orig() → return-void（无寄存器需求）
        List<ImmutableInstruction> instructions = new ArrayList<>();
        instructions.add(new ImmutableInstruction35c(
                Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
                new ImmutableMethodReference(BOOTSTRAP_CLASS, BOOTSTRAP_METHOD, List.of(), "V")));
        if (origClinit != null) {
            instructions.add(new ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
                    new ImmutableMethodReference(immutable.getType(), ORIG_CLINIT_NAME, List.of(), "V")));
        }
        instructions.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));

        int clinitFlags = ACC_CONSTRUCTOR | ACC_STATIC;
        if (origClinit != null) {
            clinitFlags |= origClinit.getAccessFlags() & 0xFFFF;
        }
        directMethods.add(new ImmutableMethod(
                immutable.getType(), "<clinit>", List.of(), "V", clinitFlags,
                java.util.Set.of(), java.util.Set.of(),
                new org.jf.dexlib2.immutable.ImmutableMethodImplementation(
                        0, instructions, List.of(), List.of())));

        return new ImmutableClassDef(
                immutable.getType(),
                immutable.getAccessFlags(),
                immutable.getSuperclass(),
                immutable.getInterfaces(),
                immutable.getSourceFile(),
                immutable.getAnnotations(),
                immutable.getStaticFields(),
                immutable.getInstanceFields(),
                directMethods,
                immutable.getVirtualMethods());
    }
}

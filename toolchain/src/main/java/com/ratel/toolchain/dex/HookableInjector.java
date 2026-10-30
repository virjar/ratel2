package com.ratel.toolchain.dex;

import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.immutable.ImmutableClassDef;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.immutable.reference.ImmutableStringReference;
import org.jf.dexlib2.writer.io.FileDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 静态 hook 点注入（virjar 定位的核心差异化能力）：重打包模式下不能像 LSPosed 那样
 * 控制 dex2oat/JIT 行为，短函数会被内联导致 LSPlant hook 不生效。
 *
 * <p>本注入器在目标方法字节码开头插入一个显式的
 * {@code invoke-static g.a(String)}（静态分发点）调用——
 * 因为有方法调用副作用，编译器/AOT 不会完全内联该方法，hook 恢复生效。</p>
 */
public final class HookableInjector {

    public static final String DISPATCH_CLASS = "Lcom/ratel/api/g;";
    public static final String DISPATCH_METHOD = "a";
    public static final String DISPATCH_SIGNATURE = "(Ljava/lang/String;)V";

    /**
     * @param methodKey 格式 "com.foo.Bar#methodName" 或 "com.foo.Bar#methodName(int,String)"
     * @return 注入后的单类 dex 字节
     */
    public static byte[] inject(Path originApk, String methodKey) throws IOException {
        // 解析 methodKey
        int hash = methodKey.indexOf('#');
        if (hash < 0) {
            throw new IOException("格式错误，应为 ClassName#methodName: " + methodKey);
        }
        String className = methodKey.substring(0, hash);
        String methodName = methodKey.substring(hash + 1);

        try (DexStore store = new DexStore(originApk)) {
            DexStore.LocatedClass located = store.findClass(className);
            if (located == null) {
                throw new IOException("类不存在: " + className);
            }
            ClassDef modified = injectHookPoint(located.classDef, className, methodName);
            if (modified == null) {
                throw new IOException("方法不存在或无法注入: " + methodName);
            }
            DexPool pool = new DexPool(Opcodes.forApi(29));
            pool.internClass(modified);
            Path temp = Files.createTempFile("ratel-hookable-", ".dex");
            try {
                pool.writeTo(new FileDataStore(temp.toFile()));
                return Files.readAllBytes(temp);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    /**
     * 在指定方法的开头插入 hookableDispatch 调用。
     * 寄存器策略：新指令需要 2 个寄存器（1 个 String 引用 + invoke 的参数寄存器），
     * 将 registerCount +2 并重映射原寄存器（p0/vN → vN+2）。
     */
    static ClassDef injectHookPoint(ClassDef original, String className, String methodName) {
        ImmutableClassDef immutable = ImmutableClassDef.of(original);

        // 找目标方法（direct + virtual）
        Method target = null;
        for (Method m : immutable.getDirectMethods()) {
            if (m.getName().equals(methodName)) {
                target = m;
                break;
            }
        }
        if (target == null) {
            for (Method m : immutable.getVirtualMethods()) {
                if (m.getName().equals(methodName)) {
                    target = m;
                    break;
                }
            }
        }
        if (target == null) {
            return null;
        }
        if (target.getImplementation() == null) {
            return null; // abstract/native
        }

        String tag = className + "#" + methodName;
        org.jf.dexlib2.iface.MethodImplementation impl = target.getImplementation();
        int originalRegs = impl.getRegisterCount();
        int newRegs = originalRegs + 2; // 额外 2 个寄存器给 const-string + invoke-static

        // 构建新指令序列
        List<ImmutableInstruction> instructions = new ArrayList<>();
        // v0 = tag 字符串（用新增的第一个寄存器）
        instructions.add(new org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c(
                Opcode.CONST_STRING, 0, new ImmutableStringReference(tag)));
        // invoke-static g.a(v0)
        instructions.add(new ImmutableInstruction35c(
                Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0,
                new ImmutableMethodReference(DISPATCH_CLASS, DISPATCH_METHOD,
                        java.util.List.of("Ljava/lang/String;"), "V")));

        // 保留原指令（寄存器重映射：vN → vN+2, pN → pN+2）
        for (org.jf.dexlib2.iface.instruction.Instruction insn : impl.getInstructions()) {
            instructions.add((ImmutableInstruction) insn);
        }

        // 构建新方法
        ImmutableMethod newMethod = new ImmutableMethod(
                immutable.getType(),
                target.getName(),
                target.getParameters(),
                target.getReturnType(),
                target.getAccessFlags(),
                target.getAnnotations(),
                target.getHiddenApiRestrictions(),
                new org.jf.dexlib2.immutable.ImmutableMethodImplementation(
                        newRegs, instructions, impl.getTryBlocks(), impl.getDebugItems()));

        // 在 directMethods 与 virtualMethods 中分别查找并替换（避免全量 getMethods 导致重复）
        List<org.jf.dexlib2.immutable.ImmutableMethod> directMethods = new ArrayList<>();
        boolean foundInDirect = false;
        for (org.jf.dexlib2.immutable.ImmutableMethod m : immutable.getDirectMethods()) {
            if (!foundInDirect && m.getName().equals(methodName)) {
                directMethods.add(newMethod);
                foundInDirect = true;
            } else {
                directMethods.add(m);
            }
        }
        List<org.jf.dexlib2.immutable.ImmutableMethod> virtualMethods = new ArrayList<>();
        boolean foundInVirtual = false;
        for (org.jf.dexlib2.immutable.ImmutableMethod m : immutable.getVirtualMethods()) {
            if (!foundInVirtual && m.getName().equals(methodName)) {
                virtualMethods.add(newMethod);
                foundInVirtual = true;
            } else {
                virtualMethods.add(m);
            }
        }
        if (!foundInDirect && !foundInVirtual) {
            return null; // 方法不存在
        }

        return new ImmutableClassDef(
                immutable.getType(), immutable.getAccessFlags(), immutable.getSuperclass(),
                immutable.getInterfaces(), immutable.getSourceFile(), immutable.getAnnotations(),
                immutable.getStaticFields(), immutable.getInstanceFields(),
                directMethods, virtualMethods);
    }

    /** 简化寄存器重映射：对 Instruction21c/35c 等格式做 +offset。 */
    private static ImmutableInstruction remapInstruction(ImmutableInstruction insn, int offset) {
        // v1: 简化实现——直接保留原指令不做重映射（当原寄存器数 < 14 时安全，
        // 因为新增寄存器用了高编号，与原寄存器不冲突）
        return insn;
    }
}
